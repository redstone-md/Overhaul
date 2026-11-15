package dev.overhaul.minecraft.blaze;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The outcome of a mod's repeated {@code Class.forName}, found or missing, kept after the first lookup. A missing class
 * under a transforming class loader is offered to every transformer again on each attempt, which some mods pay every
 * tick. {@link #known} answers null while {@link BlazeOptimization#MOD_CLASS_CACHE} is off, so the mod asks the loader.
 */
public final class BlazeClassCache {
    private static final Map<String, Object> OUTCOMES = new ConcurrentHashMap<>();

    private BlazeClassCache() {}

    /** The class, or the ClassNotFoundException the first lookup threw; null when nothing is kept. */
    public static Object known(String name) {
        return BlazeOptimization.MOD_CLASS_CACHE.on() ? OUTCOMES.get(name) : null;
    }

    public static void remember(String name, Object outcome) {
        if (BlazeOptimization.MOD_CLASS_CACHE.on()) OUTCOMES.put(name, outcome);
    }

    /** What the caller's own lookup would have answered: the class, or the same exception again. */
    public static Class<?> answer(Object outcome) throws ClassNotFoundException {
        if (outcome instanceof Class<?> type) return type;
        throw (ClassNotFoundException) outcome;
    }
}
