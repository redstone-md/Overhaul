package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.PackImages;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The storage images a pack declares with {@code image.*}. They live outside the render graph in GENERAL layout for
 * their whole life: shaders write them with {@code imageStore} and read them through their sampler, and every pass that
 * touches one is fenced by {@link #barrier} instead of a layout transition. The {@code bufferObject.*} storage buffers
 * share that fence and start zeroed.
 */
final class PackImageSet implements AutoCloseable {
    private static final Map<String,ImageFormat> FORMATS = Map.ofEntries(
            Map.entry("rgba32f",ImageFormat.RGBA32_SFLOAT),Map.entry("rgba16f",ImageFormat.RGBA16_SFLOAT),Map.entry("rg32f",ImageFormat.RG32_SFLOAT),
            Map.entry("rg16f",ImageFormat.RG16_SFLOAT),Map.entry("r11f_g11f_b10f",ImageFormat.RG11B10_UFLOAT),Map.entry("r32f",ImageFormat.R32_SFLOAT),
            Map.entry("r16f",ImageFormat.R16_SFLOAT),Map.entry("rgba16",ImageFormat.RGBA16_UNORM),Map.entry("rgb10_a2",ImageFormat.RGB10A2_UNORM),
            Map.entry("rgba8",ImageFormat.RGBA8_UNORM),Map.entry("rg16",ImageFormat.RG16_UNORM),Map.entry("rg8",ImageFormat.RG8_UNORM),
            Map.entry("r16",ImageFormat.R16_UNORM),Map.entry("r8",ImageFormat.R8_UNORM),Map.entry("rgba16_snorm",ImageFormat.RGBA16_SNORM),
            Map.entry("rgba8_snorm",ImageFormat.RGBA8_SNORM),Map.entry("rg16_snorm",ImageFormat.RG16_SNORM),Map.entry("rg8_snorm",ImageFormat.RG8_SNORM),
            Map.entry("r16_snorm",ImageFormat.R16_SNORM),Map.entry("r8_snorm",ImageFormat.R8_SNORM),Map.entry("rgba32i",ImageFormat.RGBA32_SINT),
            Map.entry("rgba16i",ImageFormat.RGBA16_SINT),Map.entry("rgba8i",ImageFormat.RGBA8_SINT),Map.entry("rg32i",ImageFormat.RG32_SINT),
            Map.entry("rg16i",ImageFormat.RG16_SINT),Map.entry("rg8i",ImageFormat.RG8_SINT),Map.entry("r32i",ImageFormat.R32_SINT),
            Map.entry("r16i",ImageFormat.R16_SINT),Map.entry("r8i",ImageFormat.R8_SINT),Map.entry("rgba32ui",ImageFormat.RGBA32_UINT),
            Map.entry("rgba16ui",ImageFormat.RGBA16_UINT),Map.entry("rgb10_a2ui",ImageFormat.RGB10A2_UINT),Map.entry("rgba8ui",ImageFormat.RGBA8_UINT),
            Map.entry("rg32ui",ImageFormat.RG32_UINT),Map.entry("rg16ui",ImageFormat.RG16_UINT),Map.entry("rg8ui",ImageFormat.RG8_UINT),
            Map.entry("r32ui",ImageFormat.R32_UINT),Map.entry("r16ui",ImageFormat.R16_UINT),Map.entry("r8ui",ImageFormat.R8_UINT));
    /** Formats every Vulkan device stores to; the rest need shaderStorageImageExtendedFormats. */
    private static final Set<String> CORE = Set.of("rgba32f","rgba16f","r32f","rgba8","rgba8_snorm","rgba32i","rgba16i","rgba8i","r32i",
            "rgba32ui","rgba16ui","rgba8ui","r32ui");

    private record Entry(PackImages.Image declaration,GpuImage image,GpuImageView view){}
    private final PackImages declarations;
    private final Map<String,Entry> byName=new LinkedHashMap<>(),bySampler=new LinkedHashMap<>();
    private final Map<Integer,GpuBuffer> buffers=new LinkedHashMap<>();
    private GpuSampler linear,nearest;
    private boolean initialized;

    PackImageSet(VulkanContext context,GpuAllocator allocator,PackImages declarations,int width,int height){
        this.declarations=declarations;
        try{
            for(var declaration:declarations.images().values()){
                if(!CORE.contains(declaration.format())&&!context.storageFormats())
                    throw new UnsupportedShaderException("Device cannot store shader-pack image format "+declaration.format()+": "+declaration.name());
                ImageFormat format=FORMATS.get(declaration.format());
                int usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                GpuImage image=declaration.volume()?allocator.createVolume(format,declaration.width(width),declaration.height(height),declaration.depth(),usage)
                        :allocator.createImage(format,declaration.width(width),declaration.height(height),1,1,usage);
                Entry entry=new Entry(declaration,image,image.view(0,1,0,1));
                byName.put(declaration.name(),entry);bySampler.put(declaration.sampler(),entry);
            }
            long range=context.maxStorageBufferRange();
            for(var buffer:declarations.buffers().entrySet()){
                if(buffer.getValue()>range)throw new UnsupportedShaderException("Device storage buffers hold at most "+range+" bytes: bufferObject."+buffer.getKey());
                buffers.put(buffer.getKey(),allocator.deviceBuffer(buffer.getValue(),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT));
            }
            linear=allocator.createSampler(new SamplerSettings(SamplerSettings.Filter.LINEAR,SamplerSettings.Filter.LINEAR,SamplerSettings.Mipmap.NEAREST,
                    SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,SamplerSettings.Address.CLAMP,0,0,1,SamplerSettings.Compare.NONE));
            nearest=allocator.createSampler(SamplerSettings.nearest());
        }catch(RuntimeException|Error failure){close();throw failure;}
    }

    boolean isEmpty(){return byName.isEmpty()&&buffers.isEmpty();}

    /** Moves every image to GENERAL once, before any frame records against it. */
    void initialize(ImmediateCommands lane){
        if(initialized)return;
        for(Entry entry:byName.values())lane.transition(entry.image,0,0,VK_IMAGE_LAYOUT_GENERAL);
        initialized=true;
    }

    /** Zeroes the images the pack asks to clear each frame, or all images and buffers on the first frame. */
    void clear(VkCommandBuffer command,boolean all){
        requireInitialized();
        List<Entry> cleared=new ArrayList<>();for(Entry entry:byName.values())if(all||entry.declaration.clear())cleared.add(entry);
        if(cleared.isEmpty()&&!(all&&!buffers.isEmpty()))return;
        barrier(command);
        if(all)for(GpuBuffer buffer:buffers.values())vkCmdFillBuffer(command,buffer.handle(),0,VK_WHOLE_SIZE,0);
        try(MemoryStack stack=VulkanStack.push()){
            var value=VkClearColorValue.calloc(stack);
            var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            for(Entry entry:cleared)vkCmdClearColorImage(command,entry.image.handle(),VK_IMAGE_LAYOUT_GENERAL,value,range);
        }
        barrier(command);
    }

    /** The storage buffer a pack numbers {@code index}, or null when it declares none. */
    GpuBuffer buffer(int index){requireInitialized();return buffers.get(index);}
    /** The image a storage binding names, or null when the name is not a pack image. */
    GpuImageView storage(String name){Entry entry=byName.get(name);return entry==null?null:checked(entry);}
    /** The image a sampler reads, or null when the sampler is not a pack image's. */
    GpuImageView sampled(String sampler){Entry entry=bySampler.get(sampler);return entry==null?null:checked(entry);}
    /** Linear for float images; integer images cannot be filtered. */
    long sampler(String sampler){
        Entry entry=bySampler.get(sampler);if(entry==null)throw new IllegalArgumentException("Not a pack image sampler: "+sampler);
        return entry.declaration.integer()?nearest.handle():linear.handle();
    }
    /** True when every binding a program declares as a storage image is one of these images. */
    void requireStorage(ProgramBindings bindings,String program){
        for(String name:bindings.samplers().keySet())if(bindings.storageBuffer(name)&&!buffers.containsKey(bindings.bufferIndex(name)))
            throw new UnsupportedShaderException("Storage buffer is not declared with bufferObject."+bindings.bufferIndex(name)+": "+program);
        for(String name:bindings.samplers().keySet())if(bindings.storageImage(name)){
            Entry entry=byName.get(name);
            if(entry==null)throw new UnsupportedShaderException("Storage image is not declared with image."+name+": "+program);
            if(entry.declaration.volume()!=bindings.volume(name))throw new UnsupportedShaderException("Storage image "+name+" dimension differs from its declaration: "+program);
        }
    }
    /** True when the binding is a pack storage buffer, a storage image or the sampler of a pack image. */
    boolean owns(ProgramBindings bindings,String name){
        if(bindings.storageBuffer(name))return buffers.containsKey(bindings.bufferIndex(name));
        return bindings.storageImage(name)?byName.containsKey(name):bySampler.containsKey(name);
    }
    boolean uses(ProgramBindings bindings){
        for(String name:bindings.samplers().keySet())if(owns(bindings,name))return true;
        return false;
    }
    PackImages declarations(){return declarations;}

    /**
     * Orders every earlier shader write before every later shader access. Pack images skip graph tracking, so this
     * runs before and after each pass that may write one.
     */
    static void barrier(VkCommandBuffer command){
        try(MemoryStack stack=VulkanStack.push()){
            var memory=VkMemoryBarrier.calloc(1,stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,memory,null,null);
        }
    }
    private GpuImageView checked(Entry entry){requireInitialized();return entry.view;}
    private void requireInitialized(){if(!initialized)throw new IllegalStateException("Initialize pack images before shader execution");}

    @Override public void close(){
        List<Entry> reverse=new ArrayList<>(byName.values());java.util.Collections.reverse(reverse);
        for(Entry entry:reverse)entry.image.close();
        byName.clear();bySampler.clear();
        for(GpuBuffer buffer:buffers.values())buffer.close();buffers.clear();
        if(nearest!=null){nearest.close();nearest=null;}if(linear!=null){linear.close();linear=null;}initialized=false;
    }
}
