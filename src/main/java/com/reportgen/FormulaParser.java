package com.reportgen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class FormulaParser {
    private final String formula;
    private final Map<String, DataTable> tables;
    private final Map<String, Object> context;
    private final Map<String, FunctionConfig> functions;
    private final Map<String, Object> variables;
    private final Deque<String> callStack;
    private int position;

    FormulaParser(String formula, Map<String, DataTable> tables, Map<String, Object> context,
            Map<String, FunctionConfig> functions) {
        this(formula, tables, context, functions, Map.of(), new ArrayDeque<>());
    }

    private FormulaParser(String formula, Map<String, DataTable> tables, Map<String, Object> context,
            Map<String, FunctionConfig> functions, Map<String, Object> variables, Deque<String> callStack) {
        this.formula = formula == null ? "" : formula;
        this.tables = tables;
        this.context = context;
        this.functions = functions == null ? Map.of() : functions;
        this.variables = variables == null ? Map.of() : variables;
        this.callStack = callStack;
    }

    Object parse() {
        Object result = expression();
        skipWhitespace();
        if (position != formula.length()) {
            throw new IllegalArgumentException("Unexpected token in formula: " + formula.substring(position));
        }
        return result;
    }

    static Set<String> referencedContextPaths(String formula, Set<String> knownPaths,
            Map<String, FunctionConfig> functions) {
        return referencedContextPaths(formula, knownPaths, functions, Set.of(), new ArrayDeque<>());
    }

    private static Set<String> referencedContextPaths(String formula, Set<String> knownPaths,
            Map<String, FunctionConfig> functions, Set<String> localNames, Deque<String> functionStack) {
        Set<String> references = new LinkedHashSet<>();
        for (Token token : scanIdentifiers(formula)) {
            if (knownPaths.contains(token.value()) && !localNames.contains(token.value())) {
                references.add(token.value());
            }
        }

        Map<String, FunctionConfig> safeFunctions = functions == null ? Map.of() : functions;
        for (Token token : scanIdentifiers(formula)) {
            if (!token.functionCall() || !safeFunctions.containsKey(token.value())
                    || functionStack.contains(token.value())) {
                continue;
            }
            FunctionConfig config = safeFunctions.get(token.value());
            functionStack.addLast(token.value());
            references.addAll(referencedContextPaths(config.formula, knownPaths, safeFunctions,
                    new LinkedHashSet<>(config.args), functionStack));
            functionStack.removeLast();
        }
        return references;
    }

    private Object expression() {
        return logicalOr();
    }

    private Object logicalOr() {
        Object result = logicalAnd();
        while (true) {
            skipWhitespace();
            if (match("||") || matchWordOperator("or")) {
                Object right = logicalAnd();
                result = truthy(result) || truthy(right);
            } else {
                return result;
            }
        }
    }

    private Object logicalAnd() {
        Object result = equality();
        while (true) {
            skipWhitespace();
            if (match("&&") || matchWordOperator("and")) {
                Object right = equality();
                result = truthy(result) && truthy(right);
            } else {
                return result;
            }
        }
    }

    private Object equality() {
        Object result = comparison();
        while (true) {
            skipWhitespace();
            if (match("==")) {
                result = valuesEqual(result, comparison());
            } else if (match("!=")) {
                result = !valuesEqual(result, comparison());
            } else {
                return result;
            }
        }
    }

    private Object comparison() {
        Object result = additive();
        while (true) {
            skipWhitespace();
            if (match(">=")) {
                result = compare(result, additive()) >= 0;
            } else if (match("<=")) {
                result = compare(result, additive()) <= 0;
            } else if (match(">")) {
                result = compare(result, additive()) > 0;
            } else if (match("<")) {
                result = compare(result, additive()) < 0;
            } else {
                return result;
            }
        }
    }

    private Object additive() {
        Object result = term();
        while (true) {
            skipWhitespace();
            if (match("+")) {
                result = number(result) + number(term());
            } else if (match("-")) {
                result = number(result) - number(term());
            } else {
                return result;
            }
        }
    }

    private Object term() {
        Object result = unary();
        while (true) {
            skipWhitespace();
            if (match("*")) {
                result = number(result) * number(unary());
            } else if (match("/")) {
                result = number(result) / number(unary());
            } else {
                return result;
            }
        }
    }

    private Object unary() {
        skipWhitespace();
        if (match("+")) {
            return number(unary());
        }
        if (match("-")) {
            return -number(unary());
        }
        if (match("!") || matchWordOperator("not")) {
            return !truthy(unary());
        }
        return primary();
    }

    private Object primary() {
        skipWhitespace();
        if (match("(")) {
            Object result = expression();
            skipWhitespace();
            if (!match(")")) {
                throw new IllegalArgumentException("Missing closing parenthesis in formula: " + formula);
            }
            return result;
        }
        if (startsString()) {
            return parseString();
        }
        if (startsNumber()) {
            return parseNumber();
        }
        if (position < formula.length() && isIdentifierStart(formula.charAt(position))) {
            return identifierOrFunction();
        }
        throw new IllegalArgumentException("Expected value in formula: " + formula);
    }

    private Object identifierOrFunction() {
        String name = parseIdentifier();
        skipWhitespace();
        if (match("(")) {
            int open = position - 1;
            int close = closingParenthesis(open);
            List<String> args = splitArguments(formula.substring(position, close));
            position = close + 1;
            return invokeFunction(name, args);
        }
        return resolveName(name);
    }

    private Object invokeFunction(String name, List<String> args) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "if" -> invokeIf(args);
            case "col" -> invokeCol(args);
            case "sum" -> sum(flattenEvaluatedArgs(args));
            case "count" -> count(flattenEvaluatedArgs(args));
            case "avg" -> avg(flattenEvaluatedArgs(args));
            case "min" -> min(flattenEvaluatedArgs(args));
            case "max" -> max(flattenEvaluatedArgs(args));
            case "round" -> invokeRound(args);
            case "abs" -> Math.abs(number(requireEvaluatedArg(args, 1, "abs").get(0)));
            case "ceil" -> Math.ceil(number(requireEvaluatedArg(args, 1, "ceil").get(0)));
            case "floor" -> Math.floor(number(requireEvaluatedArg(args, 1, "floor").get(0)));
            case "coalesce" -> invokeCoalesce(args);
            case "concat" -> invokeConcat(args);
            default -> invokeCustomFunction(name, args);
        };
    }

    private Object invokeIf(List<String> args) {
        if (args.size() != 3) {
            throw new IllegalArgumentException("Function if expects 3 arguments");
        }
        return truthy(evaluateArgument(args.get(0))) ? evaluateArgument(args.get(1)) : evaluateArgument(args.get(2));
    }

    private Object invokeCol(List<String> args) {
        List<Object> evaluated = requireEvaluatedArg(args, 2, "col");
        String tableId = stringValue(evaluated.get(0));
        String column = stringValue(evaluated.get(1));
        DataTable table = tables.get(tableId);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + tableId);
        }
        return table.column(column);
    }

    private Object invokeRound(List<String> args) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalArgumentException("Function round expects 1 or 2 arguments");
        }
        double value = number(evaluateArgument(args.get(0)));
        int digits = args.size() == 2 ? (int) number(evaluateArgument(args.get(1))) : 0;
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private Object invokeCoalesce(List<String> args) {
        for (String arg : args) {
            Object value = evaluateArgument(arg);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Object invokeConcat(List<String> args) {
        StringBuilder result = new StringBuilder();
        for (String arg : args) {
            Object value = evaluateArgument(arg);
            if (value != null) {
                result.append(value);
            }
        }
        return result.toString();
    }

    private Object invokeCustomFunction(String name, List<String> args) {
        FunctionConfig config = functions.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Unknown function: " + name);
        }
        if (config.formula == null || config.formula.isBlank()) {
            throw new IllegalArgumentException("Function formula is empty: " + name);
        }
        if (config.args.size() != args.size()) {
            throw new IllegalArgumentException("Function " + name + " expects " + config.args.size()
                    + " arguments, got " + args.size());
        }
        if (callStack.contains(name)) {
            throw new IllegalArgumentException("Recursive function call: " + name);
        }

        Map<String, Object> scopedVariables = new LinkedHashMap<>(variables);
        for (int i = 0; i < args.size(); i++) {
            scopedVariables.put(config.args.get(i), evaluateArgument(args.get(i)));
        }

        callStack.addLast(name);
        try {
            return new FormulaParser(config.formula, tables, context, functions, scopedVariables, callStack).parse();
        } finally {
            callStack.removeLast();
        }
    }

    private List<Object> flattenEvaluatedArgs(List<String> args) {
        List<Object> values = new ArrayList<>();
        for (String arg : args) {
            Object value = evaluateArgument(arg);
            if (value instanceof Collection<?> collection) {
                values.addAll(collection);
            } else {
                values.add(value);
            }
        }
        return values;
    }

    private List<Object> requireEvaluatedArg(List<String> args, int expected, String function) {
        if (args.size() != expected) {
            throw new IllegalArgumentException("Function " + function + " expects " + expected + " arguments");
        }
        List<Object> values = new ArrayList<>();
        for (String arg : args) {
            values.add(evaluateArgument(arg));
        }
        return values;
    }

    private Object evaluateArgument(String argument) {
        return new FormulaParser(argument, tables, context, functions, variables, callStack).parse();
    }

    private Object resolveName(String name) {
        return switch (name) {
            case "true" -> true;
            case "false" -> false;
            case "null" -> null;
            default -> variables.containsKey(name) ? variables.get(name) : value(name);
        };
    }

    @SuppressWarnings("unchecked")
    private Object value(String path) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw new IllegalArgumentException("Unknown value: " + path);
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    private double sum(Collection<?> values) {
        return values.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).mapToDouble(this::number).sum();
    }

    private long count(Collection<?> values) {
        return values.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).count();
    }

    private double avg(Collection<?> values) {
        List<?> present = values.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).toList();
        return present.isEmpty() ? 0 : sum(present) / present.size();
    }

    private double min(Collection<?> values) {
        return values.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).mapToDouble(this::number)
                .min().orElse(0);
    }

    private double max(Collection<?> values) {
        return values.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).mapToDouble(this::number)
                .max().orElse(0);
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return Double.parseDouble(String.valueOf(value).replace(" ", "").replace(',', '.'));
    }

    private boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        return !String.valueOf(value).isBlank();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compare(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            return Double.compare(number(left), number(right));
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            return Double.compare(number(left), number(right)) == 0;
        }
        return Objects.equals(left, right);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double parseNumber() {
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

    private String parseString() {
        char quote = formula.charAt(position++);
        StringBuilder result = new StringBuilder();
        while (position < formula.length()) {
            char current = formula.charAt(position++);
            if (current == quote) {
                return result.toString();
            }
            if (current == '\\' && position < formula.length()) {
                char escaped = formula.charAt(position++);
                result.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> escaped;
                });
            } else {
                result.append(current);
            }
        }
        throw new IllegalArgumentException("Missing closing quote in formula: " + formula);
    }

    private String parseIdentifier() {
        int start = position;
        position++;
        while (position < formula.length() && isIdentifierPart(formula.charAt(position))) {
            position++;
        }
        return formula.substring(start, position);
    }

    private List<String> splitArguments(String text) {
        List<String> args = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quote != 0) {
                if (current == '\\') {
                    i++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                args.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = text.substring(start).trim();
        if (!tail.isEmpty() || !text.isBlank()) {
            args.add(tail);
        }
        return args;
    }

    private int closingParenthesis(int openParenthesis) {
        int depth = 0;
        char quote = 0;
        for (int i = openParenthesis; i < formula.length(); i++) {
            char current = formula.charAt(i);
            if (quote != 0) {
                if (current == '\\') {
                    i++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '(') {
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

    private boolean startsString() {
        return position < formula.length() && (formula.charAt(position) == '\'' || formula.charAt(position) == '"');
    }

    private boolean startsNumber() {
        if (position >= formula.length()) {
            return false;
        }
        char current = formula.charAt(position);
        return Character.isDigit(current)
                || current == '.' && position + 1 < formula.length() && Character.isDigit(formula.charAt(position + 1));
    }

    private boolean match(String expected) {
        if (formula.startsWith(expected, position)) {
            position += expected.length();
            return true;
        }
        return false;
    }

    private boolean matchWordOperator(String expected) {
        int end = position + expected.length();
        if (end > formula.length() || !formula.regionMatches(position, expected, 0, expected.length())) {
            return false;
        }
        boolean leftBounded = position == 0 || !isIdentifierPart(formula.charAt(position - 1));
        boolean rightBounded = end == formula.length() || !isIdentifierPart(formula.charAt(end));
        if (leftBounded && rightBounded) {
            position = end;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (position < formula.length() && Character.isWhitespace(formula.charAt(position))) {
            position++;
        }
    }

    private static List<Token> scanIdentifiers(String source) {
        List<Token> tokens = new ArrayList<>();
        if (source == null) {
            return tokens;
        }
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (quote != 0) {
                if (current == '\\') {
                    i++;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (!isIdentifierStart(current)) {
                continue;
            }

            int start = i;
            i++;
            while (i < source.length() && isIdentifierPart(source.charAt(i))) {
                i++;
            }
            String value = source.substring(start, i);
            int next = i;
            while (next < source.length() && Character.isWhitespace(source.charAt(next))) {
                next++;
            }
            tokens.add(new Token(value, next < source.length() && source.charAt(next) == '('));
            i--;
        }
        return tokens;
    }

    private static boolean isIdentifierStart(char current) {
        return Character.isLetter(current) || current == '_';
    }

    private static boolean isIdentifierPart(char current) {
        return Character.isLetterOrDigit(current) || current == '_' || current == '.';
    }

    private record Token(String value, boolean functionCall) {
    }
}
