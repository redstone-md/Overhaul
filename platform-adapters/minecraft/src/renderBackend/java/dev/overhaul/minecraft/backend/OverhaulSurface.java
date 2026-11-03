package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.overhaul.vulkan.SdlSurfaceProvider;
import dev.overhaul.vulkan.SwapchainPresenter;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Borrowed SDL window, own Vulkan swapchain and faithful acquire/copy/present lifecycle. */
public final class OverhaulSurface extends BackendResource implements GpuSurfaceBackend {
    private final BooleanSupplier isIconified;
    private SwapchainPresenter presenter;
    private long retiredWaitCalls;
    private boolean acquired,blitted;

    public OverhaulSurface(OverhaulDeviceBackend owner,long window,BooleanSupplier isIconified) {
        super(owner);
        try {
            this.isIconified=Objects.requireNonNull(isIconified,"isIconified");
            owner.context().attachSurface(new SdlSurfaceProvider(window));
        }
        catch (RuntimeException|Error failure) { close(); throw failure; }
    }

    @Override public void configure(GpuSurface.Configuration configuration) throws SurfaceException {
        requireOpen();
        if (acquired) throw new IllegalStateException("Cannot configure while a surface image is acquired");
        if (configuration.presentMode()!=GpuSurface.PresentMode.FIFO) throw new SurfaceException("Only FIFO presentation is currently supported");
        try {
            if (presenter==null) presenter=new SwapchainPresenter(owner().context(),configuration.width(),configuration.height());
            else presenter.resize(configuration.width(),configuration.height());
        } catch (RuntimeException error) { throw new SurfaceException(error); }
    }

    @Override public boolean isSuboptimal() {
        requireOpen(); return presenter==null || presenter.needsResize();
    }

    @Override public void acquireNextTexture() throws SurfaceException {
        requireOpen();
        owner().queue().poll();
        if (acquired) throw new IllegalStateException("A surface image is already acquired");
        if (presenter==null) throw new SurfaceException("Configure the surface before acquisition");
        if (isIconified.getAsBoolean()) throw new SurfaceException("The window is iconified");
        try {
            if (!presenter.acquireNextTexture()) throw new SurfaceException("Vulkan surface acquisition needs reconfiguration");
            acquired=true; blitted=false;
        } catch (RuntimeException error) { throw new SurfaceException(error); }
    }

    @Override public void blitFromTexture(CommandEncoderBackend encoder,GpuTextureView texture) {
        requireOpen();
        if (!(encoder instanceof OverhaulCommandEncoder own) || own.owner()!=owner()) throw new IllegalArgumentException("Foreign command encoder");
        own.requireNoPass();
        if (!acquired || blitted) throw new IllegalStateException("Acquire one surface image before blitting");
        BackendPins pins=new BackendPins(owner(),texture,texture.texture());
        boolean transferred=false;
        try {
            owner().queue().flush();
            // The game fence created after this call includes this real asynchronous blit.
            var completion=presenter.blitAcquiredImageAsync(owner().texture(texture),true);
            owner().queue().submittedExternal(completion);
            completion.retireAfterFence(pins); transferred=true;
            owner().presented(texture);
            blitted=true;
        } finally { if (!transferred) pins.close(); }
    }

    @Override public void present() {
        requireOpen();
        if (!acquired || !blitted) throw new IllegalStateException("A surface image must be acquired and copied before presentation");
        try { presenter.presentAcquired(); }
        finally { acquired=false; blitted=false; }
    }

    @Override public Collection<GpuSurface.PresentMode> supportedPresentModes() { return List.of(GpuSurface.PresentMode.FIFO); }
    long blockingWaitCalls() { return retiredWaitCalls+(presenter==null?0:presenter.blockingWaitCalls()); }

    @Override protected void destroyNative() {
        if (presenter!=null) {
            presenter.close(); retiredWaitCalls+=presenter.blockingWaitCalls(); presenter=null;
        }
    }
}
