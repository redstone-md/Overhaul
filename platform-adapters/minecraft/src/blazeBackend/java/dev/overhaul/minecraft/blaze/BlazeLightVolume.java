package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.SamplerSettings;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The colour of block light around the camera, for Overhaul shading to tint its block light with. The blocks of the
 * eight by four by eight sections around the camera are kept as {@link BlazeLightColors}' words in an image, a tile per
 * section; a compute shader spreads the light of those that give any from block to block, a block further each step,
 * through what each block lets pass, into a volume the lighting pass reads. Both wrap: a block lies where its
 * coordinates fall in the volume's size, so moving the camera rewrites only the sections that came into reach. Sections
 * are read a few a frame: new ones first, then all of them in turn, which is how a placed or broken block is noticed;
 * only a section that changed is sent to the card.
 */
final class BlazeLightVolume implements AutoCloseable {
    /** The volume's size in sections, and the tile a section's blocks take in the image of blocks. */
    private static final int WIDE = 8, HIGH = 4, SLOTS = WIDE * HIGH * WIDE, TILE = 64;
    /** Sections new to the volume read in one frame, sections read again each second, and the most of those in one frame. */
    private static final int NEW_A_FRAME = 8, AGAIN_A_SECOND = 384, AGAIN_A_FRAME = 6;
    /** Nanoseconds between two spreads of the light: the light of a new torch is all there in a tenth of a second. */
    private static final long EVERY = 12_000_000;
    private static final long NONE = Long.MIN_VALUE;

    private static final String SPREAD = """
            #version 460
            layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;
            layout(binding = 0) uniform sampler2D blocks;
            layout(binding = 1) uniform sampler3D before;
            layout(binding = 2, rgba16f) writeonly uniform image3D after;
            // The volume's first block, where its coordinates fall in the volume.
            layout(push_constant) uniform Step { ivec4 origin; };
            const ivec3 SIZE = ivec3(128, 64, 128);

            vec4 blockAt(ivec3 voxel) {
                ivec3 slot = voxel >> 4, inside = voxel & 15;
                int tile = slot.x + slot.z * 8 + slot.y * 64;
                return texelFetch(blocks, ivec2((tile & 15) * 64 + inside.x + (inside.y & 3) * 16, (tile >> 4) * 64 + inside.z + (inside.y >> 2) * 16), 0);
            }

            // The light a neighbour hands on: a level weaker, its colour kept. Nothing crosses the volume's edge.
            vec3 from(ivec3 voxel, ivec3 within, ivec3 way) {
                ivec3 there = within + way;
                if (any(lessThan(there, ivec3(0))) || any(greaterThanEqual(there, SIZE))) return vec3(0.0);
                vec3 light = texelFetch(before, (voxel + way) & (SIZE - 1), 0).rgb;
                float most = max(light.r, max(light.g, light.b));
                return most > 1.0 / 15.0 ? light * ((most - 1.0 / 15.0) / most) : vec3(0.0);
            }

            void main() {
                ivec3 voxel = ivec3(gl_GlobalInvocationID);
                ivec3 within = (voxel - origin.xyz) & (SIZE - 1);
                vec4 block = blockAt(voxel);
                // A light's own word carries its level; the faintest mark is a fluid's.
                bool gives = block.a > 0.03;
                vec3 passes = gives ? vec3(1.0) : block.rgb;
                vec3 light = vec3(0.0);
                if (passes.r + passes.g + passes.b > 0.0) {
                    light = max(max(from(voxel, within, ivec3(1, 0, 0)), from(voxel, within, ivec3(-1, 0, 0))),
                            max(from(voxel, within, ivec3(0, 1, 0)), from(voxel, within, ivec3(0, -1, 0))));
                    light = max(light, max(from(voxel, within, ivec3(0, 0, 1)), from(voxel, within, ivec3(0, 0, -1)))) * passes;
                }
                if (gives) light = max(light, block.rgb * block.a);
                imageStore(after, voxel, vec4(light, 1.0));
            }
            """;

    /** What the lighting pass reads: the volume and the blocks, how to sample each, and which sixteen blocks of them the camera is in. */
    record Sampled(GpuImageView view, long sampler, GpuImageView blocks, long exact, int cell) {}

    private final BlazeRenderer owner;
    private final VulkanContext context;
    private final GpuSampler nearest, wrapped;
    private final GpuImage blocks;
    private final GpuImageView blocksView;
    /** The light is spread from the first into the second and back, so the first always holds the latest. */
    private final GpuImage[] light = new GpuImage[2];
    private final GpuImageView[] lightViews = new GpuImageView[2];
    private final long[] sets = new long[2];
    private long setLayout, pipelineLayout, pipeline, pool;
    /** The section each slot holds, what its blocks summed to when last sent, and whether it is yet to be read. */
    private final long[] held = new long[SLOTS];
    private final int[] sums = new int[SLOTS];
    private final boolean[] fresh = new boolean[SLOTS];
    private final ByteBuffer tile = MemoryUtil.memAlloc(TILE * TILE * 4);
    private ClientLevel world;
    private int originX = Integer.MIN_VALUE, originY, originZ, cursor;
    private long readAt, spreadAt;
    private double due;
    private boolean closed;

    BlazeLightVolume(BlazeRenderer owner) {
        this.owner = owner;
        context = owner.context();
        nearest = sampler(SamplerSettings.Filter.NEAREST, SamplerSettings.Address.CLAMP);
        wrapped = sampler(SamplerSettings.Filter.LINEAR, SamplerSettings.Address.REPEAT);
        blocks = owner.allocator().createImage(ImageFormat.RGBA8_UNORM, 16 * TILE, 16 * TILE, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        blocksView = blocks.view(0, 1, 0, 1);
        Arrays.fill(held, NONE);
        try {
            owner.clearImage(blocks, 0, 0, 1, 1, 1, 0);
            for (int i = 0; i < 2; i++) {
                light[i] = owner.allocator().createVolume(ImageFormat.RGBA16_SFLOAT, WIDE * 16, HIGH * 16, WIDE * 16,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                lightViews[i] = light[i].view(0, 1, 0, 1);
                owner.clearImage(light[i], 0, 0, 0, 0, 0, 0);
            }
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private GpuSampler sampler(SamplerSettings.Filter filter, SamplerSettings.Address address) {
        return owner.allocator().createSampler(new SamplerSettings(filter, filter, SamplerSettings.Mipmap.NEAREST, address, address, address, 0, 0, 1, SamplerSettings.Compare.NONE));
    }

    private void create() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push(); ShadercCompiler compiler = new ShadercCompiler(true)) {
            var pointer = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .descriptorType(i < 2 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            BlazeFsr2Pipelines.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "light volume layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            BlazeFsr2Pipelines.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout))
                    .pPushConstantRanges(range), null, pointer), "light volume pipeline layout");
            pipelineLayout = pointer.get(0);
            var code = compiler.compile("overhaul_light_volume", SPREAD, ShaderStage.COMPUTE).directBuffer();
            BlazeFsr2Pipelines.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, pointer), "light volume module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                BlazeFsr2Pipelines.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), "light volume pipeline");
                pipeline = pointer.get(0);
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            BlazeFsr2Pipelines.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(2).pPoolSizes(sizes), null, pointer), "light volume pool");
            pool = pointer.get(0);
            for (int i = 0; i < 2; i++) {
                BlazeFsr2Pipelines.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pointer),
                        "light volume set");
                sets[i] = pointer.get(0);
                var writes = VkWriteDescriptorSet.calloc(3, stack);
                writes.get(0).sType$Default().dstSet(sets[i]).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(nearest.handle()).imageView(blocksView.handle()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                writes.get(1).sType$Default().dstSet(sets[i]).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(nearest.handle()).imageView(lightViews[i].handle()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                writes.get(2).sType$Default().dstSet(sets[i]).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(lightViews[1 - i].handle()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
    }

    /** Brings the volume up to this frame: follows the camera, reads a few sections and spreads the light a step. */
    Sampled update(ClientLevel level, Vec3 camera) {
        int x = (int) Math.floor(camera.x), y = (int) Math.floor(camera.y), z = (int) Math.floor(camera.z);
        // The camera stays within half a section of the middle.
        int nextX = Math.floorDiv(x + 8, 16) - WIDE / 2, nextY = Math.floorDiv(y + 8, 16) - HIGH / 2, nextZ = Math.floorDiv(z + 8, 16) - WIDE / 2;
        if (level != world) {
            world = level;
            Arrays.fill(held, NONE);
            originX = Integer.MIN_VALUE;
        }
        if (nextX != originX || nextY != originY || nextZ != originZ) {
            originX = nextX;
            originY = nextY;
            originZ = nextZ;
            for (int slot = 0; slot < SLOTS; slot++) {
                long section = section(slot);
                if (held[slot] == section) continue;
                held[slot] = section;
                fresh[slot] = true;
            }
        }
        long now = System.nanoTime();
        int read = 0;
        for (int slot = 0; slot < SLOTS && read < NEW_A_FRAME; slot++) if (fresh[slot]) {
            read(slot);
            read++;
        }
        due = Math.min(AGAIN_A_FRAME, due + (readAt == 0 ? 0 : (now - readAt) * 1e-9 * AGAIN_A_SECOND));
        readAt = now;
        for (; due >= 1 && read < NEW_A_FRAME; due--) {
            cursor = (cursor + 1) % SLOTS;
            if (!fresh[cursor]) read(cursor);
        }
        if (now - spreadAt >= EVERY) {
            spreadAt = now;
            owner.enqueue(new Spread((originX & WIDE - 1) * 16, (originY & HIGH - 1) * 16, (originZ & WIDE - 1) * 16));
        }
        return new Sampled(lightViews[0], wrapped.handle(), blocksView, nearest.handle(), Math.floorDiv(x, 16) & WIDE - 1 | (Math.floorDiv(y, 16) & HIGH - 1) << 3 | (Math.floorDiv(z, 16) & WIDE - 1) << 5);
    }

    /** The section of the volume that falls into a slot: the one whose coordinates end in the slot's. */
    private long section(int slot) {
        int x = slot & WIDE - 1, z = slot >> 3 & WIDE - 1, y = slot >> 6;
        return SectionPos.asLong(originX + (x - originX & WIDE - 1), originY + (y - originY & HIGH - 1), originZ + (z - originZ & WIDE - 1));
    }

    /** Reads a slot's section into its tile, sixteen layers of sixteen by sixteen blocks, and sends it when it changed. */
    private void read(int slot) {
        long section = held[slot];
        int sectionY = SectionPos.y(section);
        LevelChunkSection found = null;
        if (sectionY >= world.getMinSection() && sectionY < world.getMaxSection()) {
            LevelChunk chunk = world.getChunkSource().getChunk(SectionPos.x(section), SectionPos.z(section), false);
            if (chunk != null) found = chunk.getSections()[world.getSectionIndexFromSectionY(sectionY)];
        }
        int sum = 0;
        if (found == null || found.hasOnlyAir()) {
            for (int at = 0; at < TILE * TILE * 4; at += 4) tile.putInt(at, BlazeLightColors.CLEAR);
        } else {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int row = ((z + (y >> 2) * 16) * TILE + (y & 3) * 16) * 4;
                for (int x = 0; x < 16; x++) {
                    int word = BlazeLightColors.of(found.getBlockState(x, y, z));
                    tile.putInt(row + x * 4, word);
                    sum = sum * 31 + word;
                }
            }
        }
        if (!fresh[slot] && sums[slot] == sum) return;
        fresh[slot] = false;
        sums[slot] = sum;
        owner.uploadImage(blocks, new ImageRegion(0, 0, (slot & 15) * TILE, (slot >> 4) * TILE, TILE, TILE), tile, 0);
    }

    /** Two steps of the light, there and back. */
    private final class Spread implements BlazeQueue.Operation {
        private final int x, y, z;
        private final BlazeImageUse images = new BlazeImageUse();

        Spread(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override public void record(VkCommandBuffer command) {
            try (MemoryStack stack = VulkanStack.push()) {
                var push = stack.malloc(16);
                push.putInt(0, x).putInt(4, y).putInt(8, z).putInt(12, 0);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                images.transition(command, blocksView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                for (int i = 0; i < 2; i++) {
                    images.transition(command, lightViews[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    images.transition(command, lightViews[1 - i], VK_IMAGE_LAYOUT_GENERAL);
                    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(sets[i]), null);
                    vkCmdDispatch(command, WIDE * 4, HIGH * 4, WIDE * 4);
                }
            }
        }

        @Override public void submitted(FrameRing.Submission submission) {
            images.submitted(submission);
        }

        @Override public void close() {
            images.close();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        VkDevice device = context.device();
        if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        pool = pipeline = pipelineLayout = setLayout = 0;
        for (int i = 0; i < 2; i++) {
            if (light[i] != null) light[i].close();
            light[i] = null;
        }
        blocks.close();
        nearest.close();
        wrapped.close();
        MemoryUtil.memFree(tile);
    }
}
