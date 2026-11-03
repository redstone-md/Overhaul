package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Descriptor set 0, binding 0 is the column-major camera transform. */
final class CameraUniform implements AutoCloseable {
    private final VulkanContext context;
    private final GpuBuffer buffer;
    private final ByteBuffer scratch = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
    long layout;
    private long pool;
    long set;

    CameraUniform(VulkanContext context, GpuAllocator allocator) {
        this.context = context;
        buffer = allocator.hostBuffer(64, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pointer = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            check(vkCreateDescriptorSetLayout(context.device(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(binding), null, pointer), "vkCreateDescriptorSetLayout");
            layout = pointer.get(0);
            VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack)
                .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            check(vkCreateDescriptorPool(context.device(), VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().maxSets(1).pPoolSizes(size), null, pointer), "vkCreateDescriptorPool");
            pool = pointer.get(0);
            check(vkAllocateDescriptorSets(context.device(), VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(layout)), pointer), "vkAllocateDescriptorSets");
            set = pointer.get(0);
            VkDescriptorBufferInfo.Buffer data = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(buffer.handle()).offset(0).range(64);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                .dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(data);
            vkUpdateDescriptorSets(context.device(), write, null);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    void write(float[] transform) {
        if (transform.length != 16) throw new IllegalArgumentException("camera matrix must contain 16 floats");
        scratch.clear();
        for (float value : transform) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("camera matrix must be finite");
            scratch.putFloat(value);
        }
        buffer.write(scratch.flip());
    }

    @Override public void close() {
        context.requireOwner();
        if (pool != 0) vkDestroyDescriptorPool(context.device(), pool, null);
        if (layout != 0) vkDestroyDescriptorSetLayout(context.device(), layout, null);
        pool = layout = set = 0;
        buffer.close();
    }
}
