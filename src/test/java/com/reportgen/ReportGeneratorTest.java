package com.reportgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportGeneratorTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesHtmlReportFromConfigAndWorkbook() throws Exception {
        Path workbook = tempDir.resolve("sales.xlsx");
        writeWorkbook(workbook);

        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "report": {
                    "title": "Sales Report",
                    "theme": "default",
                    "sources": [
                      { "id": "sales", "file": "missing.xlsx" }
                    ],
                    "tables": [
                      {
                        "id": "sales_table",
                        "source": "sales",
                        "sheet": "Sales",
                        "range": "A1:D3",
                        "headerRow": 1
                      }
                    ],
                    "context": {
                      "kpi": {
                        "projected_minus_avg": "kpi.projected_revenue - kpi.avg_check",
                        "avg_check": "kpi.revenue / kpi.orders",
                        "projected_revenue": "kpi.revenue * kpi.orders",
                        "revenue": "sum(col('sales_table', 'Revenue'))",
                        "orders": "count(col('sales_table', 'OrderId'))",
                        "revenue_plus_orders": "kpi.revenue + kpi.orders"
                      }
                    },
                    "layout": [
                      {
                        "type": "kpiRow",
                        "items": [
                          "kpi.revenue",
                          "kpi.orders",
                          "kpi.avg_check",
                          "kpi.revenue_plus_orders",
                          "kpi.projected_revenue",
                          "kpi.projected_minus_avg"
                        ]
                      },
                      { "type": "table", "table": "sales_table", "columns": ["Region", "Manager", "Revenue"] }
                    ]
                  }
                }
                """, StandardCharsets.UTF_8);

        Path output = tempDir.resolve("report.html");
        new ReportGenerator().generate(config.toString(), workbook.toString(), output.toString());

        String html = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(html)
                .contains("<title>Sales Report</title>")
                .contains("Revenue")
                .contains("300")
                .contains("302")
                .contains("600")
                .contains("450")
                .contains("Alice");
    }

    @Test
    void supportsCustomFunctionsBuiltInsAndCustomHtmlTemplate() throws Exception {
        Path workbook = tempDir.resolve("sales.xlsx");
        writeWorkbook(workbook);

        Path template = tempDir.resolve("template.html");
        Files.writeString(template, """
                <!doctype html>
                <html>
                  <head><title>{{ title }}</title><style>{{style}}</style></head>
                  <body>
                    <header data-generated="{{generatedAt}}">{{context.kpi.status}}</header>
                    <main>{{content}}</main>
                  </body>
                </html>
                """, StandardCharsets.UTF_8);

        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "report": {
                    "title": "Custom Report",
                    "template": "template.html",
                    "sources": [
                      { "id": "sales", "file": "missing.xlsx" }
                    ],
                    "tables": [
                      {
                        "id": "sales_table",
                        "source": "sales",
                        "sheet": "Sales",
                        "range": "A1:D3",
                        "headerRow": 1
                      }
                    ],
                    "functions": {
                      "safe_divide": {
                        "args": ["part", "total"],
                        "formula": "if(total == 0, 0, part / total)"
                      },
                      "bonus": {
                        "args": ["value"],
                        "formula": "if(value > 250, value * 0.1, 0)"
                      }
                    },
                    "context": {
                      "kpi": {
                        "avg_check": "round(safe_divide(kpi.revenue, kpi.orders), 2)",
                        "status": "if(kpi.avg_check > 100, 'high', 'low')",
                        "bonus": "bonus(kpi.revenue)",
                        "revenue": "sum(col('sales_table', 'Revenue'))",
                        "orders": "count(col('sales_table', 'OrderId'))",
                        "largest_order": "max(col('sales_table', 'Revenue'))"
                      }
                    },
                    "layout": [
                      { "type": "kpiRow", "items": ["kpi.avg_check", "kpi.status", "kpi.bonus", "kpi.largest_order"] }
                    ]
                  }
                }
                """, StandardCharsets.UTF_8);

        Path output = tempDir.resolve("report.html");
        new ReportGenerator().generate(config.toString(), workbook.toString(), output.toString());

        String html = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(html)
                .contains("<title>Custom Report</title>")
                .contains("<header data-generated=")
                .contains(">high</header>")
                .contains("<main><section class=\"kpis\">")
                .contains("150")
                .contains("30")
                .contains("200")
                .doesNotContain("<h1>Custom Report</h1>");
    }

    @Test
    void reportsContextFormulaCycles() throws Exception {
        Path workbook = tempDir.resolve("sales.xlsx");
        writeWorkbook(workbook);

        Path config = tempDir.resolve("config.json");
        Files.writeString(config, """
                {
                  "report": {
                    "title": "Cycle Report",
                    "sources": [
                      { "id": "sales", "file": "missing.xlsx" }
                    ],
                    "tables": [
                      {
                        "id": "sales_table",
                        "source": "sales",
                        "sheet": "Sales",
                        "range": "A1:D3",
                        "headerRow": 1
                      }
                    ],
                    "context": {
                      "kpi": {
                        "a": "kpi.b + 1",
                        "b": "kpi.a + 1"
                      }
                    },
                    "layout": []
                  }
                }
                """, StandardCharsets.UTF_8);

        Path output = tempDir.resolve("report.html");
        assertThatThrownBy(() -> new ReportGenerator().generate(config.toString(), workbook.toString(),
                output.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cycle in context formulas")
                .hasMessageContaining("kpi.a -> kpi.b -> kpi.a");
    }

    private void writeWorkbook(Path path) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sales");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Region");
            header.createCell(1).setCellValue("Manager");
            header.createCell(2).setCellValue("OrderId");
            header.createCell(3).setCellValue("Revenue");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("North");
            row1.createCell(1).setCellValue("Alice");
            row1.createCell(2).setCellValue("A-1");
            row1.createCell(3).setCellValue(100.0);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("South");
            row2.createCell(1).setCellValue("Bob");
            row2.createCell(2).setCellValue("A-2");
            row2.createCell(3).setCellValue(200.0);

            try (OutputStream output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
    }
}
