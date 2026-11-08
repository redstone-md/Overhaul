package dev.overhaul.shader.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage image declarations of a pack shader. GLSL lets memory qualifiers and a format layout sit around
 * {@code uniform}; they are lifted out here, so the declaration scanner sees {@code uniform image3D name;} and the
 * rewrite puts them back on the Vulkan binding.
 */
final class PackStorageImages {
    static final Set<String> IMAGE_TYPES = Set.of("image2D","image3D","uimage2D","uimage3D","iimage2D","iimage3D");
    private static final String MEMORY = "(?:writeonly|readonly|coherent|volatile|restrict)";
    private static final Pattern DECLARATION = Pattern.compile("(?:\\blayout\\s*\\(\\s*(\\w+)\\s*\\)\\s*)?((?:\\b"+MEMORY+"\\s+)*)\\buniform\\s+"
            +"((?:"+MEMORY+"\\s+)*)(?:layout\\s*\\(\\s*(\\w+)\\s*\\)\\s*)?([iu]?image[23]D)\\s+(\\w+(?:\\s*,\\s*\\w+)*)\\s*;");

    /** What a declaration said beyond its type: the format, when the shader states one, and its memory qualifiers. */
    record Storage(String format, String qualifiers) {
        Storage merge(Storage other) {
            String merged = format == null ? other.format : format;
            if (format != null && other.format != null && !format.equals(other.format)) {
                throw new UnsupportedShaderException("Conflicting storage image formats: "+format+" and "+other.format);
            }
            return new Storage(merged,qualifiers.equals(other.qualifiers) ? qualifiers : "");
        }
    }

    private PackStorageImages() {}

    /** Rewrites qualified image declarations to plain ones and records what was lifted, merging across stages. */
    static String lift(String source, Map<String,Storage> storage) {
        Matcher matcher = DECLARATION.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String format = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
            String qualifiers = (matcher.group(2)+" "+matcher.group(3)).trim().replaceAll("\\s+"," ");
            Storage lifted = new Storage(format == null ? null : format.toLowerCase(java.util.Locale.ROOT),qualifiers);
            for (String name : matcher.group(6).split(",")) storage.merge(name.trim(),lifted,Storage::merge);
            matcher.appendReplacement(result,Matcher.quoteReplacement("uniform "+matcher.group(5)+" "+matcher.group(6)+";"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** {@code layout(set=0,binding=N,fmt) qualifiers uniform type overhaulSampler_name;}. */
    static String declaration(String name, String type, int binding, Storage storage, Map<String,String> formats) {
        String format = storage != null && storage.format() != null ? storage.format() : formats.get(name);
        if (format == null) throw new UnsupportedShaderException("Storage image "+name+" has no format: declare image."+name+" or layout(format)");
        String qualifiers = storage == null || storage.qualifiers().isEmpty() ? "" : storage.qualifiers()+" ";
        return "layout(set=0,binding="+binding+","+format+") "+qualifiers+"uniform "+type+" "+PackProgramTranslator.samplerName(name)+";\n";
    }

    static Map<String,Storage> empty() { return new LinkedHashMap<>(); }
}
