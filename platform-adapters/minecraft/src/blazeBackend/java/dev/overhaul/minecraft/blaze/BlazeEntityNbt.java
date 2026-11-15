package dev.overhaul.minecraft.blaze;

import net.minecraft.nbt.CompoundTag;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Keeps each entity's saved NBT, and what Entity Model Features' {@code nbt()} animation rules make of it, for the rest
 * of the game tick. Entity Texture Features tests NBT-based texture and model rules by saving the whole entity, and it
 * keeps the result only until the next entity is drawn, so a player in a large mod pack is saved again for the hand,
 * the shadow pass and every other draw of every frame; each {@code nbt()} rule then turns the tags it reads back into
 * text. With {@link BlazeOptimization#ENTITY_NBT_TICK} a save is reused until the level's game time moves on, and with
 * {@link BlazeOptimization#ENTITY_NBT_RULES} so is each rule's answer for the entity; the data they read changes with
 * ticks and packets, so they are at most one tick old.
 */
public final class BlazeEntityNbt {
    private static final BlazeTickMemo<UUID, CompoundTag> saved = new BlazeTickMemo<>(512);
    private static final BlazeTickMemo<Rule, Boolean> rules = new BlazeTickMemo<>(4096);
    private static MethodHandle animatedEntity;
    private static boolean unresolved;

    private record Rule(Object entity, String path, String test) {
    }

    private BlazeEntityNbt() {
    }

    /** The entity's NBT for this tick, saved by {@code save} the first time; null when the cache is off. */
    public static CompoundTag get(UUID id, Supplier<CompoundTag> save) {
        if (id == null || !BlazeOptimization.ENTITY_NBT_TICK.on()) return null;
        return saved.get(id, key -> save.get());
    }

    /** The entity Entity Model Features is animating, which keys its rule answers; null when the cache is off. */
    public static Object ruleEntity() {
        if (!BlazeOptimization.ENTITY_NBT_TICK.on() || !BlazeOptimization.ENTITY_NBT_RULES.on()) return null;
        MethodHandle handle = animatedEntity();
        if (handle == null) return null;
        try {
            return handle.invoke();
        } catch (Throwable failure) {
            unresolved = true;
            animatedEntity = null;
            return null;
        }
    }

    /** This tick's answer of rule {@code path}/{@code test} for {@code entity}, or null before it is known. */
    public static Boolean rule(Object entity, String path, String test) {
        return rules.get(new Rule(entity, path, test), key -> null);
    }

    /** Records the rule's answer for the rest of the tick. */
    public static void remember(Object entity, String path, String test, boolean answer) {
        rules.get(new Rule(entity, path, test), key -> answer);
    }

    private static MethodHandle animatedEntity() {
        if (animatedEntity != null || unresolved) return animatedEntity;
        try {
            Class<?> context = Class.forName("traben.entity_model_features.models.animation.EMFAnimationEntityContext",
                    false, BlazeEntityNbt.class.getClassLoader());
            Class<?> entity = Class.forName("traben.entity_model_features.utils.EMFEntity", false, context.getClassLoader());
            animatedEntity = MethodHandles.publicLookup().findStatic(context, "getEMFEntity", MethodType.methodType(entity))
                    .asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException | LinkageError failure) {
            unresolved = true;
        }
        return animatedEntity;
    }
}
