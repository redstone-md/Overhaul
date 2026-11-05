package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.shaders.Uniform;
import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.shader.compat.ProgramBindings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/** Actual CPU uniform values copied at draw time. The bytes live in shared render-thread storage and last until the next capture. */
public record BlazeShaderSnapshot(BlazeProgramCompiler.Program program, ByteBuffer uniforms, Map<String, Object> samplers) {
    public BlazeShaderSnapshot {
        samplers = Collections.unmodifiableMap(samplers);
    }
    @Override public ByteBuffer uniforms() { return uniforms.duplicate().order(ByteOrder.nativeOrder()); }
    /** The same values for a program with the same uniform layout. */
    BlazeShaderSnapshot withProgram(BlazeProgramCompiler.Program replacement) { return new BlazeShaderSnapshot(replacement, uniforms, samplers); }

    private static ByteBuffer scratch = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());

    public static BlazeShaderSnapshot capture(BlazeProgramCompiler.Program program,
            Map<String, Uniform> source, Map<String, Object> samplers) {
        int bytes = program.bindings().uniformBytes();
        if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(Math.max(bytes, scratch.capacity() * 2)).order(ByteOrder.nativeOrder());
        ByteBuffer values = scratch.clear().limit(bytes).slice().order(ByteOrder.nativeOrder());
        MemoryUtil.memSet(values, 0);
        for (var entry : program.bindings().uniforms().entrySet()) {
            Uniform uniform = source.get(entry.getKey());
            if (uniform != null) copy(values, entry.getValue(), uniform);
        }
        return new BlazeShaderSnapshot(program, values, samplers);
    }

    private static void copy(ByteBuffer target, ProgramBindings.Uniform layout, Uniform value) {
        String type = layout.type();
        boolean integer = type.equals("int") || type.equals("bool") || type.startsWith("ivec");
        int dimension = type.startsWith("mat") ? Integer.parseInt(type.substring(3)) : 0;
        int count = dimension == 0 ? Math.max(1, layout.byteSize() / 4) : dimension * dimension;
        if (value.getCount() != count) throw new UnsupportedOperationException("Uniform count differs: " + value.getName());
        if (integer) {
            var source = value.getIntBuffer();
            if (source == null) throw new IllegalArgumentException("Expected integer uniform: " + value.getName());
            for (int index = 0; index < count; index++) target.putInt(layout.byteOffset() + index * 4, source.get(index));
        } else {
            var source = value.getFloatBuffer();
            if (source == null) throw new IllegalArgumentException("Expected float uniform: " + value.getName());
            for (int index = 0; index < count; index++) {
                int offset = dimension == 0 ? index * 4 : (index / dimension) * 16 + (index % dimension) * 4;
                target.putFloat(layout.byteOffset() + offset, source.get(index));
            }
        }
    }
}
