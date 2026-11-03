package dev.overhaul.vulkan;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.lwjgl.vulkan.VK10.*;

/** Per-level graph writes keep lower mip lifetime/layouts distinct from the rendered base attachment. */
final class PackMipmaps {
    private final VulkanContext context;
    private final Set<Integer> supported=new HashSet<>();
    PackMipmaps(VulkanContext context){this.context=context;}

    void generate(PackFrameGraph plan,List<GpuImageView> levels){
        if(levels==null||levels.isEmpty())throw new IllegalArgumentException("Missing pack mip chain");
        if(levels.size()==1)return;
        requireLinearBlit(levels.getFirst().image().format().vkFormat());
        for(int mip=1;mip<levels.size();mip++){
            GpuImageView source=levels.get(mip-1),target=levels.get(mip);
            String read=plan.read(source),write=plan.write(target);
            plan.pass("mipmap-"+write,Map.of(read,VulkanRenderGraph.Usage.TRANSFER_READ,write,VulkanRenderGraph.Usage.TRANSFER_WRITE),pass->{
                try(MemoryStack stack=MemoryStack.stackPush()){
                    var region=VkImageBlit.calloc(1,stack);
                    region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(source.baseMip()).baseArrayLayer(source.baseLayer()).layerCount(1);
                    region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(target.baseMip()).baseArrayLayer(target.baseLayer()).layerCount(1);
                    region.srcOffsets(0).set(0,0,0);region.srcOffsets(1).set(source.image().width(source.baseMip()),source.image().height(source.baseMip()),1);
                    region.dstOffsets(0).set(0,0,0);region.dstOffsets(1).set(target.image().width(target.baseMip()),target.image().height(target.baseMip()),1);
                    vkCmdBlitImage(pass.command(),source.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            target.image().handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,region,VK_FILTER_LINEAR);
                }
            });
        }
    }

    private void requireLinearBlit(int format){
        if(supported.contains(format))return;
        try(MemoryStack stack=MemoryStack.stackPush()){
            var properties=VkFormatProperties.malloc(stack);vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),format,properties);
            int required=VK_FORMAT_FEATURE_BLIT_SRC_BIT|VK_FORMAT_FEATURE_BLIT_DST_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if((properties.optimalTilingFeatures()&required)!=required)throw new UnsupportedShaderException("Device cannot generate a filtered mip chain for pack image format: "+format);
        }
        supported.add(format);
    }
}
