package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code image.<name> = <sampler> <pixelFormat> <internalFormat> <pixelType> <clear> <relative> <width> <height> [<depth>]}:
 * storage images a pack writes with {@code imageStore} and reads back through a sampler. A depth makes the image 3D; a
 * relative image scales its width and height by the screen. {@code bufferObject.<index> = <bytes>} declares a zeroed
 * storage buffer that shaders reach as {@code layout(std430, binding = index) buffer}.
 */
public final class PackImages {
    private static final Pattern KEY = Pattern.compile("image\\.[A-Za-z_]\\w*");
    private static final Pattern BUFFER = Pattern.compile("bufferObject\\.(\\d{1,2})");
    /** Largest storage buffer a pack may ask for; the device's own limits are checked when it is allocated. */
    private static final long MAX_BUFFER = 1L << 32;
    /** Formats both GLSL image layouts and Vulkan storage images accept. */
    private static final Set<String> FORMATS = Set.of("rgba32f","rgba16f","rg32f","rg16f","r11f_g11f_b10f","r32f","r16f",
            "rgba16","rgb10_a2","rgba8","rg16","rg8","r16","r8","rgba16_snorm","rgba8_snorm","rg16_snorm","rg8_snorm","r16_snorm","r8_snorm",
            "rgba32i","rgba16i","rgba8i","rg32i","rg16i","rg8i","r32i","r16i","r8i",
            "rgba32ui","rgba16ui","rgb10_a2ui","rgba8ui","rg32ui","rg16ui","rg8ui","r32ui","r16ui","r8ui");

    /** One declared image; {@code format} is its GLSL layout qualifier. */
    public record Image(String name, String sampler, String format, boolean clear, boolean relative,
                        double width, double height, int depth) {
        public boolean volume() { return depth > 0; }
        public boolean integer() { return format.endsWith("ui") || format.endsWith("i"); }
        public boolean unsigned() { return format.endsWith("ui"); }
        /** Texel extent for a screen of the given size. */
        public int width(int screen) { return relative ? Math.max(1,(int)Math.round(width*screen)) : (int)width; }
        public int height(int screen) { return relative ? Math.max(1,(int)Math.round(height*screen)) : (int)height; }
    }

    private final Map<String,Image> images;
    private final Map<String,Image> samplers;
    private final Map<Integer,Long> buffers;

    private PackImages(Map<String,Image> images, Map<Integer,Long> buffers) {
        this.images = Map.copyOf(images);
        this.buffers = Map.copyOf(buffers);
        Map<String,Image> bySampler = new LinkedHashMap<>();
        for (Image image : images.values()) bySampler.put(image.sampler(),image);
        samplers = Map.copyOf(bySampler);
    }

    public static final PackImages NONE = new PackImages(Map.of(),Map.of());

    public static boolean property(String key) { return KEY.matcher(key).matches() || BUFFER.matcher(key).matches(); }

    public static PackImages parse(Map<String,String> properties) {
        Map<String,Image> images = new LinkedHashMap<>();
        Map<Integer,Long> buffers = new TreeMap<>();
        for (var entry : new TreeMap<>(properties).entrySet()) {
            var buffer = BUFFER.matcher(entry.getKey());
            if (buffer.matches()) { buffers.put(Integer.parseInt(buffer.group(1)),bufferSize(entry.getKey(),entry.getValue().trim())); continue; }
            if (!KEY.matcher(entry.getKey()).matches()) continue;
            String name = entry.getKey().substring("image.".length());
            images.put(name,image(name,entry.getValue().trim()));
        }
        return images.isEmpty() && buffers.isEmpty() ? NONE : new PackImages(images,buffers);
    }
    private static long bufferSize(String key, String value) {
        long size;
        try { size = Long.parseLong(value); } catch (NumberFormatException failure) { throw new UnsupportedShaderException("Shader pack "+key+": invalid size "+value); }
        if (size <= 0 || size > MAX_BUFFER) throw new UnsupportedShaderException("Shader pack "+key+": size out of range "+value);
        return size;
    }

    private static Image image(String name, String value) {
        String[] parts = value.split("\\s+");
        if (parts.length != 8 && parts.length != 9) throw invalid(name,"expects 8 or 9 fields: "+value);
        String format = parts[2].toLowerCase(java.util.Locale.ROOT);
        if (!FORMATS.contains(format)) throw invalid(name,"unsupported storage format "+parts[2]);
        boolean clear = bool(name,parts[4]), relative = bool(name,parts[5]);
        double width = number(name,parts[6]), height = number(name,parts[7]);
        int depth = parts.length == 9 ? (int)number(name,parts[8]) : 0;
        if (relative && depth > 0) throw invalid(name,"a relative image cannot be 3D");
        if (!relative && (width != Math.rint(width) || height != Math.rint(height))) throw invalid(name,"absolute sizes must be whole");
        if (width <= 0 || height <= 0 || parts.length == 9 && depth <= 0) throw invalid(name,"sizes must be positive");
        if (!relative && (width > 16384 || height > 16384) || depth > 2048) throw invalid(name,"too large");
        if (!parts[0].matches("[A-Za-z_]\\w*")) throw invalid(name,"invalid sampler "+parts[0]);
        return new Image(name,parts[0],format,clear,relative,width,height,depth);
    }
    private static boolean bool(String name, String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw invalid(name,"expected true or false: "+value);
    }
    private static double number(String name, String value) {
        try { return Double.parseDouble(value); } catch (NumberFormatException failure) { throw invalid(name,"invalid size "+value); }
    }
    private static UnsupportedShaderException invalid(String name, String reason) {
        return new UnsupportedShaderException("Shader pack image "+name+": "+reason);
    }

    public Map<String,Image> images() { return images; }
    public Image image(String name) { return images.get(name); }
    public Image bySampler(String sampler) { return samplers.get(sampler); }
    public boolean isEmpty() { return images.isEmpty() && buffers.isEmpty(); }
    /** Storage buffer index to its size in bytes. */
    public Map<Integer,Long> buffers() { return buffers; }
    /** Image name to GLSL layout format, for the translator. */
    public Map<String,String> formats() {
        Map<String,String> formats = new LinkedHashMap<>();
        for (Image image : images.values()) formats.put(image.name(),image.format());
        return Map.copyOf(formats);
    }
}
