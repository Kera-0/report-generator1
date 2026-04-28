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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Pattern AGGREGATE = Pattern.compile(
            "(sum|count)\\s*\\(\\s*col\\s*\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)\\s*\\)");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public void generate(String configPath, String inputXlsxPath, String outputHtmlPath) throws Exception {
        Path configFile = Path.of(configPath);
        ReportConfig config = loadConfig(configFile);
        Map<String, DataTable> tables = loadTables(config, configFile.toAbsolutePath().getParent(),
                Path.of(inputXlsxPath));
        Map<String, Object> context = resolveContext(config.context, tables);
        String html = renderHtml(config, tables, context);

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
        if (file.report.sources.isEmpty() || file.report.tables.isEmpty()) {
            throw new IllegalArgumentException("Config must contain at least one source and one table");
        }
        return file.report;
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

    private Map<String, Object> resolveContext(Map<String, Object> rawContext, Map<String, DataTable> tables) {
        Map<String, Object> unresolved = new LinkedHashMap<>();
        flatten("", rawContext, unresolved);
        Map<String, Object> resolved = new LinkedHashMap<>();

        for (int pass = 0; pass < unresolved.size() + 3 && !unresolved.isEmpty(); pass++) {
            Iterator<Map.Entry<String, Object>> iterator = unresolved.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                try {
                    putNested(resolved, entry.getKey(), evaluate(String.valueOf(entry.getValue()), tables, resolved));
                    iterator.remove();
                } catch (RuntimeException ignored) {
                    // A later pass may resolve references like kpi.revenue / kpi.orders.
                }
            }
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalArgumentException("Cannot resolve context formulas: " + unresolved.keySet());
        }
        return resolved;
    }

    private Object evaluate(String formula, Map<String, DataTable> tables, Map<String, Object> context) {
        return new FormulaParser(formula, tables, context).parse();
    }

    private Object aggregate(String formula, Map<String, DataTable> tables) {
        Matcher aggregate = AGGREGATE.matcher(formula.trim());
        if (aggregate.matches()) {
            List<Object> values = table(tables, aggregate.group(2)).column(aggregate.group(3));
            return "count".equals(aggregate.group(1)) ? count(values) : sum(values);
        }
        throw new IllegalArgumentException("Unsupported aggregate formula: " + formula);
    }

    private DataTable table(Map<String, DataTable> tables, String id) {
        DataTable table = tables.get(id);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + id);
        }
        return table;
    }

    private double sum(Collection<?> values) {
        return values.stream().filter(v -> v != null).mapToDouble(this::number).sum();
    }

    private long count(Collection<?> values) {
        return values.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).count();
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value).replace(" ", "").replace(',', '.'));
    }

    @SuppressWarnings("unchecked")
    private Object value(String path, Map<String, Object> context) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw new IllegalArgumentException("Unknown value: " + path);
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    private String renderHtml(ReportConfig config, Map<String, DataTable> tables, Map<String, Object> context) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(config.title)).append("</title>")
                .append("<style>body{font-family:Arial,sans-serif;margin:32px;background:#f6f7f9;color:#1e2530}")
                .append("main{max-width:1100px;margin:auto}.kpis{display:flex;gap:12px;flex-wrap:wrap}")
                .append(".kpi,table{background:white;border:1px solid #d9dee7;border-radius:8px}")
                .append(".kpi{padding:16px;min-width:170px}.label{color:#677386;font-size:12px;text-transform:uppercase}")
                .append(".value{font-size:28px;font-weight:700;color:#256f7a}table{width:100%;border-collapse:collapse;overflow:hidden}")
                .append("th,td{padding:10px 12px;border-bottom:1px solid #d9dee7;text-align:left}th{background:#e3f2f0}</style>")
                .append("</head><body><main><h1>").append(escape(config.title)).append("</h1>")
                .append("<p>Generated at ").append(LocalDateTime.now().format(DATE_TIME)).append("</p>");

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
        return html.append("</main></body></html>").toString();
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class ConfigFile {
        public ReportConfig report;
    }

    public static class ReportConfig {
        public String title;
        public List<SourceConfig> sources = new ArrayList<>();
        public List<TableConfig> tables = new ArrayList<>();
        public Map<String, Object> context = new LinkedHashMap<>();
        public List<LayoutItem> layout = new ArrayList<>();
    }

    public static class SourceConfig {
        public String id;
        public String file;
    }

    public static class TableConfig {
        public String id;
        public String source;
        public String sheet;
        public String range;
        public int headerRow = 1;
    }

    public static class LayoutItem {
        public String type;
        public List<String> items = new ArrayList<>();
        public String table;
        public List<String> columns = new ArrayList<>();
        public String title;
        public String text;
    }

    private static class DataTable {
        final String id;
        final List<String> headers;
        final List<Map<String, Object>> rows;

        DataTable(String id, List<String> headers, List<Map<String, Object>> rows) {
            this.id = id;
            this.headers = List.copyOf(headers);
            this.rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }

        List<Object> column(String name) {
            if (!headers.contains(name)) {
                throw new IllegalArgumentException("Unknown column '" + name + "' in table '" + id + "'");
            }
            return rows.stream().map(row -> row.get(name)).toList();
        }
    }

    private class FormulaParser {
        private final String formula;
        private final Map<String, DataTable> tables;
        private final Map<String, Object> context;
        private int position;

        FormulaParser(String formula, Map<String, DataTable> tables, Map<String, Object> context) {
            this.formula = formula;
            this.tables = tables;
            this.context = context;
        }

        Object parse() {
            Object result = expression();
            skipWhitespace();
            if (position != formula.length()) {
                throw new IllegalArgumentException("Unexpected token in formula: " + formula.substring(position));
            }
            return result;
        }

        private Object expression() {
            Object result = term();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    result = number(result) + number(term());
                } else if (match('-')) {
                    result = number(result) - number(term());
                } else {
                    return result;
                }
            }
        }

        private Object term() {
            Object result = factor();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    result = number(result) * number(factor());
                } else if (match('/')) {
                    result = number(result) / number(factor());
                } else {
                    return result;
                }
            }
        }

        private Object factor() {
            skipWhitespace();
            if (match('+')) {
                return number(factor());
            }
            if (match('-')) {
                return -number(factor());
            }
            if (match('(')) {
                Object result = expression();
                skipWhitespace();
                if (!match(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis in formula: " + formula);
                }
                return result;
            }
            if (startsAggregate()) {
                return parseAggregate();
            }
            if (startsNumber()) {
                return parseNumber();
            }
            return parseValuePath();
        }

        private Object parseAggregate() {
            int start = position;
            while (position < formula.length() && Character.isLetter(formula.charAt(position))) {
                position++;
            }
            skipWhitespace();
            if (!match('(')) {
                throw new IllegalArgumentException("Expected aggregate function in formula: " + formula);
            }
            int close = closingParenthesis(position - 1);
            position = close + 1;
            return aggregate(formula.substring(start, position), tables);
        }

        private Object parseNumber() {
            int start = position;
            while (position < formula.length()) {
                char current = formula.charAt(position);
                if (!Character.isDigit(current) && current != '.' && current != ',') {
                    break;
                }
                position++;
            }
            return number(formula.substring(start, position));
        }

        private Object parseValuePath() {
            int start = position;
            while (position < formula.length()) {
                char current = formula.charAt(position);
                if (Character.isWhitespace(current) || current == '+' || current == '-' || current == '*'
                        || current == '/' || current == ')' || current == '(') {
                    break;
                }
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("Expected value in formula: " + formula);
            }
            return value(formula.substring(start, position), context);
        }

        private boolean startsAggregate() {
            return startsFunction("sum") || startsFunction("count");
        }

        private boolean startsFunction(String name) {
            if (!formula.startsWith(name, position)) {
                return false;
            }
            int current = position + name.length();
            while (current < formula.length() && Character.isWhitespace(formula.charAt(current))) {
                current++;
            }
            return current < formula.length() && formula.charAt(current) == '(';
        }

        private boolean startsNumber() {
            if (position >= formula.length()) {
                return false;
            }
            char current = formula.charAt(position);
            return Character.isDigit(current)
                    || current == '.' && position + 1 < formula.length() && Character.isDigit(formula.charAt(position + 1));
        }

        private int closingParenthesis(int openParenthesis) {
            int depth = 0;
            boolean inQuote = false;
            for (int i = openParenthesis; i < formula.length(); i++) {
                char current = formula.charAt(i);
                if (current == '\'') {
                    inQuote = !inQuote;
                }
                if (inQuote) {
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            throw new IllegalArgumentException("Missing closing parenthesis in formula: " + formula);
        }

        private boolean match(char expected) {
            if (position < formula.length() && formula.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < formula.length() && Character.isWhitespace(formula.charAt(position))) {
                position++;
            }
        }
    }
}
