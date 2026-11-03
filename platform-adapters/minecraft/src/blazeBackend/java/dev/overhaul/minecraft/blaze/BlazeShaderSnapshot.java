package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.shaders.Uniform;
import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.shader.compat.ProgramBindings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual CPU uniform values copied at draw time, independent of later animation writes. */
public record BlazeShaderSnapshot(BlazeProgramCompiler.Program program, ByteBuffer uniforms, Map<String, Object> samplers) {
    public BlazeShaderSnapshot {
        uniforms = uniforms.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        samplers = Collections.unmodifiableMap(new LinkedHashMap<>(samplers));
    }
    @Override public ByteBuffer uniforms() { return uniforms.duplicate().order(ByteOrder.nativeOrder()); }

    public static BlazeShaderSnapshot capture(BlazeProgramCompiler.Program program,
            Map<String, Uniform> source, Map<String, Object> samplers) {
        ByteBuffer values = ByteBuffer.allocateDirect(program.bindings().uniformBytes()).order(ByteOrder.nativeOrder());
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
