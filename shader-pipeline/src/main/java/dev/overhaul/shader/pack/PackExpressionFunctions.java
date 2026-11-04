package dev.overhaul.shader.pack;

import static dev.overhaul.shader.pack.PackExpression.choose;
import static dev.overhaul.shader.pack.PackExpression.constant;
import static dev.overhaul.shader.pack.PackExpression.invalid;
import static dev.overhaul.shader.pack.PackExpression.map;
import static dev.overhaul.shader.pack.PackExpression.one;
import static dev.overhaul.shader.pack.PackExpression.single;
import static dev.overhaul.shader.pack.PackExpression.zip;

import dev.overhaul.shader.pack.PackExpression.Scalar;
import dev.overhaul.shader.pack.PackExpression.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/** The function table of custom-uniform expressions, following OptiFine's names and argument forms. */
final class PackExpressionFunctions {
    private PackExpressionFunctions() {}

    static Scalar[] call(String name, List<Scalar[]> arguments, Scope scope) {
        return switch (name) {
            case "sin" -> unary(name,arguments,Math::sin);
            case "cos" -> unary(name,arguments,Math::cos);
            case "tan" -> unary(name,arguments,Math::tan);
            case "asin" -> unary(name,arguments,Math::asin);
            case "acos" -> unary(name,arguments,Math::acos);
            case "atan" -> arguments.size() == 2 ? binary(name,arguments,Math::atan2) : unary(name,arguments,Math::atan);
            case "atan2" -> binary(name,arguments,Math::atan2);
            case "torad" -> unary(name,arguments,Math::toRadians);
            case "todeg" -> unary(name,arguments,Math::toDegrees);
            case "abs" -> unary(name,arguments,Math::abs);
            case "floor" -> unary(name,arguments,Math::floor);
            case "ceil" -> unary(name,arguments,Math::ceil);
            case "round" -> unary(name,arguments,value -> (double)Math.round(value));
            case "frac" -> unary(name,arguments,value -> value-Math.floor(value));
            case "exp" -> unary(name,arguments,Math::exp);
            case "log" -> unary(name,arguments,Math::log);
            case "sqrt" -> unary(name,arguments,Math::sqrt);
            case "signum" -> unary(name,arguments,Math::signum);
            case "not" -> unary(name,arguments,value -> value == 0 ? 1 : 0);
            case "pow" -> binary(name,arguments,Math::pow);
            case "fmod" -> binary(name,arguments,(a,b) -> b == 0 ? 0 : a-b*Math.floor(a/b));
            case "min" -> fold(name,arguments,Math::min);
            case "max" -> fold(name,arguments,Math::max);
            case "and" -> fold(name,arguments,(a,b) -> a != 0 && b != 0 ? 1 : 0);
            case "or" -> fold(name,arguments,(a,b) -> a != 0 || b != 0 ? 1 : 0);
            case "clamp" -> clamp(arguments);
            case "if" -> conditional(arguments);
            case "in" -> membership(arguments);
            case "between" -> between(arguments);
            case "equals" -> approximatelyEqual(arguments);
            case "random" -> { require(name,arguments,0); yield one(PackExpression.random()); }
            case "smooth" -> smooth(arguments,scope);
            case "vec2" -> vector(name,arguments,2);
            case "vec3" -> vector(name,arguments,3);
            case "vec4" -> vector(name,arguments,4);
            case "length" -> { require(name,arguments,1); yield length(arguments.get(0)); }
            case "dot" -> { require(name,arguments,2); yield dot(arguments.get(0),arguments.get(1)); }
            case "distance" -> { require(name,arguments,2); yield length(zip(arguments.get(0),arguments.get(1),(a,b) -> a-b)); }
            default -> throw invalid("Unknown function: "+name);
        };
    }

    private static void require(String name, List<Scalar[]> arguments, int count) {
        if (arguments.size() != count) throw invalid(name+" takes "+count+" arguments, got "+arguments.size());
    }
    private static Scalar[] unary(String name, List<Scalar[]> arguments, DoubleUnaryOperator operator) {
        require(name,arguments,1);
        return map(arguments.get(0),operator);
    }
    private static Scalar[] binary(String name, List<Scalar[]> arguments, DoubleBinaryOperator operator) {
        require(name,arguments,2);
        return zip(arguments.get(0),arguments.get(1),operator);
    }
    private static Scalar[] fold(String name, List<Scalar[]> arguments, DoubleBinaryOperator operator) {
        if (arguments.isEmpty()) throw invalid(name+" requires arguments");
        Scalar[] result = arguments.get(0);
        for (int index = 1; index < arguments.size(); index++) result = zip(result,arguments.get(index),operator);
        return result;
    }
    private static Scalar[] clamp(List<Scalar[]> arguments) {
        require("clamp",arguments,3);
        return zip(zip(arguments.get(0),arguments.get(1),Math::max),arguments.get(2),Math::min);
    }
    /** {@code if(c1,v1,[c2,v2,...],else)}; the final else is optional and defaults to 0. */
    private static Scalar[] conditional(List<Scalar[]> arguments) {
        if (arguments.size() < 2) throw invalid("if requires a condition and a value");
        boolean hasElse = arguments.size()%2 == 1;
        Scalar[] result = hasElse ? arguments.get(arguments.size()-1) : constant(0);
        for (int index = arguments.size()-(hasElse ? 3 : 2); index >= 0; index -= 2) {
            result = choose(single(arguments.get(index)),arguments.get(index+1),result);
        }
        return result;
    }
    private static Scalar[] membership(List<Scalar[]> arguments) {
        if (arguments.size() < 2) throw invalid("in requires a value and candidates");
        Scalar value = single(arguments.get(0));
        Scalar[] candidates = new Scalar[arguments.size()-1];
        for (int index = 1; index < arguments.size(); index++) candidates[index-1] = single(arguments.get(index));
        return one(() -> {
            double current = value.get();
            for (Scalar candidate : candidates) if (candidate.get() == current) return 1;
            return 0;
        });
    }
    private static Scalar[] between(List<Scalar[]> arguments) {
        require("between",arguments,3);
        Scalar value = single(arguments.get(0)), low = single(arguments.get(1)), high = single(arguments.get(2));
        return one(() -> { double current = value.get(); return current >= low.get() && current <= high.get() ? 1 : 0; });
    }
    private static Scalar[] approximatelyEqual(List<Scalar[]> arguments) {
        require("equals",arguments,3);
        Scalar a = single(arguments.get(0)), b = single(arguments.get(1)), epsilon = single(arguments.get(2));
        return one(() -> Math.abs(a.get()-b.get()) <= epsilon.get() ? 1 : 0);
    }
    /**
     * {@code smooth([id,] value, [fadeUp, [fadeDown]])}; the fades default to one second. An id is
     * accepted only in its literal four-argument form, because a closure already owns its own state.
     */
    private static Scalar[] smooth(List<Scalar[]> arguments, Scope scope) {
        if (arguments.isEmpty() || arguments.size() > 4) throw invalid("smooth takes one to four arguments");
        int first = arguments.size() == 4 ? 1 : 0;
        Scalar value = single(arguments.get(first));
        Scalar up = arguments.size() > first+1 ? single(arguments.get(first+1)) : () -> 1;
        Scalar down = arguments.size() > first+2 ? single(arguments.get(first+2)) : up;
        return one(new Scalar() {
            private boolean started;
            private double current;
            @Override public double get() {
                double target = value.get();
                if (!started) { started = true; current = target; return current; }
                double fade = target > current ? up.get() : down.get();
                double keep = fade <= 0 ? 0 : Math.pow(0.01,scope.frameSeconds()/fade);
                current = current*keep+target*(1-keep);
                return current;
            }
        });
    }
    private static Scalar[] vector(String name, List<Scalar[]> arguments, int size) {
        List<Scalar> components = new ArrayList<>();
        for (Scalar[] argument : arguments) components.addAll(List.of(argument));
        if (components.size() == 1) { Scalar only = components.get(0); components = new ArrayList<>(); for (int index = 0; index < size; index++) components.add(only); }
        if (components.size() != size) throw invalid(name+" received "+components.size()+" components");
        return components.toArray(Scalar[]::new);
    }
    private static Scalar[] dot(Scalar[] a, Scalar[] b) {
        Scalar[] products = zip(a,b,(x,y) -> x*y);
        return one(() -> { double sum = 0; for (Scalar product : products) sum += product.get(); return sum; });
    }
    private static Scalar[] length(Scalar[] value) {
        return one(() -> { double sum = 0; for (Scalar component : value) { double current = component.get(); sum += current*current; } return Math.sqrt(sum); });
    }
}
