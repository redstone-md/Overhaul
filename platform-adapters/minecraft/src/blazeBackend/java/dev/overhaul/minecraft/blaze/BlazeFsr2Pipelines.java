package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuSampler;
import dev.overhaul.vulkan.SamplerSettings;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.VulkanStack;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The FSR2 compute pipelines, their descriptor set layouts and the two samplers every pass declares in set 0. These
 * do not depend on any size, so they live as long as the renderer while contexts come and go with the resolution.
 */
final class BlazeFsr2Pipelines implements AutoCloseable {
    /** One compiled pass: set 1's layout, the pipeline layout over sets 0 and 1, and the pipeline. */
    record Compiled(BlazeFsr2Shaders.Pass pass, long setLayout, long pipelineLayout, long pipeline) {}

    private final VulkanContext context;
    private final GpuSampler point, linear;
    private final Map<BlazeFsr2Shaders.Stage, Compiled> passes = new EnumMap<>(BlazeFsr2Shaders.Stage.class);
    private long samplerLayout, samplerPool, samplerSet;

    BlazeFsr2Pipelines(VulkanContext context, GpuAllocator allocator) {
        this.context = context;
        point = allocator.createSampler(sampler(SamplerSettings.Filter.NEAREST));
        GpuSampler created = null;
        try {
            created = allocator.createSampler(sampler(SamplerSettings.Filter.LINEAR));
        } finally {
            if (created == null) point.close();
        }
        linear = created;
        try {
            createSamplerSet();
            try (ShadercCompiler compiler = new ShadercCompiler()) {
                for (BlazeFsr2Shaders.Stage stage : BlazeFsr2Shaders.Stage.values()) passes.put(stage, create(BlazeFsr2Shaders.compile(compiler, stage)));
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    Compiled pass(BlazeFsr2Shaders.Stage stage) {
        return passes.get(stage);
    }

    long samplerSet() {
        return samplerSet;
    }

    private static SamplerSettings sampler(SamplerSettings.Filter filter) {
        return new SamplerSettings(filter, filter, SamplerSettings.Mipmap.NEAREST, SamplerSettings.Address.CLAMP, SamplerSettings.Address.CLAMP,
                SamplerSettings.Address.CLAMP, 0, 16, 1, SamplerSettings.Compare.NONE);
    }

    private void createSamplerSet() {
        VkDevice device = context.device();
        try (MemoryStack stack = VulkanStack.push()) {
            var pointer = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), "sampler layout");
            samplerLayout = pointer.get(0);
            var sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(2);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, pointer), "sampler pool");
            samplerPool = pointer.get(0);
            var allocate = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(samplerPool).pSetLayouts(stack.longs(samplerLayout));
            check(vkAllocateDescriptorSets(device, allocate, pointer), "sampler set");
            samplerSet = pointer.get(0);
            var writes = VkWriteDescriptorSet.calloc(2, stack);
            long[] samplers = {point.handle(), linear.handle()};
            for (int i = 0; i < 2; i++) {
                var info = VkDescriptorImageInfo.calloc(1, stack).sampler(samplers[i]);
                writes.get(i).sType$Default().dstSet(samplerSet).dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).pImageInfo(info);
            }
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private Compiled create(BlazeFsr2Shaders.Pass pass) {
        VkDevice device = context.device();
        long setLayout = 0, pipelineLayout = 0;
        try (MemoryStack stack = VulkanStack.push()) {
            var pointer = stack.mallocLong(1);
            List<BlazeFsr2Shaders.Binding> declared = pass.bindings();
            var bindings = VkDescriptorSetLayoutBinding.calloc(declared.size(), stack);
            for (int i = 0; i < declared.size(); i++) {
                BlazeFsr2Shaders.Binding binding = declared.get(i);
                bindings.get(i).binding(binding.binding()).descriptorType(descriptorType(binding.kind())).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, pointer), pass.name() + " layout");
            setLayout = pointer.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(samplerLayout, setLayout)), null, pointer),
                    pass.name() + " pipeline layout");
            pipelineLayout = pointer.get(0);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(pass.spirv().directBuffer()), null, pointer), pass.name() + " module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, pointer), pass.name() + " pipeline");
            } finally {
                vkDestroyShaderModule(device, module, null);
            }
            return new Compiled(pass, setLayout, pipelineLayout, pointer.get(0));
        } catch (RuntimeException | Error failure) {
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            throw failure;
        }
    }

    static int descriptorType(BlazeFsr2Shaders.Kind kind) {
        return switch (kind) {
            case SAMPLER -> VK_DESCRIPTOR_TYPE_SAMPLER;
            case SAMPLED -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case STORAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case UNIFORM -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
        };
    }

    static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("FSR2 " + what + " failed: " + result);
    }

    @Override public void close() {
        VkDevice device = context.device();
        for (Compiled compiled : passes.values()) {
            vkDestroyPipeline(device, compiled.pipeline(), null);
            vkDestroyPipelineLayout(device, compiled.pipelineLayout(), null);
            vkDestroyDescriptorSetLayout(device, compiled.setLayout(), null);
        }
        passes.clear();
        if (samplerPool != 0) vkDestroyDescriptorPool(device, samplerPool, null);
        if (samplerLayout != 0) vkDestroyDescriptorSetLayout(device, samplerLayout, null);
        samplerPool = samplerLayout = samplerSet = 0;
        if (linear != null) linear.close();
        point.close();
    }
}
