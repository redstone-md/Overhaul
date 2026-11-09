package dev.overhaul.minecraft.blaze;

import dev.overhaul.core.culling.SoftwareDepthBuffer;
import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Matrix4f;

/**
 * Hides sections the terrain depth of a recent frame proves were behind something. Each test asks about the view that
 * depth was drawn from, so turning the camera cannot uncover a wrong answer: what that view did not show counts as
 * visible. Moving can, so boxes grow by the distance moved and the test stops altogether past {@link #MAX_MOVE}.
 * Owner-thread only.
 */
public final class BlazeSectionOcclusion implements AutoCloseable {
    private static final double MAX_MOVE = 4;
    private static final int SECTION = 16;
    /** Block entity models may lean out of their section a little. */
    private static final int BLOCK_ENTITY_REACH = 2;
    /** Depth is from the fence-passed past, and floats round; a little distance keeps a section's own surface from hiding it. */
    private static final float MARGIN = .5f;
    /** Older depth says too little about now, and a path that stopped capturing must not keep testing against its last one. */
    private static final long MAX_AGE_NANOS = 250_000_000L;
    private static final int VERDICT_SLOTS = 1 << 14;
    /** Depth this recent says as much as the next frame's would, and every capture costs a readback and a new pyramid. */
    private static final long MIN_CAPTURE_NANOS = 10_000_000L;

    private final VulkanContext context;
    private final BlazeQueue queue;
    private final BlazeDepthTiles tiles;
    private final float[] viewProjection = new float[16];
    /** The newest queued capture, which GPU tests queued after it read; the CPU's copy above lags it by a fence. */
    private float[] queuedViewProjection;
    private double queuedX, queuedY, queuedZ;
    private int queuedWide, queuedHigh;
    private long queuedAt;
    private BlazeSectionCull cull;
    private boolean cullFailed;
    /**
     * Verdicts by section, as origin then stamp and answer in neighbouring longs: layers ask about the same sections, and
     * only the camera or new depth can change an answer. Two sections sharing a slot only means one gets asked again.
     */
    private final long[] verdicts = new long[VERDICT_SLOTS * 2];
    private long stamp = 1;
    private double preparedX, preparedY, preparedZ;
    private SoftwareDepthBuffer depth;
    private int pixelsWide, pixelsHigh;
    private double cameraX, cameraY, cameraZ;
    private long capturedAt, requestedAt;
    private boolean usable;
    private float grow;

    BlazeSectionOcclusion(VulkanContext context, GpuAllocator allocator, BlazeQueue queue) {
        this.context = context;
        this.queue = queue;
        tiles = new BlazeDepthTiles(context, allocator, queue);
    }

    /**
     * Hands this layer's section tests to the batch when the GPU makes them; the caller then tests no section itself.
     * Call after {@link #prepare}.
     */
    public boolean handTo(BlazeSectionBatch batch) {
        if (!BlazeOptimization.GPU_OCCLUSION.on() || cullFailed) return false;
        if (cull == null) {
            try {
                cull = new BlazeSectionCull(context, queue);
            } catch (RuntimeException failure) {
                cullFailed = true;
                BlazeGlBoundary.reportOnce("section cull", "Terrain occlusion tests stay on the CPU: " + failure.getMessage());
                return false;
            }
        }
        batch.cullBy(this);
        return true;
    }

    /** Queues the GPU test of these section draws, made against the camera last prepared; nothing when no capture applies. */
    void cull(BlazeBufferPool.Slice commands, BlazeBufferPool.Slice positions, int count) {
        GpuBuffer latest = tiles.latest();
        if (latest == null || queuedViewProjection == null || System.nanoTime() - queuedAt > MAX_AGE_NANOS) return;
        double dx = preparedX - queuedX, dy = preparedY - queuedY, dz = preparedZ - queuedZ, move = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (move > MAX_MOVE) return;
        int tilesWide = (queuedWide + BlazeDepthTiles.TILE_PIXELS - 1) / BlazeDepthTiles.TILE_PIXELS;
        int tilesHigh = (queuedHigh + BlazeDepthTiles.TILE_PIXELS - 1) / BlazeDepthTiles.TILE_PIXELS;
        cull.enqueue(commands, positions, count, latest, new BlazeSectionCull.View(queuedViewProjection, (float) dx, (float) dy, (float) dz,
                (float) move + MARGIN, tilesWide, tilesHigh, queuedWide, queuedHigh));
    }

    /** Called for each layer with the camera now; decides whether this layer's tests can mean anything. */
    public void prepare(double x, double y, double z) {
        usable = false;
        if (x != preparedX || y != preparedY || z != preparedZ) {
            preparedX = x;
            preparedY = y;
            preparedZ = z;
            stamp++;
        }
        if (depth == null || System.nanoTime() - capturedAt > MAX_AGE_NANOS) return;
        double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ, move = Math.sqrt(dx * dx + dy * dy + dz * dz);
        usable = move <= MAX_MOVE;
        grow = (float) move + MARGIN;
    }

    /** Whether the section whose lowest corner is at this block position was entirely behind captured terrain. */
    public boolean hidden(int originX, int originY, int originZ) {
        if (!usable) return false;
        long origin = ((long) originX & 0x3FFFFFFL) << 38 | ((long) originZ & 0x3FFFFFFL) << 12 | originY & 0xFFFL;
        int at = ((originX >> 4) * 73856093 ^ (originY >> 4) * 19349663 ^ (originZ >> 4) * 83492791) & VERDICT_SLOTS - 1;
        at *= 2;
        boolean hidden;
        if (verdicts[at] == origin && verdicts[at + 1] >>> 1 == stamp) {
            hidden = (verdicts[at + 1] & 1) != 0;
        } else {
            hidden = behindTerrain(originX, originY, originZ, originX + SECTION, originY + SECTION, originZ + SECTION);
            verdicts[at] = origin;
            verdicts[at + 1] = stamp << 1 | (hidden ? 1 : 0);
        }
        if (hidden) BlazeStats.hiddenSection();
        return hidden;
    }

    /** Whether everything the block entities of this section draw was entirely behind captured terrain. */
    public boolean blockEntitiesHidden(int originX, int originY, int originZ) {
        if (!usable || !behindTerrain(originX - BLOCK_ENTITY_REACH, originY - BLOCK_ENTITY_REACH, originZ - BLOCK_ENTITY_REACH,
                originX + SECTION + BLOCK_ENTITY_REACH, originY + SECTION + BLOCK_ENTITY_REACH, originZ + SECTION + BLOCK_ENTITY_REACH)) return false;
        BlazeStats.hiddenBlockEntities();
        return true;
    }

    /** Whether this world-space box was entirely behind captured terrain. */
    public boolean entityHidden(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!usable || !behindTerrain(minX, minY, minZ, maxX, maxY, maxZ)) return false;
        BlazeStats.hiddenEntity();
        return true;
    }

    private boolean behindTerrain(double minWorldX, double minWorldY, double minWorldZ, double maxWorldX, double maxWorldY, double maxWorldZ) {
        float left = (float) (minWorldX - cameraX) - grow, low = (float) (minWorldY - cameraY) - grow, back = (float) (minWorldZ - cameraZ) - grow;
        float acrossX = (float) (maxWorldX - minWorldX) + 2 * grow, acrossY = (float) (maxWorldY - minWorldY) + 2 * grow, acrossZ = (float) (maxWorldZ - minWorldZ) + 2 * grow;
        float[] m = viewProjection;
        // The corners of a box differ along three axes, so one full projection and three steps give all eight.
        float baseX = m[0] * left + m[4] * low + m[8] * back + m[12], baseY = m[1] * left + m[5] * low + m[9] * back + m[13];
        float baseZ = m[2] * left + m[6] * low + m[10] * back + m[14], baseW = m[3] * left + m[7] * low + m[11] * back + m[15];
        float stepXx = m[0] * acrossX, stepXy = m[1] * acrossX, stepXz = m[2] * acrossX, stepXw = m[3] * acrossX;
        float stepYx = m[4] * acrossY, stepYy = m[5] * acrossY, stepYz = m[6] * acrossY, stepYw = m[7] * acrossY;
        float stepZx = m[8] * acrossZ, stepZy = m[9] * acrossZ, stepZz = m[10] * acrossZ, stepZw = m[11] * acrossZ;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, nearest = Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            float x = baseX, y = baseY, z = baseZ, w = baseW;
            if ((corner & 1) != 0) { x += stepXx; y += stepXy; z += stepXz; w += stepXw; }
            if ((corner & 2) != 0) { x += stepYx; y += stepYy; z += stepYz; w += stepYw; }
            if ((corner & 4) != 0) { x += stepZx; y += stepZy; z += stepZz; w += stepZw; }
            if (w <= .05f) return false;
            float inverse = 1 / w, screenX = x * inverse, screenY = y * inverse, screenZ = z * inverse;
            if (screenX < minX) minX = screenX;
            if (screenX > maxX) maxX = screenX;
            if (screenY < minY) minY = screenY;
            if (screenY > maxY) maxY = screenY;
            if (screenZ < nearest) nearest = screenZ;
        }
        float toTilesX = pixelsWide * .5f / BlazeDepthTiles.TILE_PIXELS, toTilesY = pixelsHigh * .5f / BlazeDepthTiles.TILE_PIXELS;
        // Depth images keep OpenGL's row order and vertex output is not flipped, so row 0 is NDC y -1. Off-screen parts fail open.
        return depth.quicklyOccluded((int) Math.floor((minX + 1) * toTilesX), (int) Math.floor((minY + 1) * toTilesY),
                (int) Math.ceil((maxX + 1) * toTilesX), (int) Math.ceil((maxY + 1) * toTilesY), .5f * nearest + .5f);
    }

    /** Reads the target's depth as it stands now, which must hold terrain and nothing that moves. */
    public void capture(BlazeTarget target, Matrix4f modelView, Matrix4f projection, double x, double y, double z) {
        GpuImageView view = target.sampledDepthView();
        if (view == null) return;
        long now = System.nanoTime();
        if (now - requestedAt < MIN_CAPTURE_NANOS) return;
        requestedAt = now;
        float[] matrix = new Matrix4f(projection).mul(modelView).get(new float[16]);
        int wide = target.width(), high = target.height();
        boolean queued = tiles.capture(view, wide, high, (farthest, size) -> {
            if (depth == null || depth.width() != size[0] || depth.height() != size[1]) depth = new SoftwareDepthBuffer(size[0], size[1]);
            depth.load(farthest);
            System.arraycopy(matrix, 0, viewProjection, 0, 16);
            pixelsWide = wide;
            pixelsHigh = high;
            cameraX = x;
            cameraY = y;
            cameraZ = z;
            capturedAt = System.nanoTime();
            stamp++;
        });
        if (!queued) return;
        queuedViewProjection = matrix;
        queuedX = x;
        queuedY = y;
        queuedZ = z;
        queuedWide = wide;
        queuedHigh = high;
        queuedAt = now;
    }

    @Override public void close() {
        if (cull != null) cull.close();
        tiles.close();
    }
}
