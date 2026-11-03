package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic discovery; dimension folders replace, rather than merge with, root programs. */
public record ShaderPackCatalog(List<Program> programs, ShaderPackProperties properties, List<String> diagnostics) {
    public ShaderPackCatalog { programs = List.copyOf(programs); diagnostics = List.copyOf(diagnostics); }

    public static ShaderPackCatalog discover(ShaderPackSource pack, Integer dimension, Map<String,Boolean> options) throws IOException {
        List<String> paths = pack.paths();
        String prefix = dimension != null && paths.contains("world"+dimension+"/") ? "world"+dimension+"/" : "";
        ShaderPackProperties properties = ShaderPackProperties.parse(paths.contains("shaders.properties") ? pack.read("shaders.properties") : "");
        List<String> diagnostics = new ArrayList<>();
        for (String key : properties.unsupportedProperties()) diagnostics.add("Unsupported pack property: "+key);
        Set<String> names = new TreeSet<>();
        for (String path : paths) {
            if (!path.startsWith(prefix)) continue;
            String relative = path.substring(prefix.length());
            if (relative.contains("/")) continue;
            if (relative.endsWith(".vsh") || relative.endsWith(".fsh")) names.add(path.substring(0,path.length()-4));
            else if (relative.matches(".*\\.(gsh|csh|tcs|tes)$")) diagnostics.add("Unsupported shader stage: "+path);
        }
        List<Program> programs = new ArrayList<>();
        for (String name : names) {
            if (!paths.contains(name+".vsh") || !paths.contains(name+".fsh")) {
                diagnostics.add("Incomplete vertex/fragment program: "+name); continue;
            }
            try { programs.add(new Program(name,name+".vsh",name+".fsh",properties.program(name,options))); }
            catch (UnsupportedShaderException failure) { diagnostics.add(name+": "+failure.getMessage()); }
        }
        return new ShaderPackCatalog(programs,properties,diagnostics);
    }

    public record Program(String name, String vertexPath, String fragmentPath, ShaderPackProperties.ProgramState state) {}
}
