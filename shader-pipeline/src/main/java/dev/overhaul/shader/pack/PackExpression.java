package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * Compiles the OptiFine custom-uniform expression language into scalar closures, one per vector
 * component, so a frame's evaluation allocates nothing. Comparisons and logic yield 1 or 0.
 */
final class PackExpression {
    interface Scalar { double get(); }
    interface Scope {
        /** The components of a named value; an unknown name fails. */
        Scalar[] variable(String name);
        /** Seconds the running frame covers, read each time a stateful function evaluates. */
        double frameSeconds();
    }

    private static final int MAX_DEPTH = 64;
    private final String text;
    private final Scope scope;
    private int position, depth;

    private PackExpression(String text, Scope scope) { this.text = text; this.scope = scope; }

    static Scalar[] parse(String text, Scope scope) {
        var parser = new PackExpression(text,scope);
        Scalar[] result = parser.conditional();
        parser.skipSpace();
        if (parser.position != text.length()) throw invalid("Unexpected text at "+parser.position);
        return result;
    }

    private Scalar[] conditional() {
        if (++depth > MAX_DEPTH) throw invalid("Expression nests too deeply");
        Scalar[] condition = or();
        if (match("?")) {
            Scalar[] yes = conditional();
            if (!match(":")) throw invalid("Conditional lacks ':'");
            condition = choose(single(condition),yes,conditional());
        }
        depth--;
        return condition;
    }
    private Scalar[] or() {
        Scalar[] left = and();
        while (match("||")) { Scalar a = single(left), b = single(and()); left = one(() -> a.get() != 0 || b.get() != 0 ? 1 : 0); }
        return left;
    }
    private Scalar[] and() {
        Scalar[] left = comparison();
        while (match("&&")) { Scalar a = single(left), b = single(comparison()); left = one(() -> a.get() != 0 && b.get() != 0 ? 1 : 0); }
        return left;
    }
    private Scalar[] comparison() {
        Scalar[] left = additive();
        while (true) {
            if (match("==")) left = compare(left,additive(),(a,b) -> a == b ? 1 : 0);
            else if (match("!=")) left = compare(left,additive(),(a,b) -> a != b ? 1 : 0);
            else if (match("<=")) left = compare(left,additive(),(a,b) -> a <= b ? 1 : 0);
            else if (match(">=")) left = compare(left,additive(),(a,b) -> a >= b ? 1 : 0);
            else if (match("<")) left = compare(left,additive(),(a,b) -> a < b ? 1 : 0);
            else if (match(">")) left = compare(left,additive(),(a,b) -> a > b ? 1 : 0);
            else return left;
        }
    }
    private Scalar[] additive() {
        Scalar[] left = multiplicative();
        while (true) {
            if (match("+")) left = zip(left,multiplicative(),Double::sum);
            else if (match("-")) left = zip(left,multiplicative(),(a,b) -> a-b);
            else return left;
        }
    }
    private Scalar[] multiplicative() {
        Scalar[] left = unary();
        while (true) {
            if (match("*")) left = zip(left,unary(),(a,b) -> a*b);
            else if (match("/")) left = zip(left,unary(),(a,b) -> b == 0 ? 0 : a/b);
            else if (match("%")) left = zip(left,unary(),(a,b) -> b == 0 ? 0 : a%b);
            else return left;
        }
    }
    private Scalar[] unary() {
        if (!text.startsWith("!=",nextToken()) && match("!")) { Scalar value = single(unary()); return one(() -> value.get() == 0 ? 1 : 0); }
        if (match("-")) return map(unary(),value -> -value);
        if (match("+")) return unary();
        return primary();
    }
    private Scalar[] primary() {
        if (match("(")) {
            Scalar[] value = conditional();
            if (!match(")")) throw invalid("Unbalanced parenthesis");
            return value;
        }
        int start = nextToken();
        if (start >= text.length()) throw invalid("Incomplete expression");
        char first = text.charAt(start);
        if (Character.isDigit(first) || first == '.') return number();
        if (!Character.isLetter(first) && first != '_') throw invalid("Unexpected '"+first+"'");
        String name = word();
        if (match("(")) return call(name);
        return switch (name) {
            case "true" -> constant(1);
            case "false" -> constant(0);
            case "pi" -> constant(Math.PI);
            default -> access(name);
        };
    }
    /** {@code name.y} addresses one component; the name without a suffix is the whole value. */
    private Scalar[] access(String name) {
        Scalar[] value = scope.variable(name);
        if (position >= text.length() || text.charAt(position) != '.') return value;
        position++;
        String swizzle = word();
        if (swizzle.length() != 1) throw invalid("Invalid component: "+name+"."+swizzle);
        int component = "xyzw".indexOf(swizzle.charAt(0));
        if (component < 0) component = "rgba".indexOf(swizzle.charAt(0));
        if (component < 0 || component >= value.length) throw invalid("Invalid component: "+name+"."+swizzle);
        return one(value[component]);
    }
    private Scalar[] number() {
        int start = position;
        while (position < text.length() && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) position++;
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < text.length() && (text.charAt(position) == '-' || text.charAt(position) == '+')) position++;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
        }
        String literal = text.substring(start,position);
        if (position < text.length() && (text.charAt(position) == 'f' || text.charAt(position) == 'F')) position++;
        try { return constant(Double.parseDouble(literal)); }
        catch (NumberFormatException failure) { throw invalid("Invalid number: "+literal); }
    }

    private Scalar[] call(String name) {
        List<Scalar[]> arguments = new ArrayList<>();
        if (!match(")")) {
            do arguments.add(conditional()); while (match(","));
            if (!match(")")) throw invalid("Unbalanced call of "+name);
        }
        return PackExpressionFunctions.call(name,arguments,scope);
    }

    static Scalar[] one(Scalar value) { return new Scalar[]{value}; }
    static Scalar[] constant(double value) { return one(() -> value); }
    static Scalar single(Scalar[] value) {
        if (value.length != 1) throw invalid("Expected a scalar, got "+value.length+" components");
        return value[0];
    }
    static Scalar[] map(Scalar[] value, DoubleUnaryOperator operator) {
        Scalar[] result = new Scalar[value.length];
        for (int index = 0; index < value.length; index++) { Scalar source = value[index]; result[index] = () -> operator.applyAsDouble(source.get()); }
        return result;
    }
    /** Elementwise, with a scalar operand broadcast across the other's components. */
    static Scalar[] zip(Scalar[] left, Scalar[] right, DoubleBinaryOperator operator) {
        if (left.length != right.length && left.length != 1 && right.length != 1) throw invalid("Vector sizes differ: "+left.length+" and "+right.length);
        Scalar[] result = new Scalar[Math.max(left.length,right.length)];
        for (int index = 0; index < result.length; index++) {
            Scalar a = left[left.length == 1 ? 0 : index], b = right[right.length == 1 ? 0 : index];
            result[index] = () -> operator.applyAsDouble(a.get(),b.get());
        }
        return result;
    }
    static Scalar[] choose(Scalar condition, Scalar[] yes, Scalar[] no) {
        if (yes.length != no.length && yes.length != 1 && no.length != 1) throw invalid("Conditional branches differ in size");
        Scalar[] result = new Scalar[Math.max(yes.length,no.length)];
        for (int index = 0; index < result.length; index++) {
            Scalar a = yes[yes.length == 1 ? 0 : index], b = no[no.length == 1 ? 0 : index];
            result[index] = () -> condition.get() != 0 ? a.get() : b.get();
        }
        return result;
    }
    static Scalar random() { return () -> ThreadLocalRandom.current().nextDouble(); }
    private static Scalar[] compare(Scalar[] left, Scalar[] right, DoubleBinaryOperator operator) {
        Scalar a = single(left), b = single(right);
        return one(() -> operator.applyAsDouble(a.get(),b.get()));
    }

    private String word() {
        int start = nextToken();
        position = start;
        while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) position++;
        return text.substring(start,position);
    }
    private boolean match(String token) {
        int start = nextToken();
        if (!text.startsWith(token,start)) return false;
        position = start+token.length();
        return true;
    }
    private int nextToken() { skipSpace(); return position; }
    private void skipSpace() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
    static UnsupportedShaderException invalid(String message) { return new UnsupportedShaderException("Custom uniform expression: "+message); }
}
