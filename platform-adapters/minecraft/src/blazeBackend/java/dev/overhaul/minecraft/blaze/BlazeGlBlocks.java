package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Answer;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary.Arguments;
import dev.overhaul.shader.blaze.BlazeBlockBuffers;
import dev.overhaul.vulkan.GpuBuffer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Indexed buffer bindings of the uniform blocks that hold arrays or structs, which run as storage buffers. A mod binds a
 * buffer to a binding point and points a program's block at it; a draw follows both links back to the buffer.
 */
public final class BlazeGlBlocks {
    private static final int UNIFORM_BUFFER = 0x8A11, SHADER_STORAGE_BUFFER = 0x90D2;
    /** The largest uniform block OpenGL guarantees, so a block nothing bound reads zeros everywhere. */
    private static final int BLANK_BYTES = 65536;
    private static volatile BlazeGlBlocks active;
    private final BlazeGlBuffers buffers;
    /** Binding point to the buffer name bound there. */
    private final Int2IntOpenHashMap points = new Int2IntOpenHashMap();
    /** Block index, the same for every program, to the binding point its programs read. */
    private final Int2IntOpenHashMap blocks = new Int2IntOpenHashMap();
    private BlazeRenderer blankOwner;
    private GpuBuffer blank;

    private BlazeGlBlocks(BlazeGlBuffers buffers) {
        this.buffers = buffers;
        points.defaultReturnValue(0);
        blocks.defaultReturnValue(-1);
    }

    static void install(Map<String, Answer> table, BlazeGlBuffers buffers) {
        BlazeGlBlocks blocks = new BlazeGlBlocks(buffers);
        table.put("glBindBufferBase", arguments -> blocks.bind(arguments, 0));
        table.put("glBindBufferRange", arguments -> blocks.bind(arguments, arguments.pointer(3)));
        table.put("glUniformBlockBinding", arguments -> {
            synchronized (blocks) {
                blocks.blocks.put(arguments.integer(1), arguments.integer(2));
            }
            return 0;
        });
        active = blocks;
    }

    private long bind(Arguments arguments, long offset) {
        int target = arguments.integer(0), name = arguments.integer(2);
        // Binding a range also binds the generic target, which the mod's next buffer write goes through.
        buffers.bind(target, name);
        if (target != UNIFORM_BUFFER && target != SHADER_STORAGE_BUFFER) return 0;
        if (offset != 0) BlazeGlBoundary.reportOnce("block range offset", "A uniform block buffer bound at an offset reads from its start on this backend");
        synchronized (this) {
            points.put(arguments.integer(1), name);
        }
        return 0;
    }

    /**
     * The storage buffer a program's descriptor reads, or null when the descriptor stands for no mod block. A block no
     * buffer was bound for reads zeros.
     */
    public static BlazeTextures.Sampled storage(String descriptor) {
        String block = BlazeBlockBuffers.blockOf(descriptor);
        BlazeGlBlocks blocks = active;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (block == null || blocks == null || renderer == null) return null;
        return BlazeTextures.Sampled.storage(blocks.buffer(renderer, BlazeBlockBuffers.index(block)));
    }

    private synchronized GpuBuffer buffer(BlazeRenderer renderer, int block) {
        int point = blocks.get(block);
        int name = point < 0 ? 0 : points.get(point);
        GpuBuffer bound = name == 0 ? null : buffers.device(renderer, name);
        return bound != null ? bound : blank(renderer);
    }

    private GpuBuffer blank(BlazeRenderer renderer) {
        if (blank != null && blankOwner == renderer) return blank;
        blank = renderer.newGlDeviceBuffer(BLANK_BYTES);
        blankOwner = renderer;
        long zeros = MemoryUtil.nmemCalloc(1, BLANK_BYTES);
        try {
            renderer.writeGlDeviceBuffer(blank, 0, zeros, BLANK_BYTES);
        } finally {
            MemoryUtil.nmemFree(zeros);
        }
        return blank;
    }
}
