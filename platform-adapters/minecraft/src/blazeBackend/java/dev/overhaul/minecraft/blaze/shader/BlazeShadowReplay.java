package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the local player's shadow-pass geometry between the frames that animate it. With
 * {@link BlazeOptimization#PLAYER_SHADOW_REPLAY}, a frame that animates the player records every vertex its renderers
 * send through the given buffers, and the frames that {@link dev.overhaul.minecraft.blaze.BlazeShadowAnimation} paces
 * draw those vertices again, moved by how far the player moved from the camera, without running the renderers. A
 * renderer that draws past the given buffers, straight to the game's buffers or to the screen, would vanish from a
 * replay, so a recording that sees one is thrown away and replays stay off for the session.
 */
public final class BlazeShadowReplay {
    private static final Logger LOG = LoggerFactory.getLogger(BlazeShadowReplay.class);
    private static boolean recording, leaked;
    private static int handing;
    private final Map<RenderType, BlazeVertexLog> logs = new LinkedHashMap<>();
    private final MultiBufferSource.BufferSource game;
    private final MultiBufferSource recorder = this::recordingBuffer;
    private double x, y, z;
    private boolean ready, broken;

    BlazeShadowReplay(MultiBufferSource.BufferSource game) {
        this.game = game;
    }

    /** Whether frames may replay and so recordings are worth making. */
    boolean on() {
        return !broken && BlazeOptimization.PLAYER_SHADOW_REPLAY.on();
    }

    /** Draws the last recording moved to the player's camera-relative position; false when there is none to draw. */
    boolean replay(double ex, double ey, double ez) {
        if (!ready || !on()) return false;
        float dx = (float) (ex - x), dy = (float) (ey - y), dz = (float) (ez - z);
        for (Map.Entry<RenderType, BlazeVertexLog> entry : logs.entrySet())
            if (entry.getValue().vertices() > 0) BlazeVertexRecorder.replay(game.getBuffer(entry.getKey()), entry.getValue(), dx, dy, dz);
        return true;
    }

    /** Starts a recording of the player drawn at the camera-relative position; answers the buffers to draw it to. */
    MultiBufferSource record(double ex, double ey, double ez) {
        for (BlazeVertexLog log : logs.values()) log.clear();
        x = ex;
        y = ey;
        z = ez;
        ready = false;
        recording = true;
        leaked = false;
        return recorder;
    }

    /** Ends the recording; {@code drawn} is false when the renderers failed part way. */
    void recorded(boolean drawn) {
        recording = false;
        if (drawn && !leaked) {
            ready = true;
            return;
        }
        broken = true;
        LOG.info("The local player's shadow is drawn every frame: {}", drawn ? "a renderer draws past the buffers it is given" : "a renderer failed");
    }

    private VertexConsumer recordingBuffer(RenderType type) {
        handing++;
        try {
            return new BlazeVertexRecorder(game.getBuffer(type), logs.computeIfAbsent(type, key -> new BlazeVertexLog()));
        } finally {
            handing--;
        }
    }

    /** The game's buffers were asked for a buffer; outside a recorder's request during a recording, that is a leak. */
    public static void bufferRequested() {
        if (recording && handing == 0) leaked = true;
    }

    /** A draw reached the shadow map; outside a recorder's request during a recording, it bypassed the buffers. */
    public static void drew() {
        if (recording && handing == 0) leaked = true;
    }
}
