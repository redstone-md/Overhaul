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
    private static final java.util.regex.Pattern UNPROCESSED = java.util.regex.Pattern.compile("iris\\.features\\..*|sliders|screen(?:\\..*)?|profile\\..*");
    private static final String COLOR_BUFFER = "colortex(?:[0-9]|1[0-5])|gcolor|gdepth|gnormal|composite|gaux[1-4]";
    private static final String BUFFER = COLOR_BUFFER+"|shadowcolor[01]";
    private final Map<String,String> values;

    private ShaderPackProperties(Map<String,String> values) { this.values = Map.copyOf(values); }
    public static ShaderPackProperties parse(String source) throws IOException {
        return parse(source,Map.of());
    }
    /** Conditionals select the active keys; menu and feature keys are read from the raw text, as Iris does. */
    public static ShaderPackProperties parse(String source, Map<String,String> defines) throws IOException {
        Map<String,String> values = load(PackPropertiesPreprocessor.process(source,defines));
        values.keySet().removeIf(key -> UNPROCESSED.matcher(key).matches());
        for (var entry : load(source).entrySet()) if (UNPROCESSED.matcher(entry.getKey()).matches()) values.put(entry.getKey(),entry.getValue());
        return new ShaderPackProperties(values);
    }
    private static Map<String,String> load(String text) throws IOException {
        Properties parsed = new Properties(); parsed.load(new StringReader(text));
        Map<String,String> values = new TreeMap<>();
        for (String key : parsed.stringPropertyNames()) values.put(key,parsed.getProperty(key).trim());
        return values;
    }
    public Map<String,String> values() { return values; }

    /**
     * A dimension program also honors keys written for its bare name, since packs usually address
     * programs that way; a key naming the folder overrides the bare key.
     */
    public ProgramState program(String name, Map<String,Boolean> options) {
        List<String> scopes = scopes(name);
        String enabled = "true";
        java.util.Optional<AlphaTest> alpha = java.util.Optional.empty();
        Map<String,Blend> blends = new LinkedHashMap<>();
        Map<String,Boolean> flips = new LinkedHashMap<>();
        for (String scope : scopes) {
            enabled = values.getOrDefault("program."+scope+".enabled",enabled);
            if (values.containsKey("alphaTest."+scope)) alpha = java.util.Optional.of(parseAlpha(values.get("alphaTest."+scope)));
            collectBlends(scope,blends);
            collectFlips(scope,flips);
        }
        return new ProgramState(BooleanOptionExpression.evaluate(enabled,options),blends,alpha,flips);
    }
    private static List<String> scopes(String name) {
        int folder = name.lastIndexOf('/');
        return folder < 0 ? List.of(name) : List.of(name.substring(folder+1),name);
    }
    private void collectBlends(String scope, Map<String,Blend> blends) {
        String prefix = "blend."+scope;
        for (var entry : new TreeMap<>(values).entrySet()) {
            if (entry.getKey().equals(prefix)) blends.put("*",parseBlend(entry.getValue()));
            else if (entry.getKey().startsWith(prefix+".")) {
                String buffer = entry.getKey().substring(prefix.length()+1);
                if (!buffer.matches(BUFFER)) throw unsupported("Unsupported blend buffer: "+buffer);
                blends.put(canonicalBuffer(buffer),parseBlend(entry.getValue()));
            }
        }
    }
    private void collectFlips(String scope, Map<String,Boolean> flips) {
        String prefix = "flip."+scope+".";
        for (var entry : new TreeMap<>(values).entrySet()) if (entry.getKey().startsWith(prefix)) {
            String target = entry.getKey().substring(prefix.length());
            if (!target.matches(BUFFER) || !Set.of("true","1","false","0").contains(entry.getValue())) throw unsupported("Invalid buffer flip: "+entry.getKey());
            flips.put(canonicalBuffer(target),entry.getValue().equals("true") || entry.getValue().equals("1"));
        }
    }
    /** Legacy names such as gaux1 address the same target as their colortex name. */
    private static String canonicalBuffer(String buffer) {
        int color = PackRenderTargets.colorIndex(buffer);
        return color < 0 ? buffer : "colortex"+color;
    }

    public List<String> unsupportedProperties() {
        List<String> result = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(values.keySet())) {
            boolean pipeline = key.matches("program\\.[A-Za-z0-9_/-]+\\.enabled")
                    || key.matches("(?:blend|alphaTest)\\.[A-Za-z0-9_/-]+(?:\\.(?:"+BUFFER+"))?")
                    || key.matches("flip\\.[A-Za-z0-9_/-]+\\.(?:"+BUFFER+")")
                    || key.matches("size\\.buffer\\.(?:"+COLOR_BUFFER+")") || PackTextures.property(key)
                    || PackCustomUniforms.property(key);
            if (!pipeline && !PackPropertyPolicy.ignored(key) && !key.startsWith("screen") && !key.startsWith("profile.") && !key.equals("sliders")) result.add(key);
        }
        return List.copyOf(result);
    }
    /** Set by the pack, not honored here, and safe to run without; reported as degradation. */
    public List<String> ignoredProperties() {
        return values.keySet().stream().filter(PackPropertyPolicy::ignored).sorted().toList();
    }

    private static Blend parseBlend(String value) {
        if (value.equals("off")) return new Blend(false,"ONE","ZERO","ONE","ZERO");
        String[] factors = value.split("\\s+");
        if (factors.length != 2 && factors.length != 4) throw unsupported("Blend requires two or four factors: "+value);
        for (String factor : factors) if (!FACTORS.contains(factor)) throw unsupported("Unknown blend factor: "+factor);
        return new Blend(true,factors[0],factors[1],factors.length==4 ? factors[2] : factors[0],factors.length==4 ? factors[3] : factors[1]);
    }
    private static AlphaTest parseAlpha(String value) {
        if (value.equals("off") || value.equals("false")) return new AlphaTest(false,"ALWAYS",0);
        String[] parts = value.split("\\s+");
        if (parts.length == 2 && parts[0].startsWith("GL_")) parts[0] = parts[0].substring(3);
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
