package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.VulkanContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** Compatible layouts are cached; each draw leases an immutable set until its submission fence. */
final class BlazeDescriptors implements AutoCloseable {
    private static final int SETS_PER_PAGE=128,MAX_PAGES=64,MAX_LAYOUTS=128;
    private final VulkanContext context;
    private final BlazeQueue queue;
    private final LinkedHashMap<BlazeProgramCompiler.Program,Layout> layouts=new LinkedHashMap<>(32,.75f,true);
    private int pageCount;
    private boolean closed;

    BlazeDescriptors(VulkanContext context,BlazeQueue queue){this.context=context;this.queue=queue;}
    Lease acquire(BlazeProgramCompiler.Program program,BlazeBufferPool.Slice uniforms,int range,List<BlazeTextures.Sampled> samples){
        if(closed)throw new IllegalStateException("Descriptor storage is closed");
        Layout layout=layouts.get(program);
        if(layout==null){
            if(layouts.size()==MAX_LAYOUTS){var iterator=layouts.entrySet().iterator();Layout old=iterator.next().getValue();iterator.remove();queue.retire(old);}
            layout=new Layout(program);layouts.put(program,layout);
        }
        Page page=layout.available();
        if(page==null){
            if(pageCount==MAX_PAGES){
                queue.reclaim();
                for(Layout candidate:layouts.values())candidate.discardIdlePages();
                if(pageCount==MAX_PAGES)throw new IllegalStateException("Live Blaze descriptor sets exceed bounded pool capacity");
            }
            page=new Page(layout);layout.pages.add(page);
        }
        long set=page.free.removeFirst();page.active++;
        Lease lease=new Lease(layout,page,set);
        try{write(lease,program,uniforms,range,samples);return lease;}catch(RuntimeException|Error failure){lease.close();throw failure;}
    }
    private void write(Lease lease,BlazeProgramCompiler.Program program,BlazeBufferPool.Slice uniforms,int range,List<BlazeTextures.Sampled> samples){
        try(MemoryStack stack=VulkanStack.push()){
            var writes=VkWriteDescriptorSet.calloc(samples.size()+1,stack);
            writes.get(0).sType$Default().dstSet(lease.set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(uniforms.buffer().handle()).offset(uniforms.offset()).range(range));
            int index=0;
            for(int binding:program.bindings().samplers().values()){
                BlazeTextures.Sampled sample=samples.get(index);
                writes.get(++index).sType$Default().dstSet(lease.set).dstBinding(binding).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(sample.view().handle()).sampler(sample.sampler()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            }
            vkUpdateDescriptorSets(context.device(),writes,null);
        }
    }
    void forget(BlazeProgramCompiler.Program program){Layout layout=layouts.remove(program);if(layout!=null)queue.retire(layout);}
    @Override public void close(){if(!closed){for(Layout layout:layouts.values())layout.close();layouts.clear();closed=true;}}

    final class Lease implements AutoCloseable {
        private final Layout layout;
        private final Page page;
        private final long set;
        private boolean released;
        private Lease(Layout layout,Page page,long set){this.layout=layout;this.page=page;this.set=set;}
        long layout(){return layout.handle;}long set(){return set;}
        @Override public void close(){if(!released){released=true;page.active--;if(!page.closed)page.free.addLast(set);}}
    }
    private final class Layout implements AutoCloseable {
        private final List<Page> pages=new ArrayList<>();
        private final int samplerCount;
        private long handle;
        private Layout(BlazeProgramCompiler.Program program){
            samplerCount=program.bindings().samplers().size();
            try(MemoryStack stack=VulkanStack.push()){
                var bindings=VkDescriptorSetLayoutBinding.calloc(samplerCount+1,stack);
                bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
                int index=1;for(int number:program.bindings().samplers().values())bindings.get(index++).binding(number).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
                var pointer=stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,pointer),"Blaze descriptor layout");handle=pointer.get(0);
            }
        }
        private Page available(){for(Page page:pages)if(!page.free.isEmpty())return page;return null;}
        private void discardIdlePages(){var iterator=pages.iterator();while(iterator.hasNext()){Page page=iterator.next();if(page.active==0){page.close();iterator.remove();}}}
        @Override public void close(){if(handle==0)return;for(Page page:pages)page.close();pages.clear();vkDestroyDescriptorSetLayout(context.device(),handle,null);handle=0;}
    }
    private final class Page implements AutoCloseable {
        private final ArrayDeque<Long> free=new ArrayDeque<>();
        private long pool;
        private int active;
        private boolean closed;
        private Page(Layout layout){
            try(MemoryStack stack=VulkanStack.push()){
                var sizes=VkDescriptorPoolSize.calloc(layout.samplerCount==0?1:2,stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(SETS_PER_PAGE);
                if(layout.samplerCount!=0)sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_PAGE*layout.samplerCount);
                var pointer=stack.mallocLong(1);
                check(vkCreateDescriptorPool(context.device(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SETS_PER_PAGE).pPoolSizes(sizes),null,pointer),"Blaze descriptor page");pool=pointer.get(0);
                var nativeLayouts=stack.mallocLong(SETS_PER_PAGE);for(int index=0;index<SETS_PER_PAGE;index++)nativeLayouts.put(index,layout.handle);
                var sets=stack.mallocLong(SETS_PER_PAGE);
                check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(nativeLayouts),sets),"Blaze descriptor page sets");
                for(int index=0;index<SETS_PER_PAGE;index++)free.addLast(sets.get(index));pageCount++;
            }catch(RuntimeException|Error failure){if(pool!=0)vkDestroyDescriptorPool(context.device(),pool,null);throw failure;}
        }
        @Override public void close(){if(!closed){closed=true;vkDestroyDescriptorPool(context.device(),pool,null);pool=0;free.clear();pageCount--;}}
    }
}
