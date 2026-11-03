package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.*;
import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.VulkanContext;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native Overhaul implementation behind RenderPearl's shared validation/shader frontend. */
public final class OverhaulDeviceBackend implements GpuDeviceBackend,NativePassResources {
    private final Thread owner=Thread.currentThread();
    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final ImmediateCommands commands;
    private final OverhaulCommandQueue queue;
    private final DescriptorPoolCache descriptorPools;
    private final AttachmentPool attachments;
    private final DeviceInfo info;
    private final BackendSmokeProbe smoke;
    private final BufferStorageStatistics bufferStorage=new BufferStorageStatistics();
    private final Set<BackendResource> resources=new LinkedHashSet<>();
    private final List<OverhaulCommandEncoder> encoders=new ArrayList<>();
    private final List<OverhaulSurface> surfaces=new ArrayList<>();
    private final boolean debugging;
    private boolean closing,closed;

    /** Takes ownership of the context, including construction failure cleanup. */
    public OverhaulDeviceBackend(VulkanContext context,GpuDebugOptions debug) {
        this.context=Objects.requireNonNull(context);
        debugging=debug.useValidationLayers()&&VulkanContext.validationAvailable();
        GpuAllocator allocated=null;
        ImmediateCommands lane=null;
        OverhaulCommandQueue work=null;
        try {
            allocated=new GpuAllocator(context);
            lane=new ImmediateCommands(context);
            info=OverhaulDeviceInfo.read(context);
            work=new OverhaulCommandQueue(this);
        } catch (RuntimeException|Error failure) {
            if (work!=null) work.close();
            if (lane!=null) lane.close();
            if (allocated!=null) allocated.close();
            context.close(); throw failure;
        }
        allocator=allocated; commands=lane; queue=work;
        descriptorPools=new DescriptorPoolCache(context);
        attachments=new AttachmentPool(this,64);
        smoke=new BackendSmokeProbe(this);
    }

    public void requireOwner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("Vulkan device accessed outside its owner thread");
        if (closed) throw new IllegalStateException("Vulkan device is closed");
    }
    public void requireOpen() { requireOwner(); if (closing) throw new IllegalStateException("Vulkan device is closing"); }
    void register(BackendResource resource) { requireOpen(); resources.add(resource); }
    void forget(BackendResource resource) { requireOwner(); resources.remove(resource); }
    @Override public VulkanContext context() { requireOwner(); return context; }
    public GpuAllocator allocator() { requireOwner(); return allocator; }
    public ImmediateCommands commands() { requireOwner(); if (queue!=null) queue.flush(); return commands; }
    OverhaulCommandQueue queue() { requireOwner(); return queue; }
    BufferStorageStatistics bufferStorage() { requireOwner(); return bufferStorage; }
    DescriptorPoolCache descriptorPools() { requireOwner(); return descriptorPools; }
    AttachmentPool attachments() { requireOwner(); return attachments; }
    void resourceClosing(BackendResource resource) { requireOwner(); attachments.invalidate(resource); }
    void rendered(int draws) { smoke.rendered(draws); }
    void presented(GpuTextureView source) { smoke.presented(source); }
    java.util.concurrent.CompletableFuture<String> awaitWorldCapture() { requireOpen(); return smoke.awaitWorldCapture(); }
    java.util.concurrent.CompletableFuture<String> awaitWorldCapture(String label) { requireOpen(); return smoke.awaitWorldCapture(label); }
    Map<String,Long> submissionStatistics() {
        long surfaceWaits=0;
        for (var surface:surfaces) surfaceWaits+=surface.blockingWaitCalls();
        return Map.of("queueSubmissions",queue.submissions(),"queuedOperations",queue.operations(),
                "queueFenceWaitCalls",queue.blockingWaitCalls(),"explicitHostWaits",queue.hostWaits(),
                "surfaceFenceWaitCalls",surfaceWaits,"immediateSubmissions",commands.submissionCount(),
                "immediateFenceWaitCalls",commands.fenceWaitCalls());
    }

    @Override public GpuSurfaceBackend createSurface(long window,BooleanSupplier isIconified) {
        requireOpen();
        if (!surfaces.isEmpty()) throw new UnsupportedOperationException("One game surface per device is supported");
        var surface=new OverhaulSurface(this,window,isIconified); surfaces.add(surface); return surface;
    }
    @Override public OverhaulCommandEncoder createCommandEncoder() {
        requireOpen(); var encoder=new OverhaulCommandEncoder(this); encoders.add(encoder); return encoder;
    }
    @Override public GpuSampler createSampler(AddressMode u,AddressMode v,FilterMode min,FilterMode mag,int anisotropy,OptionalDouble maxLod) {
        requireOpen(); return new OverhaulSampler(this,u,v,min,mag,anisotropy,maxLod);
    }
    @Override public GpuTexture createTexture(String label,int usage,GpuFormat format,int width,int height,int layers,int mips) {
        requireOpen(); return new OverhaulTexture(this,label,usage,format,width,height,layers,mips);
    }
    @Override public GpuTextureView createTextureView(GpuTexture texture,int baseMip,int mips) {
        requireOpen(); return new OverhaulTextureView(this,owned(texture,OverhaulTexture.class),baseMip,mips);
    }
    @Override public GpuBuffer createBuffer(Supplier<String> label,int usage,long size) {
        requireOpen(); return new OverhaulBuffer(this,usage,size,null);
    }
    @Override public GpuBuffer createBuffer(Supplier<String> label,int usage,ByteBuffer initial) {
        requireOpen(); return new OverhaulBuffer(this,usage,initial.remaining(),initial);
    }
    @Override public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
        // Frontend invokes this on its compilation executor. Pending copies shader input;
        // native work and ownership registration happen during finishCompile on the render thread.
        return OverhaulPipeline.compile(this,info);
    }
    @Override public GpuQueryPool createTimestampQueryPool(int count) { requireOpen(); return new OverhaulQueryPool(this,count); }
    @Override public long getTimestampCalibrationOffset() { requireOpen(); return OverhaulQueryPool.calibrationOffset(this); }
    @Override public DeviceInfo getDeviceInfo() { return info; }
    @Override public List<String> getLastDebugMessages() { requireOwner(); return context.validationErrors(); }
    @Override public boolean isDebuggingEnabled() { return debugging; }

    private <T extends BackendResource> T owned(Object resource,Class<T> type) {
        requireOwner();
        if (!type.isInstance(resource)||type.cast(resource).owner()!=this) throw new IllegalArgumentException("Foreign backend resource");
        return type.cast(resource);
    }
    @Override public dev.overhaul.vulkan.GpuBuffer buffer(GpuBuffer buffer) { return owned(buffer,OverhaulBuffer.class).nativeBuffer(); }
    public dev.overhaul.vulkan.GpuImage image(GpuTexture texture) { return owned(texture,OverhaulTexture.class).image(); }
    @Override public dev.overhaul.vulkan.GpuImageView texture(GpuTextureView view) { return owned(view,OverhaulTextureView.class).nativeView(); }
    @Override public dev.overhaul.vulkan.GpuSampler sampler(GpuSampler sampler) { return owned(sampler,OverhaulSampler.class).nativeSampler(); }
    @Override public AutoCloseable retain(Object resource) { return owned(resource,BackendResource.class).pin(); }
    @Override public void prepareTimestamp(GpuQueryPool pool,int index) { owned(pool,OverhaulQueryPool.class).prepare(index); }
    @Override public void resetTimestamp(VkCommandBuffer command,GpuQueryPool pool,int index) { owned(pool,OverhaulQueryPool.class).reset(command,index); }
    @Override public long timestampGeneration(GpuQueryPool pool,int index) { return owned(pool,OverhaulQueryPool.class).generation(index); }
    @Override public void timestampSubmitted(GpuQueryPool pool,int index,long generation) { owned(pool,OverhaulQueryPool.class).markSubmitted(index,generation); }
    @Override public void timestamp(VkCommandBuffer command,GpuQueryPool pool,int index) { owned(pool,OverhaulQueryPool.class).write(command,index); }

    @Override public void close() {
        if (closed) return;
        requireOwner();
        if (!closing) {
            queue.drain();
            for (var encoder:encoders) encoder.close();
            for (var surface:surfaces) surface.close();
            queue.close();
            attachments.close(); descriptorPools.close();
            closing=true;
        }
        context.waitIdle();
        var reverse=new ArrayList<>(resources); Collections.reverse(reverse);
        for (var resource:reverse) resource.close();
        if (!resources.isEmpty()) throw new IllegalStateException("Close outstanding resource mapping leases before closing the device");
        smoke.close(); commands.close(); allocator.close(); context.close(); closed=true;
    }
}
