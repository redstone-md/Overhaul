package dev.overhaul.vulkan;

/** RGBA8 linear samples, legacy bottom row first. Sampling policy is explicit. */
public record PackTextureData(int width,int height,byte[] rgba,Filter filter,Wrap wrap) {
    public enum Filter { NEAREST, LINEAR }
    public enum Wrap { REPEAT, CLAMP }
    public PackTextureData {
        if (width<1 || height<1 || (long)width*height*4!=rgba.length || rgba.length>67_108_864 || filter==null || wrap==null) {
            throw new IllegalArgumentException("Invalid sampled texture");
        }
        rgba=rgba.clone();
    }
    @Override public byte[] rgba() { return rgba.clone(); }
}
