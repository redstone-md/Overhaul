package dev.overhaul.minecraft.blaze;

/**
 * Paces the local player's animation in a shader pack's shadow pass. In first person the game never draws the player,
 * so only the shadow pass animates its model, and a mod such as Entity Model Features may spend a third of the frame on
 * it. With {@link BlazeOptimization#PLAYER_SHADOW_PACE} the pose is worked out again at most every 16 ms, or 33 ms with
 * {@link BlazeOptimization#PLAYER_SHADOW_SLOW_PACE}; frames in
 * between reuse the mod's last result for that player, as it does under Iris for entities the main pass animates.
 * Turning and moving stay smooth, since the renderer applies them, not the animation.
 */
public final class BlazeShadowAnimation {
    private static final long PERIOD_NANOS = 16_000_000L, SLOW_PERIOD_NANOS = 33_000_000L;
    private static long animatedAt;
    private static boolean reusing;

    private BlazeShadowAnimation() {
    }

    /** Called before the shadow pass draws the local player; true when this frame should reuse the last pose. */
    public static boolean beginLocalPlayer() {
        if (!BlazeOptimization.PLAYER_SHADOW_PACE.on()) return false;
        long now = System.nanoTime();
        reusing = now - animatedAt < (BlazeOptimization.PLAYER_SHADOW_SLOW_PACE.on() ? SLOW_PERIOD_NANOS : PERIOD_NANOS);
        if (!reusing) animatedAt = now;
        return reusing;
    }

    /** Called once the local player's shadow is drawn. */
    public static void end() {
        reusing = false;
    }

    /** Whether the entity being drawn should keep its last animation. Render thread only. */
    public static boolean reusing() {
        return reusing;
    }
}
