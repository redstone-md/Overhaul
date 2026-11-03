package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Parsed pack state remains explicit metadata for native pipeline creation. */
public final class ShaderPackProperties {
    private static final Set<String> FACTORS = Set.of("ZERO","ONE","SRC_COLOR","ONE_MINUS_SRC_COLOR",
            "DST_COLOR","ONE_MINUS_DST_COLOR","SRC_ALPHA","ONE_MINUS_SRC_ALPHA","DST_ALPHA",
            "ONE_MINUS_DST_ALPHA","SRC_ALPHA_SATURATE","CONSTANT_COLOR","ONE_MINUS_CONSTANT_COLOR",
            "CONSTANT_ALPHA","ONE_MINUS_CONSTANT_ALPHA");
    private static final Set<String> COMPARES = Set.of("NEVER","LESS","EQUAL","LEQUAL","GREATER","NOTEQUAL","GEQUAL","ALWAYS");
    private final Map<String,String> values;

    private ShaderPackProperties(Map<String,String> values) { this.values = Map.copyOf(values); }
    public static ShaderPackProperties parse(String source) throws IOException {
        Properties parsed = new Properties(); parsed.load(new StringReader(source));
        Map<String,String> values = new TreeMap<>();
        for (String key : parsed.stringPropertyNames()) values.put(key,parsed.getProperty(key).trim());
        return new ShaderPackProperties(values);
    }
    public Map<String,String> values() { return values; }

    public ProgramState program(String name, Map<String,Boolean> options) {
        boolean enabled = BooleanOptionExpression.evaluate(values.getOrDefault("program."+name+".enabled","true"),options);
        Map<String,Blend> blends = new LinkedHashMap<>();
        String blendPrefix = "blend."+name;
        for (var entry : new TreeMap<>(values).entrySet()) {
            if (entry.getKey().equals(blendPrefix) || entry.getKey().startsWith(blendPrefix+".")) {
                String buffer = entry.getKey().equals(blendPrefix) ? "*" : entry.getKey().substring(blendPrefix.length()+1);
                if (!buffer.equals("*") && !buffer.matches("colortex(?:[0-9]|1[0-5])")) throw unsupported("Unsupported blend buffer: "+buffer);
                blends.put(buffer,parseBlend(entry.getValue()));
            }
        }
        java.util.Optional<AlphaTest> alpha = java.util.Optional.ofNullable(values.get("alphaTest."+name)).map(ShaderPackProperties::parseAlpha);
        Map<String,Boolean> flips = new LinkedHashMap<>();
        String flipPrefix = "flip."+name+".";
        for (var entry : new TreeMap<>(values).entrySet()) if (entry.getKey().startsWith(flipPrefix)) {
            String target = entry.getKey().substring(flipPrefix.length());
            if (!target.matches("colortex(?:[0-9]|1[0-5])") || !Set.of("true","false").contains(entry.getValue())) throw unsupported("Invalid buffer flip: "+entry.getKey());
            flips.put(target,Boolean.valueOf(entry.getValue()));
        }
        return new ProgramState(enabled,blends,alpha,flips);
    }

    public List<String> unsupportedProperties() {
        List<String> result = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(values.keySet())) {
            boolean pipeline = key.matches("program\\.[A-Za-z0-9_/-]+\\.enabled")
                    || key.matches("(?:blend|alphaTest)\\.[A-Za-z0-9_/-]+(?:\\.colortex(?:[0-9]|1[0-5]))?")
                    || key.matches("flip\\.[A-Za-z0-9_/-]+\\.colortex(?:[0-9]|1[0-5])")
                    || key.matches("size\\.buffer\\.colortex(?:[0-9]|1[0-5])");
            if (!pipeline && !key.startsWith("screen") && !key.startsWith("profile.") && !key.equals("sliders")) result.add(key);
        }
        return List.copyOf(result);
    }

    private static Blend parseBlend(String value) {
        if (value.equals("off")) return new Blend(false,"ONE","ZERO","ONE","ZERO");
        String[] factors = value.split("\\s+");
        if (factors.length != 2 && factors.length != 4) throw unsupported("Blend requires two or four factors: "+value);
        for (String factor : factors) if (!FACTORS.contains(factor)) throw unsupported("Unknown blend factor: "+factor);
        return new Blend(true,factors[0],factors[1],factors.length==4 ? factors[2] : factors[0],factors.length==4 ? factors[3] : factors[1]);
    }
    private static AlphaTest parseAlpha(String value) {
        if (value.equals("off")) return new AlphaTest(false,"ALWAYS",0);
        String[] parts = value.split("\\s+");
        if (parts.length != 2 || !COMPARES.contains(parts[0])) throw unsupported("Invalid alpha test: "+value);
        float reference;
        try { reference = Float.parseFloat(parts[1]); } catch (NumberFormatException invalid) { throw unsupported("Invalid alpha reference: "+value); }
        if (!Float.isFinite(reference) || reference<0 || reference>1) throw unsupported("Alpha reference outside [0,1]");
        return new AlphaTest(true,parts[0],reference);
    }
    private static UnsupportedShaderException unsupported(String message) { return new UnsupportedShaderException(message); }
    public record Blend(boolean enabled, String sourceRgb, String destinationRgb, String sourceAlpha, String destinationAlpha) {}
    public record AlphaTest(boolean enabled, String comparison, float reference) {}
    public record ProgramState(boolean enabled, Map<String,Blend> blends, java.util.Optional<AlphaTest> alphaTest, Map<String,Boolean> flips) {
        public ProgramState { blends = Map.copyOf(blends); flips = Map.copyOf(flips); }
    }
}
