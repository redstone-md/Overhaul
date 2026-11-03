package dev.overhaul.shader.compile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Java-owned immutable compiled bytes, independent of the native compiler lifetime. */
public final class SpirvBinary {
    private final byte[] bytes;
    private final ShaderStage stage;

    SpirvBinary(ByteBuffer source, ShaderStage stage) {
        bytes = new byte[source.remaining()];
        source.duplicate().get(bytes);
        this.stage = stage;
    }

    public ShaderStage stage() { return stage; }
    public int byteSize() { return bytes.length; }
    public byte[] bytes() { return bytes.clone(); }

    /** Returns a fresh direct copy suitable for vkCreateShaderModule. */
    public ByteBuffer directBuffer() {
        ByteBuffer result = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        result.put(bytes).flip();
        return result;
    }
}
