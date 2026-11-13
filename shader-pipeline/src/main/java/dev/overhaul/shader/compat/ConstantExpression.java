package dev.overhaul.shader.compat;

/** Integer arithmetic a preprocessed array length can still hold, such as {@code 256/2}: literals, + - * / and parentheses. */
final class ConstantExpression {
    private final String text;
    private int at;

    private ConstantExpression(String text) { this.text = text; }

    static int evaluate(String expression) {
        var parser = new ConstantExpression(expression.replaceAll("\s+", ""));
        int value = parser.sum();
        if (parser.at != parser.text.length()) throw unsupported(expression);
        return value;
    }

    private int sum() {
        int value = product();
        while (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            char operator = text.charAt(at++);
            value = operator == '+' ? Math.addExact(value, product()) : Math.subtractExact(value, product());
        }
        return value;
    }

    private int product() {
        int value = factor();
        while (at < text.length() && (text.charAt(at) == '*' || text.charAt(at) == '/')) {
            char operator = text.charAt(at++);
            int right = factor();
            if (operator == '*') value = Math.multiplyExact(value, right);
            else if (right == 0) throw unsupported(text);
            else value /= right;
        }
        return value;
    }

    private int factor() {
        if (at < text.length() && text.charAt(at) == '(') {
            at++;
            int value = sum();
            if (at >= text.length() || text.charAt(at++) != ')') throw unsupported(text);
            return value;
        }
        if (at < text.length() && text.charAt(at) == '-') { at++; return Math.negateExact(factor()); }
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
        if (start == at) throw unsupported(text);
        String digits = text.substring(start, at);
        if (at < text.length() && (text.charAt(at) == 'u' || text.charAt(at) == 'U')) at++;
        return Integer.parseInt(digits);
    }

    private static UnsupportedShaderException unsupported(String expression) {
        return new UnsupportedShaderException("Uniform array length must be a constant integer expression: " + expression);
    }
}
