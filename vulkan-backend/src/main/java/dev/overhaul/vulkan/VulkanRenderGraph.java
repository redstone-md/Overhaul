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
 * preserve the declared layout on exit. Imported versions borrow actual image
 * views; physical subresources, rather than logical names, own layout history.
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
        GRAPHICS_SAMPLE(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT,VK_IMAGE_USAGE_SAMPLED_BIT,false),
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

    /** View lifetime identity survives graph wrappers and distinguishes reused native handles. */
    public record Image(long handle, long view, int format, int aspect, int width, int height, Object identity) {
        public Image { Objects.requireNonNull(identity,"identity"); }
        /** Manual contexts have independent lifetime tokens unless an explicit identity is supplied. */
        public Image(long handle,long view,int format,int aspect,int width,int height) {
            this(handle,view,format,aspect,width,height,new Object());
        }
    }
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

    /** What construction proved about a graph of imported images; a graph of the same shape reuses it with new pass callbacks. */
    public record Shape(RenderGraph graph, Map<String,GpuImageView> bindings, Map<String,Image> images) {}

    private final VulkanContext context;
    private final RenderGraph graph;
    private final Map<String,Operation> operations;
    private final Map<String,GpuImage> owned = new LinkedHashMap<>();
    private Map<String,GpuImageView> bindings = new LinkedHashMap<>();
    private Map<String,Image> images = new LinkedHashMap<>();
    private final boolean ownsCommands;
    private GpuAllocator allocator;
    private ImmediateCommands commands;
    private boolean closed;
    private boolean executing;

    public VulkanRenderGraph(VulkanContext context, RenderGraph graph, Map<String,Operation> operations) {
        this(context,graph,operations,Map.of(),null);
    }

    /** Borrowed views and command lanes remain caller-owned, including when construction fails. */
    public VulkanRenderGraph(VulkanContext context,RenderGraph graph,Map<String,Operation> operations,
                             Map<String,GpuImageView> imports,ImmediateCommands borrowedCommands) {
        this.context=Objects.requireNonNull(context,"context");
        this.graph=Objects.requireNonNull(graph,"graph");
        this.operations=Map.copyOf(operations);
        this.ownsCommands=borrowedCommands==null;
        context.requireOwner();
        if(borrowedCommands!=null&&borrowedCommands.context()!=context)throw new IllegalArgumentException("Foreign graph command lane");
        imports=Map.copyOf(imports);
        validateOperations();
        try {
            commands=ownsCommands?new ImmediateCommands(context):borrowedCommands;
            Set<String> expectedImports=new HashSet<>();
            Map<String,Integer> usages=requiredUsages();
            for (RenderGraph.Resource resource:graph.resources().values()) {
                GpuImageView view;
                int flags=usages.getOrDefault(resource.name(),0);
                if(resource.imported()){
                    expectedImports.add(resource.name());view=imports.get(resource.name());
                    if(view==null)throw new IllegalArgumentException("Missing imported graph view: "+resource.name());
                }else{
                    if(allocator==null)allocator=new GpuAllocator(context);
                    GpuImage image=allocator.createImage(ImageFormat.fromVk(format(resource.format())),resource.width(),resource.height(),1,1,
                            flags|VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
                    owned.put(resource.name(),image);view=image.view(0,1,0,1);
                }
                bind(resource,view,flags);
            }
            if(!expectedImports.equals(imports.keySet()))throw new IllegalArgumentException("Unknown imported graph resource");
            validatePhysicalFeedback();
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    /**
     * A graph whose shape an earlier construction validated. The caller guarantees that {@code operations} name the
     * same passes with the same uses; only their callbacks differ.
     */
    VulkanRenderGraph(VulkanContext context,Shape shape,Map<String,Operation> operations,ImmediateCommands borrowedCommands){
        this.context=context;context.requireOwner();
        if(borrowedCommands==null||borrowedCommands.context()!=context)throw new IllegalArgumentException("A reused graph shape borrows its frame's command lane");
        this.graph=shape.graph();this.operations=operations;this.ownsCommands=false;this.commands=borrowedCommands;
        this.bindings=shape.bindings();this.images=shape.images();
    }

    /** The validated shape of a graph that owns no images, for reuse by later graphs of the same shape. */
    Shape shape(){
        requireOpen();
        if(!owned.isEmpty())throw new IllegalStateException("Only graphs of imported images have a reusable shape");
        return new Shape(graph,Map.copyOf(bindings),Map.copyOf(images));
    }

    /** Creation flags per resource in one sweep over the passes; graphs are built every frame. */
    private Map<String,Integer> requiredUsages(){
        Map<String,Integer> flags=new HashMap<>();
        for(Operation operation:operations.values())
            for(Map.Entry<String,Usage> use:operation.uses().entrySet())flags.merge(use.getKey(),use.getValue().creationFlag,(a,b)->a|b);
        return flags;
    }
    private void bind(RenderGraph.Resource resource,GpuImageView view,int flags){
        GpuImage image=view.image();
        if(image.context()!=context)throw new IllegalArgumentException("Foreign graph image: "+resource.name());
        if((view.mipCount()!=1||view.layerCount()!=1)&&flags!=VK_IMAGE_USAGE_SAMPLED_BIT)throw new IllegalArgumentException("Multiple graph subresources require sampling-only usage: "+resource.name());
        if(image.format().vkFormat()!=format(resource.format())||image.width(view.baseMip())!=resource.width()
                ||image.height(view.baseMip())!=resource.height())throw new IllegalArgumentException("Imported image does not match its graph declaration: "+resource.name());
        image.requireUsage(flags);bindings.put(resource.name(),view);
        images.put(resource.name(),new Image(image.handle(),view.handle(),image.format().vkFormat(),image.format().aspectMask(),resource.width(),resource.height(),view));
    }
    private void validatePhysicalFeedback(){
        for(RenderGraph.Pass pass:graph.passes()){
            List<Map.Entry<String,Usage>> uses=new ArrayList<>(operations.get(pass.name()).uses().entrySet());
            for(int first=0;first<uses.size();first++)for(int second=first+1;second<uses.size();second++){
                var a=uses.get(first);var b=uses.get(second);
                if(!a.getValue().writes&&!b.getValue().writes)continue;
                GpuImageView left=bindings.get(a.getKey()),right=bindings.get(b.getKey());
                if(left.image()==right.image()&&left.baseMip()<right.baseMip()+right.mipCount()&&right.baseMip()<left.baseMip()+left.mipCount()
                        &&left.baseLayer()<right.baseLayer()+right.layerCount()&&right.baseLayer()<left.baseLayer()+left.layerCount())
                    throw new IllegalArgumentException("Physical image feedback requires separate ping-pong targets in pass "+pass.name());
            }
        }
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
                boolean depth=ImageFormat.fromVk(format(graph.resources().get(use.getKey()).format())).isDepth();
                if ((usage==Usage.DEPTH_WRITE || usage==Usage.DEPTH_READ) && !depth
                        || usage==Usage.COLOR_WRITE && depth) throw new IllegalArgumentException("Attachment format mismatch: "+use.getKey());
            }
        }
        if (!expected.equals(operations.keySet())) throw new IllegalArgumentException("Unknown native pass operation");
    }

    public void execute() {
        requireOpen();
        if(executing)throw new IllegalStateException("Nested render graph execution");
        executing=true;
        List<GpuImage.LayoutChange> changes=new ArrayList<>();
        try{
            commands.submit(command->recordPasses(command,changes));
            for(GpuImage.LayoutChange change:changes)change.commit();
        }finally{
            try{for(int index=changes.size()-1;index>=0;index--)changes.get(index).close();}
            finally{executing=false;}
        }
    }

    /** Records into an enclosing encoder; its caller owns submission, transaction commit/rollback and imported-resource pins. */
    public void record(VkCommandBuffer command,List<GpuImage.LayoutChange> changes){
        requireOpen();Objects.requireNonNull(changes,"changes");
        if(command.getDevice()!=context.device())throw new IllegalArgumentException("Foreign graph command buffer");
        if(executing)throw new IllegalStateException("Nested render graph recording");
        if(ownsCommands||!owned.isEmpty())throw new IllegalStateException("Inline graphs must borrow their command lane and fence-owned image resources");
        executing=true;try{recordPasses(command,changes,observer);}finally{executing=false;}
    }
    private void recordPasses(VkCommandBuffer command,List<GpuImage.LayoutChange> changes){recordPasses(command,changes,null);}
    private void recordPasses(VkCommandBuffer command,List<GpuImage.LayoutChange> changes,PassObserver watch){
        for(RenderGraph.Pass pass:graph.passes()){
            Operation operation=operations.get(pass.name());Map<String,Image> scoped=new LinkedHashMap<>();
            for(var use:operation.uses().entrySet()){
                transition(command,bindings.get(use.getKey()),use.getValue(),changes);scoped.put(use.getKey(),images.get(use.getKey()));
            }
            operation.record().accept(new PassContext(command,Collections.unmodifiableMap(scoped)));
            if(watch!=null)watch.recorded(command,pass.name());
        }
    }

    /** Told after each pass a graph records into an enclosing encoder; a profiling hook, null when nothing listens. */
    public interface PassObserver{void recorded(VkCommandBuffer command,String pass);}
    private static volatile PassObserver observer;
    public static void observe(PassObserver listener){observer=listener;}

    /** Readback is a separate synchronized submission; returned bytes have Java ownership. */
    public byte[] readRgba8(String name) {
        requireOpen();
        if(executing)throw new IllegalStateException("Readback during render graph execution");
        Image image=images.get(name);
        GpuImageView view=bindings.get(name);
        if (image==null || image.format()!=VK_FORMAT_R8G8B8A8_UNORM || view.image().layout(view.baseMip(),view.baseLayer())==VK_IMAGE_LAYOUT_UNDEFINED) {
            throw new IllegalArgumentException("Readback requires a defined RGBA8 resource");
        }
        int size=Math.multiplyExact(Math.multiplyExact(image.width(),image.height()),4);
        try (GpuAllocator allocator=new GpuAllocator(context);
             GpuBuffer buffer=allocator.hostBuffer(size,VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            List<GpuImage.LayoutChange> changes=new ArrayList<>();
            try{
              commands.submit(command->{
                transition(command,view,Usage.TRANSFER_READ,changes);
                try (MemoryStack stack=VulkanStack.push()) {
                    VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,stack);
                    copy.imageSubresource().aspectMask(image.aspect()).mipLevel(view.baseMip()).baseArrayLayer(view.baseLayer()).layerCount(1);
                    copy.imageExtent().set(image.width(),image.height(),1);
                    vkCmdCopyImageToBuffer(command,image.handle(),Usage.TRANSFER_READ.layout,buffer.handle(),copy);
                    VkMemoryBarrier.Buffer barrier=VkMemoryBarrier.calloc(1,stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,barrier,null,null);
                }
              });
              for(GpuImage.LayoutChange change:changes)change.commit();
            }finally{for(int index=changes.size()-1;index>=0;index--)changes.get(index).close();}
            byte[] bytes=new byte[size]; buffer.read().get(bytes); return bytes;
        }
    }

    private static void transition(VkCommandBuffer command,GpuImageView view,Usage next,List<GpuImage.LayoutChange> changes) {
        GpuImage image=view.image();view.handle();
        boolean changed=next.writes;
        for(int layer=view.baseLayer();layer<view.baseLayer()+view.layerCount();layer++)for(int mip=view.baseMip();mip<view.baseMip()+view.mipCount();mip++){
            int previous=image.layout(mip,layer);
            if(previous==VK_IMAGE_LAYOUT_UNDEFINED&&!next.writes)throw new IllegalStateException("Graph reads an uninitialized physical image");
            changed|=previous!=next.layout;
        }
        if(changed)changes.add(image.recordTransition(command,view.baseMip(),view.mipCount(),view.baseLayer(),view.layerCount(),next.layout));
    }

    private static int format(RenderGraph.Format format) {
        return GraphImageFormats.image(format).vkFormat();
    }

    private void requireOpen() {
        context.requireOwner();
        if (closed) throw new IllegalStateException("Render graph is closed");
    }

    @Override public void close() {
        if (closed) return;
        context.requireOwner();
        if(executing)throw new IllegalStateException("Cannot close an executing render graph");
        List<GpuImage> reverse=new ArrayList<>(owned.values()); Collections.reverse(reverse);
        for (GpuImage image:reverse) image.close();
        owned.clear(); images=Map.of(); bindings=Map.of();
        if(allocator!=null)allocator.close();
        if (ownsCommands&&commands!=null) commands.close();
        closed=true;
    }
}
