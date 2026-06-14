package com.reportgen;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

public class ReportGenerator {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public void generate(String configPath, String inputXlsxPath, String outputHtmlPath) throws Exception {
        Path configFile = Path.of(configPath);
        ReportConfig config = loadConfig(configFile);
        Path configDir = configFile.toAbsolutePath().getParent();
        Map<String, DataTable> tables = loadTables(config, configDir, Path.of(inputXlsxPath));
        Map<String, Object> context = resolveContext(config.context, tables, config.functions);
        String html = renderHtml(config, tables, context, configDir);

        Path output = Path.of(outputHtmlPath);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, html, StandardCharsets.UTF_8);
    }

    private ReportConfig loadConfig(Path configFile) throws IOException {
        ConfigFile file = mapper.readValue(configFile.toFile(), ConfigFile.class);
        if (file.report == null || isBlank(file.report.title)) {
            throw new IllegalArgumentException("Config must contain report.title");
        }
        if (file.report.functions == null) {
            file.report.functions = new LinkedHashMap<>();
        }
        if (file.report.sources.isEmpty() || file.report.tables.isEmpty()) {
            throw new IllegalArgumentException("Config must contain at least one source and one table");
        }
        validateFunctions(file.report.functions);
        return file.report;
    }

    private void validateFunctions(Map<String, FunctionConfig> functions) {
        for (Map.Entry<String, FunctionConfig> entry : functions.entrySet()) {
            String name = entry.getKey();
            FunctionConfig function = entry.getValue();
            if (isBlank(name) || !isIdentifier(name)) {
                throw new IllegalArgumentException("Invalid function name: " + name);
            }
            if (function == null || isBlank(function.formula)) {
                throw new IllegalArgumentException("Function must contain formula: " + name);
            }
            if (function.args == null) {
                function.args = new ArrayList<>();
            }
            Set<String> args = new LinkedHashSet<>();
            for (String arg : function.args) {
                if (isBlank(arg) || !isIdentifier(arg)) {
                    throw new IllegalArgumentException("Invalid argument in function " + name + ": " + arg);
                }
                if (!args.add(arg)) {
                    throw new IllegalArgumentException("Duplicate argument in function " + name + ": " + arg);
                }
            }
        }
    }

    private Map<String, DataTable> loadTables(ReportConfig config, Path configDir, Path cliInputPath) throws Exception {
        Map<String, Path> sources = new LinkedHashMap<>();
        for (SourceConfig source : config.sources) {
            sources.put(source.id, resolveSource(source.file, configDir, cliInputPath));
        }

        Map<Path, Workbook> openWorkbooks = new LinkedHashMap<>();
        Map<String, DataTable> tables = new LinkedHashMap<>();
        try {
            for (TableConfig table : config.tables) {
                Path path = sources.get(table.source);
                if (path == null) {
                    throw new IllegalArgumentException("Unknown source: " + table.source);
                }
                Workbook workbook = openWorkbooks.computeIfAbsent(path, this::openWorkbook);
                tables.put(table.id, readTable(workbook, table));
            }
        } finally {
            for (Workbook workbook : openWorkbooks.values()) {
                workbook.close();
            }
        }
        return tables;
    }

    private Path resolveSource(String configuredPath, Path configDir, Path cliInputPath) {
        Path configured = Path.of(configuredPath);
        if (!configured.isAbsolute() && configDir != null) {
            configured = configDir.resolve(configured).normalize();
        }
        if (Files.exists(configured)) {
            return configured;
        }
        if (Files.exists(cliInputPath)) {
            return cliInputPath;
        }
        return configured;
    }

    private Workbook openWorkbook(Path path) {
        try {
            if (!Files.exists(path)) {
                throw new IOException("Spreadsheet file does not exist: " + path);
            }

            try (var inputStream = Files.newInputStream(path)) {
                return WorkbookFactory.create(inputStream);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot open spreadsheet: " + path, e);
        }
    }

    private DataTable readTable(Workbook workbook, TableConfig config) {
        Sheet sheet = workbook.getSheet(config.sheet);
        if (sheet == null) {
            throw new IllegalArgumentException("Sheet not found: " + config.sheet);
        }

        CellRangeAddress range = isBlank(config.range)
                ? new CellRangeAddress(sheet.getFirstRowNum(), sheet.getLastRowNum(), 0, lastColumn(sheet))
                : CellRangeAddress.valueOf(config.range);
        int headerRowIndex = range.getFirstRow() + Math.max(config.headerRow, 1) - 1;
        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) {
            throw new IllegalArgumentException("Header row is empty for table: " + config.id);
        }

        List<String> headers = new ArrayList<>();
        for (int col = range.getFirstColumn(); col <= range.getLastColumn(); col++) {
            Cell cell = headerRow.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String header = cell == null ? "" : cell.toString().trim();
            headers.add(isBlank(header) ? "Column" + (col - range.getFirstColumn() + 1) : header);
        }

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int rowIndex = headerRowIndex + 1; rowIndex <= Math.min(range.getLastRow(), sheet.getLastRowNum()); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            boolean hasValue = false;
            for (int i = 0; i < headers.size(); i++) {
                Object value = readCell(row.getCell(range.getFirstColumn() + i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL),
                        evaluator);
                values.put(headers.get(i), value);
                hasValue = hasValue || value != null && !String.valueOf(value).isBlank();
            }
            if (hasValue) {
                rows.add(values);
            }
        }

        return new DataTable(config.id, headers, rows);
    }

    private int lastColumn(Sheet sheet) {
        Row firstRow = sheet.getRow(sheet.getFirstRowNum());
        return firstRow == null || firstRow.getLastCellNum() < 0 ? 0 : firstRow.getLastCellNum() - 1;
    }

    private Object readCell(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            CellValue value = evaluator.evaluate(cell);
            return value == null ? null : switch (value.getCellType()) {
                case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue() : value.getNumberValue();
                case STRING -> blankToNull(value.getStringValue());
                case BOOLEAN -> value.getBooleanValue();
                default -> null;
            };
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue() : cell.getNumericCellValue();
            case STRING -> blankToNull(cell.getStringCellValue());
            case BOOLEAN -> cell.getBooleanCellValue();
            default -> null;
        };
    }

    private Map<String, Object> resolveContext(Map<String, Object> rawContext, Map<String, DataTable> tables,
            Map<String, FunctionConfig> functions) {
        Map<String, Object> flatContext = new LinkedHashMap<>();
        flatten("", rawContext, flatContext);

        Map<String, FormulaNode> nodes = new LinkedHashMap<>();
        Set<String> contextPaths = flatContext.keySet();
        for (Map.Entry<String, Object> entry : flatContext.entrySet()) {
            Set<String> dependencies = entry.getValue() instanceof String formula
                    ? FormulaParser.referencedContextPaths(formula, contextPaths, functions)
                    : Set.of();
            nodes.put(entry.getKey(), new FormulaNode(entry.getKey(), entry.getValue(), dependencies));
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String path : sortContextNodes(nodes)) {
            FormulaNode node = nodes.get(path);
            Object value = node.rawValue() instanceof String formula
                    ? evaluate(formula, tables, resolved, functions)
                    : node.rawValue();
            putNested(resolved, node.path(), value);
        }
        return resolved;
    }

    private Object evaluate(String formula, Map<String, DataTable> tables, Map<String, Object> context,
            Map<String, FunctionConfig> functions) {
        return new FormulaParser(formula, tables, context, functions).parse();
    }

    private List<String> sortContextNodes(Map<String, FormulaNode> nodes) {
        List<String> ordered = new ArrayList<>();
        Map<String, VisitState> states = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String path : nodes.keySet()) {
            visitContextNode(path, nodes, states, stack, ordered);
        }
        return ordered;
    }

    private void visitContextNode(String path, Map<String, FormulaNode> nodes, Map<String, VisitState> states,
            Deque<String> stack, List<String> ordered) {
        VisitState state = states.get(path);
        if (state == VisitState.DONE) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw new IllegalArgumentException("Cycle in context formulas: " + formatCycle(stack, path));
        }

        states.put(path, VisitState.VISITING);
        stack.addLast(path);
        for (String dependency : nodes.get(path).dependencies()) {
            if (nodes.containsKey(dependency)) {
                visitContextNode(dependency, nodes, states, stack, ordered);
            }
        }
        stack.removeLast();
        states.put(path, VisitState.DONE);
        ordered.add(path);
    }

    private String formatCycle(Deque<String> stack, String repeatedPath) {
        List<String> cycle = new ArrayList<>();
        boolean inCycle = false;
        for (String path : stack) {
            if (path.equals(repeatedPath)) {
                inCycle = true;
            }
            if (inCycle) {
                cycle.add(path);
            }
        }
        cycle.add(repeatedPath);
        return String.join(" -> ", cycle);
    }

    private DataTable table(Map<String, DataTable> tables, String id) {
        DataTable table = tables.get(id);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + id);
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    Object value(String path, Map<String, Object> context) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw new IllegalArgumentException("Unknown value: " + path);
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    private String renderHtml(ReportConfig config, Map<String, DataTable> tables, Map<String, Object> context,
            Path configDir) throws IOException {
        String generatedAt = LocalDateTime.now().format(DATE_TIME);
        String content = renderContent(config, tables, context);
        String template = loadTemplate(config, configDir);
        return applyTemplate(template, config, context, generatedAt, content);
    }

    private String renderContent(ReportConfig config, Map<String, DataTable> tables, Map<String, Object> context) {
        StringBuilder html = new StringBuilder();

        for (LayoutItem item : config.layout) {
            if ("kpiRow".equals(item.type)) {
                html.append("<section class=\"kpis\">");
                for (String path : item.items) {
                    html.append("<article class=\"kpi\"><div class=\"label\">").append(escape(label(path))).append("</div>")
                            .append("<div class=\"value\">").append(escape(format(value(path, context)))).append("</div></article>");
                }
                html.append("</section>");
            } else if ("heading".equals(item.type)) {
                html.append("<h2>").append(escape(item.text)).append("</h2>");
            } else if ("table".equals(item.type)) {
                DataTable table = table(tables, item.table);
                List<String> columns = item.columns.isEmpty() ? table.headers : item.columns;
                if (!isBlank(item.title)) {
                    html.append("<h3>").append(escape(item.title)).append("</h3>");
                }
                html.append("<table><thead><tr>");
                for (String column : columns) {
                    html.append("<th>").append(escape(column)).append("</th>");
                }
                html.append("</tr></thead><tbody>");
                for (Map<String, Object> row : table.rows) {
                    html.append("<tr>");
                    for (String column : columns) {
                        html.append("<td>").append(escape(format(row.get(column)))).append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</tbody></table>");
            }
        }
        return html.toString();
    }

    private String loadTemplate(ReportConfig config, Path configDir) throws IOException {
        if (isBlank(config.template)) {
            return defaultTemplate();
        }
        Path templatePath = Path.of(config.template);
        if (!templatePath.isAbsolute() && configDir != null) {
            templatePath = configDir.resolve(templatePath).normalize();
        }
        if (!Files.exists(templatePath)) {
            throw new IllegalArgumentException("HTML template does not exist: " + templatePath);
        }
        return Files.readString(templatePath, StandardCharsets.UTF_8);
    }

    private String defaultTemplate() {
        return "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
                + "<title>{{title}}</title><style>{{style}}</style></head><body><main>"
                + "<h1>{{title}}</h1><p>Generated at {{generatedAt}}</p>{{content}}"
                + "</main></body></html>";
    }

    private String defaultStyles() {
        return "body{font-family:Arial,sans-serif;margin:32px;background:#f6f7f9;color:#1e2530}"
                + "main{max-width:1100px;margin:auto}.kpis{display:flex;gap:12px;flex-wrap:wrap}"
                + ".kpi,table{background:white;border:1px solid #d9dee7;border-radius:8px}"
                + ".kpi{padding:16px;min-width:170px}.label{color:#677386;font-size:12px;text-transform:uppercase}"
                + ".value{font-size:28px;font-weight:700;color:#256f7a}"
                + "table{width:100%;border-collapse:collapse;overflow:hidden}"
                + "th,td{padding:10px 12px;border-bottom:1px solid #d9dee7;text-align:left}th{background:#e3f2f0}";
    }

    private String applyTemplate(String template, ReportConfig config, Map<String, Object> context, String generatedAt,
            String content) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", escape(config.title));
        values.put("report.title", escape(config.title));
        values.put("generatedAt", escape(generatedAt));
        values.put("report.generatedAt", escape(generatedAt));
        values.put("content", content);
        values.put("report.content", content);
        values.put("style", defaultStyles());
        values.put("styles", defaultStyles());
        values.put("report.style", defaultStyles());
        values.put("report.styles", defaultStyles());

        Map<String, Object> flatContext = new LinkedHashMap<>();
        flatten("", context, flatContext);
        for (Map.Entry<String, Object> entry : flatContext.entrySet()) {
            values.put(entry.getKey(), escape(format(entry.getValue())));
            values.put("context." + entry.getKey(), escape(format(entry.getValue())));
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            if (replacement == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void flatten(String prefix, Object value, Map<String, Object> target) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isBlank() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), target);
            }
        } else if (!prefix.isBlank()) {
            target.put(prefix, value);
        }
    }

    @SuppressWarnings("unchecked")
    private void putNested(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[i], ignored -> new LinkedHashMap<>());
        }
        current.put(parts[parts.length - 1], value);
    }

    private String label(String path) {
        String value = path.substring(path.lastIndexOf('.') + 1).replace('_', ' ');
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
            symbols.setGroupingSeparator(' ');
            return new DecimalFormat(Math.rint(number.doubleValue()) == number.doubleValue() ? "#,##0" : "#,##0.##",
                    symbols).format(number.doubleValue());
        }
        return String.valueOf(value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isIdentifier(String value) {
        if (isBlank(value) || !isIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!isIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record FormulaNode(String path, Object rawValue, Set<String> dependencies) {
    }

    private enum VisitState {
        VISITING,
        DONE
    }
}
