package dev.overhaul.minecraft.blaze;

import java.util.HashMap;
import java.util.Map;

/**
 * Paces the local player's first-person hand animation. A mod such as Entity Model Features works the whole player
 * model's animation out again for the hand every frame, which in a large animation pack is a third of the frame. With
 * {@link BlazeOptimization#PLAYER_HAND_PACE} it is worked out at most every 8 ms, and the frames in between reuse the
 * mod's last result, which only stands in for the hand's pose when the hand made it, not the shadow pass or a screen.
 * Animation packs advance their timers by the frame time, so the time of frames no animation saw is handed to the next
 * one that runs, and timed moves keep their speed. Turning and moving stay smooth, since the renderer applies them.
 * Render thread only.
 */
public final class BlazeHandAnimation {
    private static final long PERIOD_NANOS = 8_333_333L;
    private static final Map<String, Boolean> madeByHand = new HashMap<>();
    private static long animatedAt;
    private static boolean inHand, reuse;
    private static float owed, served, owedFrame = Float.NaN, servedFrame = Float.NaN;

    private BlazeHandAnimation() {
    }

    /** Called before the hand is drawn. */
    public static void begin() {
        inHand = true;
        reuse = false;
        if (!BlazeOptimization.PLAYER_HAND_PACE.on()) return;
        long now = System.nanoTime();
        reuse = now - animatedAt < PERIOD_NANOS;
        if (!reuse) animatedAt = now;
    }

    /** Called once the hand is drawn. */
    public static void end() {
        inHand = false;
        reuse = false;
    }

    /**
     * Asked before each of the mod's animations of {@code model} on frame {@code frame} of its counter; true when the
     * local player's hand should keep its last pose, in which case the caller owes the frame's time with {@link #owe}.
     */
    public static boolean keep(String model, float frame) {
        if (!BlazeOptimization.PLAYER_HAND_PACE.on()) {
            madeByHand.clear();
            return false;
        }
        if (!BlazeEmfEntity.localPlayer()) return false;
        if (inHand && reuse && madeByHand.get(model) == Boolean.TRUE) return true;
        madeByHand.put(model, inHand);
        if (frame != servedFrame) {
            servedFrame = frame;
            served = owed;
            owed = 0;
        }
        return false;
    }

    /** Records that frame {@code frame}, {@code frameTime} seconds long, went by without the local player animating. */
    public static void owe(float frame, float frameTime) {
        if (frame == servedFrame || frame == owedFrame) return;
        owedFrame = frame;
        owed += frameTime;
    }

    /** The mod's frame time on frame {@code frame}, plus the time owed to the local player's animation on it. */
    public static float frameTime(float frameTime, float frame) {
        if (served == 0 || frameTime == 0 || frame != servedFrame || !BlazeEmfEntity.localPlayer()) return frameTime;
        return frameTime + served;
    }
}
