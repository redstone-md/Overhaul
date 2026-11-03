package dev.overhaul.vulkan;

import dev.overhaul.shader.graph.RenderGraph;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Executes a versioned graph with real owned images and explicit access barriers.
 * Callbacks record commands, not resource transitions. Render-pass attachments must
 * preserve the declared layout on exit. This synchronous executor is also useful
 * for validating passes before integrating them with a production frame ring.
 */
public final class VulkanRenderGraph implements AutoCloseable {
    public enum Usage {
        COLOR_WRITE(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, true),
        DEPTH_WRITE(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, true),
        DEPTH_READ(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, false),
        FRAGMENT_SAMPLE(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_USAGE_SAMPLED_BIT, false),
        COMPUTE_SAMPLE(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_USAGE_SAMPLED_BIT, false),
        TRANSFER_WRITE(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_USAGE_TRANSFER_DST_BIT, true),
        TRANSFER_READ(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_USAGE_TRANSFER_SRC_BIT, false);

        final int layout, stage, access, creationFlag;
        final boolean writes;
        Usage(int layout, int stage, int access, int creationFlag, boolean writes) {
            this.layout=layout; this.stage=stage; this.access=access; this.creationFlag=creationFlag; this.writes=writes;
        }
        public int layout() { return layout; }
    }

    public record Image(long handle, long view, int format, int aspect, int width, int height) {}
    public record PassContext(VkCommandBuffer command, Map<String, Image> images) {
        public Image image(String name) {
            Image image=images.get(name);
            if (image==null) throw new IllegalArgumentException("Pass did not declare image: "+name);
            return image;
        }
    }
    public record Operation(Map<String, Usage> uses, Consumer<PassContext> record) {
        public Operation { uses=Map.copyOf(uses); Objects.requireNonNull(record,"record"); }
    }

    private final VulkanContext context;
    private final RenderGraph graph;
    private final Map<String,Operation> operations;
    private final Map<String,AttachmentImage> owned = new LinkedHashMap<>();
    private final Map<String,Image> images = new LinkedHashMap<>();
    private final Map<String,Usage> states = new HashMap<>();
    private ImmediateCommands commands;
    private boolean closed;

    public VulkanRenderGraph(VulkanContext context, RenderGraph graph, Map<String,Operation> operations) {
        this.context=Objects.requireNonNull(context,"context");
        this.graph=Objects.requireNonNull(graph,"graph");
        this.operations=Map.copyOf(operations);
        validateOperations();
        try {
            commands=new ImmediateCommands(context);
            for (RenderGraph.Resource resource:graph.resources().values()) {
                if (resource.imported()) throw new IllegalArgumentException("Borrowed images require an explicit ownership bridge: "+resource.name());
                int format=format(resource.format()), aspect=resource.format()==RenderGraph.Format.DEPTH32F
                        ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
                int flags=VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                for (Operation operation:this.operations.values()) {
                    Usage usage=operation.uses().get(resource.name());
                    if (usage!=null) flags|=usage.creationFlag;
                }
                AttachmentImage image=new AttachmentImage(context,resource.width(),resource.height(),format,flags,aspect);
                owned.put(resource.name(),image);
                images.put(resource.name(),new Image(image.image,image.view,format,aspect,resource.width(),resource.height()));
            }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private void validateOperations() {
        Set<String> expected=new HashSet<>();
        for (RenderGraph.Pass pass:graph.passes()) {
            expected.add(pass.name());
            Operation operation=operations.get(pass.name());
            if (operation==null) throw new IllegalArgumentException("Missing native pass: "+pass.name());
            Set<String> declared=new HashSet<>(pass.reads()); declared.addAll(pass.writes());
            if (!declared.equals(operation.uses().keySet())) throw new IllegalArgumentException("Pass access differs from graph: "+pass.name());
            for (Map.Entry<String,Usage> use:operation.uses().entrySet()) {
                Usage usage=use.getValue();
                if (usage==Usage.COMPUTE_SAMPLE && !context.supportsCompute()) {
                    throw new IllegalArgumentException("Selected queue does not support compute shader access");
                }
                if (usage.writes!=pass.writes().contains(use.getKey())) throw new IllegalArgumentException("Read/write usage mismatch: "+use.getKey());
                boolean depth=graph.resources().get(use.getKey()).format()==RenderGraph.Format.DEPTH32F;
                if ((usage==Usage.DEPTH_WRITE || usage==Usage.DEPTH_READ) && !depth
                        || usage==Usage.COLOR_WRITE && depth) throw new IllegalArgumentException("Attachment format mismatch: "+use.getKey());
            }
        }
        if (!expected.equals(operations.keySet())) throw new IllegalArgumentException("Unknown native pass operation");
    }

    public void execute() {
        requireOpen();
        Map<String,Usage> next=new HashMap<>(states);
        commands.submit(command->{
            for (RenderGraph.Pass pass:graph.passes()) {
                Operation operation=operations.get(pass.name());
                Map<String,Image> scoped=new LinkedHashMap<>();
                for (Map.Entry<String,Usage> use:operation.uses().entrySet()) {
                    Image image=images.get(use.getKey());
                    transition(command,image,next.get(use.getKey()),use.getValue());
                    next.put(use.getKey(),use.getValue());
                    scoped.put(use.getKey(),image);
                }
                operation.record().accept(new PassContext(command,Map.copyOf(scoped)));
            }
        });
        states.clear(); states.putAll(next);
    }

    /** Readback is a separate synchronized submission; returned bytes have Java ownership. */
    public byte[] readRgba8(String name) {
        requireOpen();
        Image image=images.get(name);
        if (image==null || image.format()!=VK_FORMAT_R8G8B8A8_UNORM || !states.containsKey(name)) {
            throw new IllegalArgumentException("Readback requires a defined RGBA8 resource");
        }
        int size=Math.multiplyExact(Math.multiplyExact(image.width(),image.height()),4);
        try (GpuAllocator allocator=new GpuAllocator(context);
             GpuBuffer buffer=allocator.hostBuffer(size,VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            commands.submit(command->{
                transition(command,image,states.get(name),Usage.TRANSFER_READ);
                try (MemoryStack stack=MemoryStack.stackPush()) {
                    VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,stack);
                    copy.imageSubresource().aspectMask(image.aspect()).layerCount(1);
                    copy.imageExtent().set(image.width(),image.height(),1);
                    vkCmdCopyImageToBuffer(command,image.handle(),Usage.TRANSFER_READ.layout,buffer.handle(),copy);
                    VkMemoryBarrier.Buffer barrier=VkMemoryBarrier.calloc(1,stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,barrier,null,null);
                }
            });
            states.put(name,Usage.TRANSFER_READ);
            byte[] bytes=new byte[size]; buffer.read().get(bytes); return bytes;
        }
    }

    private static void transition(VkCommandBuffer command,Image image,Usage previous,Usage next) {
        if (previous==next && !next.writes) return;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default()
                    .oldLayout(previous==null ? VK_IMAGE_LAYOUT_UNDEFINED : previous.layout).newLayout(next.layout)
                    .srcAccessMask(previous==null ? 0 : previous.access).dstAccessMask(next.access)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.handle());
            barrier.subresourceRange().aspectMask(image.aspect()).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command,previous==null ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : previous.stage,
                    next.stage,0,null,null,barrier);
        }
    }

    private static int format(RenderGraph.Format format) {
        return switch (format) {
            case RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32F -> VK_FORMAT_R32_SFLOAT;
            case DEPTH32F -> VK_FORMAT_D32_SFLOAT;
        };
    }

    private void requireOpen() {
        context.requireOwner();
        if (closed) throw new IllegalStateException("Render graph is closed");
    }

    @Override public void close() {
        if (closed) return;
        context.requireOwner(); context.waitIdle();
        List<AttachmentImage> reverse=new ArrayList<>(owned.values()); Collections.reverse(reverse);
        for (AttachmentImage image:reverse) image.close();
        owned.clear(); images.clear(); states.clear();
        if (commands!=null) commands.close();
        closed=true;
    }
}
