package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.blaze.BlazeHandAnimation;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Keeps the first-person hand's geometry between the frames that animate it. With
 * {@link BlazeOptimization#PLAYER_HAND_REPLAY}, a frame that animates the hand draws it with the view's pose into buffers
 * that record every vertex as they pass it on to the game's buffers; the frames that {@link BlazeHandAnimation} paces
 * draw those vertices again moved by their pose relative to the recorded one, so bobbing and turning stay smooth,
 * without running the renderers. A recording that sees a draw or a buffer past the recording buffers is not
 * replayed. Render thread only.
 */
public final class BlazeHandReplay {
    private static final Logger LOG = LoggerFactory.getLogger(BlazeHandReplay.class);
    private static BlazeHandReplay recording;
    private final Map<RenderType, BlazeVertexLog> logs = new LinkedHashMap<>();
    private final Matrix4f transform = new Matrix4f(), recordedInverse = new Matrix4f();
    private final Matrix3f normals = new Matrix3f(), recordedNormalInverse = new Matrix3f();
    private final Function<BlazeHandReplay, MultiBufferSource.BufferSource> recorders;
    private MultiBufferSource.BufferSource recorder, game;
    private int inside;
    private boolean ready, leaked, told;
    private Throwable where;

    /** {@code recorders} makes the loader's buffers that send what they are given to this replay. */
    BlazeHandReplay(Function<BlazeHandReplay, MultiBufferSource.BufferSource> recorders) {
        this.recorders = recorders;
    }

    /** Draws the hand that {@code renderer} draws with a pose and buffers, or its last geometry on a paced frame. */
    void draw(PoseStack pose, MultiBufferSource.BufferSource buffers, BiConsumer<PoseStack, MultiBufferSource.BufferSource> renderer) {
        if (!BlazeOptimization.PLAYER_HAND_REPLAY.on() || !BlazeOptimization.PLAYER_HAND_PACE.on()) {
            ready = false;
            renderer.accept(pose, buffers);
            return;
        }
        PoseStack.Pose view = pose.last();
        if (ready && game == buffers && BlazeHandAnimation.reusing()) {
            // The log holds the vertices as the recorded pose placed them; move them by this pose relative to that one.
            view.pose().mul(recordedInverse, transform);
            view.normal().mul(recordedNormalInverse, normals);
            for (Map.Entry<RenderType, BlazeVertexLog> entry : logs.entrySet())
                if (entry.getValue().vertices() > 0) BlazeVertexRecorder.replay(buffers.getBuffer(entry.getKey()), entry.getValue(), transform, normals);
            buffers.endBatch();
            BlazeHandAnimation.replayed();
            return;
        }
        for (BlazeVertexLog log : logs.values()) log.clear();
        if (recorder == null) recorder = recorders.apply(this);
        game = buffers;
        // Renderers that draw past the buffers they are given (TacZ guns) still need the real pose to land in view.
        boolean invertible = view.pose().invert(recordedInverse).isFinite() && view.normal().invert(recordedNormalInverse).isFinite();
        ready = leaked = false;
        inside = 0;
        recording = this;
        try {
            renderer.accept(pose, recorder);
            ready = !leaked && invertible;
        } finally {
            recording = null;
        }
        if (leaked && !told) {
            told = true;
            LOG.info("The hand is drawn every frame while a renderer draws it past the buffers it is given", where);
        }
    }

    /** The recording buffers' answer for {@code type}: the game's buffer, recorded. */
    VertexConsumer buffer(RenderType type) {
        inside++;
        try {
            return new BlazeVertexRecorder(game.getBuffer(type), logs.computeIfAbsent(type, key -> new BlazeVertexLog()));
        } finally {
            inside--;
        }
    }

    /** Runs {@code flush} on the game's buffers for the recording buffers, so the draws it makes are expected. */
    void flush(Consumer<MultiBufferSource.BufferSource> flush) {
        inside++;
        try {
            flush.accept(game);
        } finally {
            inside--;
        }
    }

    /** A buffer source was asked for a buffer; during a recording and past the recording buffers, it was bypassed. */
    public static void bufferRequested() {
        BlazeHandReplay replay = recording;
        if (replay != null && replay.inside == 0) replay.leak("a buffer from other buffers");
    }

    /** Something is drawn; during a recording and past the recording buffers, it bypassed them. */
    public static void drew() {
        BlazeHandReplay replay = recording;
        if (replay != null && replay.inside == 0) replay.leak("a direct draw");
    }

    private void leak(String how) {
        if (!leaked && where == null) where = new Throwable(how);
        leaked = true;
    }
}
