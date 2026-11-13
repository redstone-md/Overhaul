package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compat.ProgramBindings;
import java.nio.ByteBuffer;

/** Lays out the values a mod set with raw glUniform calls into a program's uniform bytes. */
final class BlazeRawUniforms {
    private BlazeRawUniforms() {}

    /** Every uniform of a program that a mod fills only through raw calls; a uniform never set reads zero. */
    static BlazeShaderSnapshot snapshot(BlazePrograms programs, int id) {
        var program = programs.compiled(id);
        ProgramBindings bindings = program.bindings();
        ByteBuffer values = BlazeShaderSnapshot.blank(bindings.uniformBytes());
        for (var entry : bindings.uniforms().entrySet()) write(values, entry.getValue(), programs.rawUniform(id, programs.uniformLocation(id, entry.getKey())));
        return new BlazeShaderSnapshot(program, values, programs.boundTextures(id));
    }

    /** Matrices arrive column by column and each std140 column takes sixteen bytes. */
    static void write(ByteBuffer target, ProgramBindings.Uniform layout, int[] words) {
        if (words == null) return;
        int offset = layout.byteOffset();
        String type = layout.type();
        if (layout.array()) {
            int components = layout.components();
            for (int index = 0; index < Math.min(words.length, layout.arrayLength() * components); index++)
                target.putInt(offset + index / components * ProgramBindings.Uniform.ARRAY_STRIDE + index % components * 4, words[index]);
            return;
        }
        if (type.startsWith("mat")) {
            int size = type.charAt(3) - '0';
            for (int index = 0; index < Math.min(words.length, size * size); index++) target.putInt(offset + (index / size) * 16 + (index % size) * 4, words[index]);
            return;
        }
        for (int index = 0; index < Math.min(words.length, layout.byteSize() / 4); index++) target.putInt(offset + index * 4, words[index]);
    }
}