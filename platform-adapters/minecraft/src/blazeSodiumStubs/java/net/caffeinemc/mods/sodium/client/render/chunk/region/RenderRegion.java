package net.caffeinemc.mods.sodium.client.render.chunk.region;
public class RenderRegion {
    public int getOriginX() { throw new AssertionError(); }
    public int getOriginY() { throw new AssertionError(); }
    public int getOriginZ() { throw new AssertionError(); }
    public DeviceResources getResources() { throw new AssertionError(); }
    public static class DeviceResources {
        public net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer getGeometryBuffer() { throw new AssertionError(); }
        public net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer getIndexBuffer() { throw new AssertionError(); }
    }
}
