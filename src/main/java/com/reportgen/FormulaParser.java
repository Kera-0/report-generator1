package com.reportgen;

import java.util.Map;

class FormulaParser {
    private final String formula;
    private final Map<String, DataTable> tables;
    private final Map<String, Object> context;
    private final ReportGenerator reportGenerator;
    private int position;

    FormulaParser(String formula, Map<String, DataTable> tables, Map<String, Object> context,
            ReportGenerator reportGenerator) {
        this.formula = formula;
        this.tables = tables;
        this.context = context;
        this.reportGenerator = reportGenerator;
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
                result = reportGenerator.number(result) + reportGenerator.number(term());
            } else if (match('-')) {
                result = reportGenerator.number(result) - reportGenerator.number(term());
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
                result = reportGenerator.number(result) * reportGenerator.number(factor());
            } else if (match('/')) {
                result = reportGenerator.number(result) / reportGenerator.number(factor());
            } else {
                return result;
            }
        }
    }

    private Object factor() {
        skipWhitespace();
        if (match('+')) {
            return reportGenerator.number(factor());
        }
        if (match('-')) {
            return -reportGenerator.number(factor());
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
        return reportGenerator.aggregate(formula.substring(start, position), tables);
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
        return reportGenerator.number(formula.substring(start, position));
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
        return reportGenerator.value(formula.substring(start, position), context);
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
