package dev.overhaul.minecraft.blaze;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stages a mod compiles and attaches itself through raw OpenGL calls, as Lodestone does with geometry programs:
 * their source is kept so the native link of the shader they join can compile them.
 */
public final class BlazeAttachedStages {
    private static final int GEOMETRY = 36313;
    public record Stage(String name, String source) {}
    private static final Map<Integer, Integer> TYPES = new ConcurrentHashMap<>();
    private static final Map<Integer, String> SOURCES = new ConcurrentHashMap<>();
    private static final Map<Integer, Stage> GEOMETRY_BY_PROGRAM = new ConcurrentHashMap<>();

    private BlazeAttachedStages() {}

    public static void created(int shader, int type) {
        if (type == GEOMETRY) TYPES.put(shader, type);
    }

    public static void source(int shader, String source) {
        if (TYPES.containsKey(shader)) SOURCES.put(shader, source);
    }

    public static void attach(int program, int shader, String name) {
        String source = SOURCES.get(shader);
        if (source != null) GEOMETRY_BY_PROGRAM.put(program, new Stage(name, source));
    }

    public static void forget(int shader) {
        TYPES.remove(shader);
        SOURCES.remove(shader);
    }

    /** The geometry stage attached to {@code program} since its last link, or null. */
    static Stage takeGeometry(int program) {
        return GEOMETRY_BY_PROGRAM.remove(program);
    }
}
