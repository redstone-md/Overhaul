package dev.overhaul.shader.compat;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage buffer blocks of a pack shader, {@code layout(std430, binding = N) buffer Name { ... } instance;}. The pack
 * numbers them by its {@code bufferObject.N} index; here the block keeps its place and members, and only its layout is
 * swapped for a marker that the rewrite turns into the program's own descriptor binding.
 */
final class PackStorageBuffers {
    private static final String MEMORY = "(?:writeonly|readonly|coherent|volatile|restrict)";
    private static final Pattern BLOCK = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*((?:\\b"+MEMORY+"\\s+)*)\\bbuffer\\s+(\\w+)\\s*\\{");
    private static final Pattern BINDING = Pattern.compile("binding\\s*=\\s*(\\d+)");
    private static final String MARKER = "OVERHAUL_STORAGE_BUFFER_";

    private PackStorageBuffers() {}

    /** The descriptor name of the buffer a pack numbers {@code index}. */
    static String name(int index) { return "overhaulBuffer"+index; }

    /** Replaces each block layout with its marker and records the pack index it names. */
    static String lift(String source, Set<Integer> indices) {
        Matcher matcher = BLOCK.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            int index = -1;
            for (String part : matcher.group(1).split(",")) {
                String token = part.trim();
                Matcher binding = BINDING.matcher(token);
                if (binding.matches()) index = Integer.parseInt(binding.group(1));
                else if (!token.equals("std430") && !token.equals("std140")) throw new UnsupportedShaderException("Unsupported storage buffer layout: "+matcher.group(1).trim());
            }
            if (index < 0) throw new UnsupportedShaderException("Storage buffer "+matcher.group(3)+" names no binding");
            indices.add(index);
            String qualifiers = matcher.group(2).trim().replaceAll("\\s+"," ");
            matcher.appendReplacement(result,Matcher.quoteReplacement(MARKER+index+" "+(qualifiers.isEmpty() ? "" : qualifiers+" ")+"buffer "+matcher.group(3)+" {"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Puts each block on its descriptor binding; {@code bindings} maps descriptor names to binding numbers. */
    static String place(String body, Set<Integer> indices, Map<String,Integer> bindings) {
        for (int index : indices) body = body.replace(MARKER+index+" ","layout(set=0,binding="+bindings.get(name(index))+",std430) ");
        if (body.contains(MARKER)) throw new UnsupportedShaderException("Storage buffer block left without a binding");
        return body;
    }
}
