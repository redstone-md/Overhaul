package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.Map;

/** Boolean-only grammar used by program.*.enabled; undefined options are diagnostics. */
final class BooleanOptionExpression {
    private final String expression;
    private final Map<String,Boolean> options;
    private int cursor;
    private int depth;

    private BooleanOptionExpression(String expression, Map<String,Boolean> options) {
        this.expression = expression; this.options = options;
    }
    static boolean evaluate(String expression, Map<String,Boolean> options) {
        if (expression.length()>4096) throw new UnsupportedShaderException("Program option expression exceeds size limit");
        var parser = new BooleanOptionExpression(expression,options);
        boolean value = parser.or();
        parser.space();
        if (parser.cursor != expression.length()) throw parser.invalid();
        return value;
    }
    private boolean or() {
        boolean value = and();
        while (take("||")) { boolean right = and(); value = value || right; }
        return value;
    }
    private boolean and() {
        boolean value = unary();
        while (take("&&")) { boolean right = unary(); value = value && right; }
        return value;
    }
    private boolean unary() {
        if (++depth>64) throw new UnsupportedShaderException("Program option expression exceeds nesting limit");
        try {
        if (take("!")) return !unary();
        if (take("(")) { boolean value = or(); if (!take(")")) throw invalid(); return value; }
        space(); int start = cursor;
        while (cursor<expression.length() && (Character.isLetterOrDigit(expression.charAt(cursor)) || expression.charAt(cursor)=='_')) cursor++;
        if (cursor == start) throw invalid();
        String name = expression.substring(start,cursor);
        if (name.equals("true")) return true;
        if (name.equals("false")) return false;
        Boolean value = options.get(name);
        if (value == null) throw new UnsupportedShaderException("Undefined shader option in program expression: "+name);
        return value;
        } finally { depth--; }
    }
    private boolean take(String token) {
        space(); if (!expression.startsWith(token,cursor)) return false;
        cursor += token.length(); return true;
    }
    private void space() { while (cursor<expression.length() && Character.isWhitespace(expression.charAt(cursor))) cursor++; }
    private UnsupportedShaderException invalid() { return new UnsupportedShaderException("Invalid program option expression: "+expression); }
}
