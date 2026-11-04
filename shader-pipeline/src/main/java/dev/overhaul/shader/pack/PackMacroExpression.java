package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.Map;

/**
 * Integer {@code #if} expressions for shaders.properties, with C preprocessor rules:
 * an undefined identifier is 0, and a macro expands to its value. A macro without an
 * integer value fails instead of guessing, as does any operator outside this grammar.
 */
final class PackMacroExpression {
    private static final int MAX_DEPTH = 32;
    private final String text;
    private final Map<String,String> defines;
    private final int depth;
    private int position;

    private PackMacroExpression(String text, Map<String,String> defines, int depth) {
        if (depth > MAX_DEPTH) throw invalid("Conditional expression nests too deeply: " + text);
        this.text = text; this.defines = defines; this.depth = depth;
    }

    static boolean evaluate(String expression, Map<String,String> defines) {
        return value(expression,defines,0) != 0;
    }

    private static long value(String expression, Map<String,String> defines, int depth) {
        var parser = new PackMacroExpression(expression,defines,depth);
        long result = parser.or();
        parser.skipSpace();
        if (parser.position != expression.length()) throw invalid("Unsupported conditional expression: " + expression);
        return result;
    }

    private long or() {
        long left = and();
        while (match("||")) { long right = and(); left = left != 0 || right != 0 ? 1 : 0; }
        return left;
    }
    private long and() {
        long left = equality();
        while (match("&&")) { long right = equality(); left = left != 0 && right != 0 ? 1 : 0; }
        return left;
    }
    private long equality() {
        long left = relational();
        while (true) {
            if (match("==")) left = left == relational() ? 1 : 0;
            else if (match("!=")) left = left != relational() ? 1 : 0;
            else return left;
        }
    }
    private long relational() {
        long left = additive();
        while (true) {
            if (match("<=")) left = left <= additive() ? 1 : 0;
            else if (match(">=")) left = left >= additive() ? 1 : 0;
            else if (match("<")) left = left < additive() ? 1 : 0;
            else if (match(">")) left = left > additive() ? 1 : 0;
            else return left;
        }
    }
    private long additive() {
        long left = multiplicative();
        while (true) {
            if (match("+")) left += multiplicative();
            else if (match("-")) left -= multiplicative();
            else return left;
        }
    }
    private long multiplicative() {
        long left = unary();
        while (true) {
            if (match("*")) left *= unary();
            else if (match("/")) left /= divisor();
            else if (match("%")) left %= divisor();
            else return left;
        }
    }
    private long divisor() {
        long right = unary();
        if (right == 0) throw invalid("Division by zero in conditional: " + text);
        return right;
    }
    /** Operators are collected iteratively so a long run of them cannot exhaust the stack. */
    private long unary() {
        StringBuilder operators = new StringBuilder();
        while (match("!") || match("-") || match("+")) {
            operators.append(text.charAt(position-1));
            if (operators.length() > MAX_DEPTH) throw invalid("Conditional expression nests too deeply: " + text);
        }
        long value = primary();
        for (int index = operators.length()-1; index >= 0; index--) {
            char operator = operators.charAt(index);
            if (operator == '!') value = value == 0 ? 1 : 0;
            else if (operator == '-') value = -value;
        }
        return value;
    }
    private long primary() {
        if (match("(")) {
            long value = new PackMacroExpression(text,defines,depth+1).resumeAt(this);
            if (!match(")")) throw invalid("Unbalanced conditional expression: " + text);
            return value;
        }
        skipSpace();
        if (position >= text.length()) throw invalid("Incomplete conditional expression: " + text);
        char first = text.charAt(position);
        if (Character.isDigit(first)) return number();
        if (Character.isLetter(first) || first == '_') return identifier();
        throw invalid("Unsupported conditional expression: " + text);
    }
    /** Parenthesized groups share the parent's cursor while their nesting stays bounded. */
    private long resumeAt(PackMacroExpression parent) {
        position = parent.position;
        long value = or();
        parent.position = position;
        return value;
    }
    private long number() {
        int start = position;
        while (position < text.length() && Character.isLetterOrDigit(text.charAt(position))) position++;
        String literal = text.substring(start,position);
        try { return Long.decode(literal); }
        catch (NumberFormatException failure) { throw invalid("Non-integer literal in conditional: " + literal); }
    }
    private long identifier() {
        String name = word();
        if (!name.equals("defined")) return macro(name);
        boolean parenthesized = match("(");
        skipSpace();
        String operand = word();
        if (operand.isEmpty()) throw invalid("defined requires a macro name: " + text);
        if (parenthesized && !match(")")) throw invalid("Unbalanced defined operator: " + text);
        return defines.containsKey(operand) ? 1 : 0;
    }
    private long macro(String name) {
        String definition = defines.get(name);
        if (definition == null) return 0;
        if (definition.isBlank()) throw invalid("Macro has no integer value in conditional: " + name);
        return value(definition,defines,depth+1);
    }
    private String word() {
        int start = position;
        while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) position++;
        return text.substring(start,position);
    }
    private boolean match(String token) {
        skipSpace();
        if (!text.startsWith(token,position)) return false;
        position += token.length();
        return true;
    }
    private void skipSpace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
    }
    private static UnsupportedShaderException invalid(String message) { return new UnsupportedShaderException(message); }
}
