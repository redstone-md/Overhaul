package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Uniform values a mod sets with raw {@code glUniform*} calls on the program in use. They are kept per program and
 * location as 32-bit words, matrices column by column, for draws whose own uniform objects never hold the value.
 */
final class BlazeGlUniforms {
    /** Enough for a uniform array of 1024 vec4 elements. */
    private static final int MAX_WORDS = 4096;

    private BlazeGlUniforms() {}

    static void install(Map<String, Answer> table) {
        for (int size = 1; size <= 4; size++) {
            int words = size;
            Answer scalars = arguments -> {
                int[] values = new int[words];
                for (int index = 0; index < words; index++) values[index] = arguments.integer(index + 1);
                return store(arguments.integer(0), values);
            };
            table.put("glUniform" + size + "i", scalars);
            table.put("glUniform" + size + "ui", scalars);
            table.put("glUniform" + size + "f", arguments -> {
                int[] values = new int[words];
                for (int index = 0; index < words; index++) values[index] = Float.floatToRawIntBits(arguments.real(index + 1));
                return store(arguments.integer(0), values);
            });
            Answer vectors = arguments -> store(arguments.integer(0), read(arguments.pointer(2), words * Math.max(arguments.integer(1), 1)));
            table.put("glUniform" + size + "iv", vectors);
            table.put("glUniform" + size + "uiv", vectors);
            table.put("glUniform" + size + "fv", vectors);
        }
        for (int size = 2; size <= 4; size++) {
            int dimension = size;
            table.put("glUniformMatrix" + size + "fv", arguments -> {
                int[] values = read(arguments.pointer(3), dimension * dimension);
                return store(arguments.integer(0), arguments.flag(2) ? transposed(values, dimension) : values);
            });
        }
    }

    private static int[] read(long address, int words) {
        int[] values = new int[Math.min(words, MAX_WORDS)];
        if (address == 0) return values;
        for (int index = 0; index < values.length; index++) values[index] = MemoryUtil.memGetInt(address + index * (long) Integer.BYTES);
        return values;
    }

    private static int[] transposed(int[] values, int dimension) {
        int[] columns = new int[values.length];
        for (int row = 0; row < dimension; row++) for (int column = 0; column < dimension; column++) {
            columns[column * dimension + row] = values[row * dimension + column];
        }
        return columns;
    }

    private static long store(int location, int[] values) {
        if (location < 0 || !RenderSystem.isOnRenderThread()) return 0;
        BlazePrograms programs = BlazePrograms.current();
        if (programs != null) programs.rawUniform(location, values);
        return 0;
    }
}
