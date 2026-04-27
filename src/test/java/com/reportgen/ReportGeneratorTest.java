package com.reportgen;

import static org.assertj.core.api.Assertions.assertThat;

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
                        "revenue": "sum(col('sales_table', 'Revenue'))",
                        "orders": "count(col('sales_table', 'OrderId'))",
                        "avg_check": "kpi.revenue / kpi.orders"
                      }
                    },
                    "layout": [
                      { "type": "kpiRow", "items": ["kpi.revenue", "kpi.orders", "kpi.avg_check"] },
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
                .contains("Alice");
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
