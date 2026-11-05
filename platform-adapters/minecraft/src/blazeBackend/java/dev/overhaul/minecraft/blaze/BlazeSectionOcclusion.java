package dev.overhaul.minecraft.blaze;

import dev.overhaul.core.culling.SoftwareDepthBuffer;
import dev.overhaul.vulkan.GpuAllocator;
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

    private final BlazeDepthTiles tiles;
    private final float[] viewProjection = new float[16];
    /** Verdicts per place in the section list: layers walk the same list, and only the camera or new depth can change one. */
    private long[] verdictOrigins = new long[2048];
    private int[] verdictStamps = new int[2048];
    private boolean[] verdicts = new boolean[2048];
    private int stamp = 1;
    private double preparedX, preparedY, preparedZ;
    private SoftwareDepthBuffer depth;
    private int pixelsWide, pixelsHigh;
    private double cameraX, cameraY, cameraZ;
    private long capturedAt;
    private boolean usable;
    private float grow;

    BlazeSectionOcclusion(VulkanContext context, GpuAllocator allocator, BlazeQueue queue) {
        tiles = new BlazeDepthTiles(context, allocator, queue);
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

    /**
     * Whether the section whose lowest corner is at this block position was entirely behind captured terrain.
     *
     * @param place the section's index in the list being walked, under which its verdict is remembered
     */
    public boolean hidden(int place, int originX, int originY, int originZ) {
        if (!usable) return false;
        if (place >= verdicts.length) growVerdicts(place);
        long origin = ((long) originX & 0x3FFFFFFL) << 38 | ((long) originZ & 0x3FFFFFFL) << 12 | originY & 0xFFFL;
        if (verdictStamps[place] != stamp || verdictOrigins[place] != origin) {
            verdictStamps[place] = stamp;
            verdictOrigins[place] = origin;
            verdicts[place] = behindTerrain(originX, originY, originZ, originX + SECTION, originY + SECTION, originZ + SECTION);
        }
        if (verdicts[place]) BlazeStats.hiddenSection();
        return verdicts[place];
    }

    private void growVerdicts(int place) {
        int size = Math.max(place + 1, verdicts.length * 2);
        verdictOrigins = java.util.Arrays.copyOf(verdictOrigins, size);
        verdictStamps = java.util.Arrays.copyOf(verdictStamps, size);
        verdicts = java.util.Arrays.copyOf(verdicts, size);
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
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, nearest = Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            float x = left + ((corner & 1) == 0 ? 0 : acrossX), y = low + ((corner & 2) == 0 ? 0 : acrossY), z = back + ((corner & 4) == 0 ? 0 : acrossZ);
            float w = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (w <= .05f) return false;
            float screenX = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w, screenY = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w;
            float screenZ = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w;
            minX = Math.min(minX, screenX);
            maxX = Math.max(maxX, screenX);
            minY = Math.min(minY, screenY);
            maxY = Math.max(maxY, screenY);
            nearest = Math.min(nearest, screenZ);
        }
        float toTilesX = pixelsWide * .5f / BlazeDepthTiles.TILE_PIXELS, toTilesY = pixelsHigh * .5f / BlazeDepthTiles.TILE_PIXELS;
        // Depth images keep OpenGL's row order and vertex output is not flipped, so row 0 is NDC y -1. Off-screen parts fail open.
        return depth.quicklyOccluded((int) Math.floor((minX + 1) * toTilesX), (int) Math.floor((minY + 1) * toTilesY),
                (int) Math.ceil((maxX + 1) * toTilesX), (int) Math.ceil((maxY + 1) * toTilesY), .5f * nearest + .5f);
    }

    /** Reads the target's depth as it stands now, which must hold terrain and nothing that moves. */
    public void capture(BlazeTarget target, Matrix4f modelView, Matrix4f projection, double x, double y, double z) {
        GpuImageView view = target.depthView();
        if (view == null) return;
        float[] matrix = new Matrix4f(projection).mul(modelView).get(new float[16]);
        int wide = target.width(), high = target.height();
        tiles.capture(view, wide, high, (farthest, size) -> {
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
    }

    @Override public void close() {
        tiles.close();
    }
}
