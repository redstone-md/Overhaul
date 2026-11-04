package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.PackExpression.Scalar;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code uniform.<type>.<name>} and {@code variable.<type>.<name>} definitions of one pack generation.
 * Definitions run in dependency order once per frame, on the thread that later fills uniform blocks.
 * A definition may read its own previous value; a cycle through several definitions fails.
 */
public final class PackCustomUniforms {
    /** Supplies a built-in value; an unknown name throws, so nothing silently reads as zero. */
    public interface Source { double read(String name, int component); }

    private static final Pattern KEY = Pattern.compile("(uniform|variable)\\.(bool|int|float|vec[234])\\.([A-Za-z_]\\w*)");
    private static final Set<String> EXPRESSION_ONLY = Set.of("is_alive","is_burning","is_child","is_glowing","is_hurt","is_in_lava",
            "is_in_water","is_invisible","is_on_ground","is_ridden","is_riding","is_sneaking","is_sprinting","is_wet",
            "health","max_health","temperature","rainfall","biome","biome_category","biome_precipitation");
    private static final Map<String,Double> CONSTANTS = constants();
    private static final int MAX_DEFINITIONS = 2048;

    private static Map<String,Double> constants() {
        Map<String,Double> table = new java.util.HashMap<>(Map.of("PPT_NONE",0.0,"PPT_RAIN",1.0,"PPT_SNOW",2.0));
        for (PackBiomeCategory category : PackBiomeCategory.values()) table.put("CAT_"+category.name(),(double)category.ordinal());
        return Map.copyOf(table);
    }

    private static final class Definition {
        final String name, type, expression;
        final boolean uniform;
        final double[] values;
        final Set<Definition> dependencies = new HashSet<>();
        Scalar[] compiled;
        Definition(String name, String type, String expression, boolean uniform) {
            this.name = name; this.type = type; this.expression = expression; this.uniform = uniform;
            values = new double[components(type)];
        }
    }
    private record Builtin(String name, double[] values) {}

    private final Map<String,Definition> definitions;
    private final List<Definition> order;
    private final List<Builtin> builtins;
    private final Map<String,String> uniformTypes;
    private double frameSeconds;

    private PackCustomUniforms(Map<String,Definition> definitions, List<Definition> order, List<Builtin> builtins) {
        this.definitions = definitions; this.order = order; this.builtins = builtins;
        Map<String,String> types = new TreeMap<>();
        for (Definition definition : definitions.values()) if (definition.uniform) types.put(definition.name,definition.type);
        uniformTypes = Map.copyOf(types);
    }

    public static boolean property(String key) { return KEY.matcher(key).matches(); }

    public static PackCustomUniforms parse(Map<String,String> properties) {
        Map<String,Definition> definitions = new LinkedHashMap<>();
        for (var entry : new TreeMap<>(properties).entrySet()) {
            Matcher matcher = KEY.matcher(entry.getKey());
            if (!matcher.matches()) continue;
            if (definitions.size() >= MAX_DEFINITIONS) throw new UnsupportedShaderException("Shader pack exceeds "+MAX_DEFINITIONS+" custom uniforms");
            var definition = new Definition(matcher.group(3),matcher.group(2),entry.getValue(),matcher.group(1).equals("uniform"));
            if (definitions.putIfAbsent(definition.name,definition) != null) throw new UnsupportedShaderException("Duplicate custom uniform: "+definition.name);
        }
        var holder = new PackCustomUniforms[1];
        Map<String,Builtin> builtins = new LinkedHashMap<>();
        for (Definition definition : definitions.values()) compile(definition,definitions,builtins,holder);
        List<Definition> order = new ArrayList<>();
        Set<Definition> done = new HashSet<>(), active = new HashSet<>();
        for (Definition definition : definitions.values()) visit(definition,done,active,order);
        holder[0] = new PackCustomUniforms(definitions,List.copyOf(order),List.copyOf(builtins.values()));
        return holder[0];
    }
    private static void compile(Definition definition, Map<String,Definition> definitions, Map<String,Builtin> builtins, PackCustomUniforms[] holder) {
        var scope = new PackExpression.Scope() {
            @Override public Scalar[] variable(String name) {
                Definition other = definitions.get(name);
                if (other != null) { if (other != definition) definition.dependencies.add(other); return read(other.values); }
                Double constant = CONSTANTS.get(name);
                if (constant != null) return read(new double[]{constant});
                return read(builtins.computeIfAbsent(name,PackCustomUniforms::builtin).values());
            }
            @Override public double frameSeconds() { return holder[0].frameSeconds; }
        };
        try {
            Scalar[] compiled = PackExpression.parse(definition.expression,scope);
            if (compiled.length != definition.values.length) {
                if (compiled.length != 1) throw new UnsupportedShaderException("Custom uniform expression: "+compiled.length+" components for "+definition.type);
                Scalar only = compiled[0];
                compiled = new Scalar[definition.values.length];
                java.util.Arrays.fill(compiled,only);
            }
            definition.compiled = compiled;
        } catch (UnsupportedShaderException failure) {
            throw new UnsupportedShaderException("Custom uniform "+definition.name+": "+failure.getMessage());
        }
    }
    private static Builtin builtin(String name) {
        if (EXPRESSION_ONLY.contains(name) || name.startsWith("BIOME_")) return new Builtin(name,new double[1]);
        var known = PackInterfaceCatalog.uniform(name).orElseThrow(() -> PackExpression.invalid("Unknown value: "+name));
        if (known.type().startsWith("mat")) throw PackExpression.invalid("Matrix values are not readable: "+name);
        return new Builtin(name,new double[components(known.type())]);
    }
    private static Scalar[] read(double[] values) {
        Scalar[] result = new Scalar[values.length];
        for (int index = 0; index < values.length; index++) { int component = index; result[index] = () -> values[component]; }
        return result;
    }
    private static void visit(Definition definition, Set<Definition> done, Set<Definition> active, List<Definition> order) {
        if (done.contains(definition)) return;
        if (!active.add(definition)) throw new UnsupportedShaderException("Custom uniform dependency cycle through "+definition.name);
        for (Definition dependency : definition.dependencies) visit(dependency,done,active,order);
        active.remove(definition);
        done.add(definition);
        order.add(definition);
    }
    private static int components(String type) {
        char last = type.charAt(type.length()-1);
        return Character.isDigit(last) ? last-'0' : 1;
    }

    /** Names and declared types the pack's shaders may bind as uniforms. */
    public Map<String,String> uniformTypes() { return uniformTypes; }
    /** Built-in names the definitions read, so a source can be checked before its first frame. */
    public Set<String> builtinNames() {
        Set<String> names = new HashSet<>();
        for (Builtin builtin : builtins) names.add(builtin.name());
        return Set.copyOf(names);
    }

    public void update(double frameSeconds, Source source) {
        this.frameSeconds = frameSeconds;
        for (Builtin builtin : builtins) for (int index = 0; index < builtin.values().length; index++) builtin.values()[index] = source.read(builtin.name(),index);
        for (Definition definition : order) store(definition);
    }
    /** Components are written after all are read, so a self-reference sees last frame's vector. */
    private static void store(Definition definition) {
        double x = definition.compiled[0].get();
        double y = definition.compiled.length > 1 ? definition.compiled[1].get() : 0;
        double z = definition.compiled.length > 2 ? definition.compiled[2].get() : 0;
        double w = definition.compiled.length > 3 ? definition.compiled[3].get() : 0;
        definition.values[0] = x;
        if (definition.compiled.length > 1) definition.values[1] = y;
        if (definition.compiled.length > 2) definition.values[2] = z;
        if (definition.compiled.length > 3) definition.values[3] = w;
    }

    /** Coerces to the type the shader declared, since packs often declare it differently from the property. */
    public boolean fill(String name, UniformBlock block) {
        Definition definition = definitions.get(name);
        if (definition == null || !definition.uniform) return false;
        var uniform = block.bindings().uniforms().get(name);
        if (uniform == null) return false;
        double[] values = definition.values;
        switch (uniform.type()) {
            case "float" -> block.setFloat(name,(float)values[0]);
            case "int" -> block.setInt(name,(int)values[0]);
            case "bool" -> block.setBoolean(name,values[0] != 0);
            case "vec2" -> { require(name,values,2); block.setVector(name,(float)values[0],(float)values[1]); }
            case "vec3" -> { require(name,values,3); block.setVector(name,(float)values[0],(float)values[1],(float)values[2]); }
            case "vec4" -> { require(name,values,4); block.setVector(name,(float)values[0],(float)values[1],(float)values[2],(float)values[3]); }
            case "ivec2" -> { require(name,values,2); block.setIntVector(name,(int)values[0],(int)values[1]); }
            case "ivec3" -> { require(name,values,3); block.setIntVector(name,(int)values[0],(int)values[1],(int)values[2]); }
            case "ivec4" -> { require(name,values,4); block.setIntVector(name,(int)values[0],(int)values[1],(int)values[2],(int)values[3]); }
            default -> throw new UnsupportedShaderException("Custom uniform "+name+" is declared "+uniform.type());
        }
        return true;
    }
    private static void require(String name, double[] values, int size) {
        if (values.length != size) throw new UnsupportedShaderException("Custom uniform "+name+" has "+values.length+" components, shader declares "+size);
    }
}
