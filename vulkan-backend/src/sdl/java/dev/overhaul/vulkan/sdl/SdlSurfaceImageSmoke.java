package dev.overhaul.vulkan.sdl;

import dev.overhaul.vulkan.*;
import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Exercises the encoder/surface boundary: acquire now, complete blit now, present later. */
final class SdlSurfaceImageSmoke {
    private SdlSurfaceImageSmoke(){ }
    static void verify(VulkanContext context,SwapchainPresenter presenter){
        int usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        try(GpuAllocator allocator=new GpuAllocator(context);ImmediateCommands commands=new ImmediateCommands(context);
            GpuImage image=allocator.createImage(ImageFormat.RGBA8_UNORM,16,16,1,1,usage);
            GpuImageView view=image.view(0,1,0,1)){
            ByteBuffer pixels=ByteBuffer.allocate(16*16*4);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)pixels.put((byte)(x*16)).put((byte)(y*16)).put((byte)128).put((byte)255);
            pixels.flip();commands.upload(image,ImageRegion.whole(image,0,0),pixels,0);
            if(!pixels.equals(commands.readback(image,ImageRegion.whole(image,0,0),0)))throw new IllegalStateException("surface source image readback differs");
            for(int i=0;i<12;i++){
                org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
                if(!presenter.acquireNextTexture())throw new IllegalStateException("split surface acquire failed");
                expectState(presenter::acquireNextTexture);
                presenter.blitAcquiredImage(view);
                if(image.layout(0,0)!=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)throw new IllegalStateException("surface blit lost source layout");
                if(!presenter.presentAcquired())throw new IllegalStateException("split surface present failed");
            }
            expectState(presenter::presentAcquired);
            for(int i=0;i<4;i++)if(!presenter.present(view))throw new IllegalStateException("generic image present failed");
            if(!presenter.acquireNextTexture())throw new IllegalStateException("abandoned acquire fixture failed");
            presenter.resize(128,128); // Must consume the acquisition before replacing its swapchain.
            if(!presenter.present(view))throw new IllegalStateException("surface did not recover from unfilled acquired image");
            System.out.println("SDL image surface: real split acquire/blit/present12 frames + generic4, abandoned acquire/resize verified");
        }
    }
    private static void expectState(Runnable action){
        try{action.run();throw new IllegalArgumentException("invalid surface state accepted");}catch(IllegalStateException expected){ }
    }

    /** Runs last: closes the presenter while the final asynchronous blit is still owned by its frame ring. */
    static void verifyAsyncAndClose(VulkanContext context,SwapchainPresenter presenter,long window){
        int usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        java.util.List<FrameRing.Submission> receipts=new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger retired=new java.util.concurrent.atomic.AtomicInteger();
        try(GpuAllocator allocator=new GpuAllocator(context);ImmediateCommands commands=new ImmediateCommands(context);
            GpuImage image=allocator.createImage(ImageFormat.RGBA8_UNORM,16,16,1,1,usage);
            GpuImageView view=image.view(0,1,0,1)){
            commands.clearColor(image,0,0,1,.5f,.25f,1);
            for(int i=0;i<24;i++){
                org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();
                if(!presenter.acquireNextTexture())throw new IllegalStateException("async surface acquire failed");
                FrameRing.Submission completion=presenter.blitAcquiredImageAsync(view,(i&1)!=0);
                completion.retireAfterFence(retired::incrementAndGet);receipts.add(completion);
                if(!presenter.presentAcquired())throw new IllegalStateException("async surface present failed");
            }
            for(FrameRing.Submission receipt:receipts)if(!receipt.await(1_000_000_000L))throw new IllegalStateException("async surface fence did not complete");
            if(retired.get()!=24||!receipts.get(0).await(0))throw new IllegalStateException("ring-wrap retirement differs");
            if(!presenter.acquireNextTexture())throw new IllegalStateException("source lifetime acquire failed");
            FrameRing.Submission pinned=presenter.blitAcquiredImageAsync(view);
            image.close(); // Must wait for the queued blit before destroying its native storage and child view.
            if(!pinned.await(0)||!presenter.presentAcquired())throw new IllegalStateException("source image was not safely fence-pinned");
            try(GpuImage second=allocator.createImage(ImageFormat.RGBA8_UNORM,16,16,1,1,usage);
                GpuImageView secondView=second.view(0,1,0,1)){
                commands.clearColor(second,0,0,.25f,.5f,1,1);
                if(!presenter.acquireNextTexture())throw new IllegalStateException("abandoned async acquire failed");
                FrameRing.Submission abandoned=presenter.blitAcquiredImageAsync(secondView);
                if(!org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(window,160,144))throw new IllegalStateException("async resize failed");
                org.lwjgl.sdl.SDLEvents.SDL_PumpEvents();presenter.resize(160,144);
                if(!abandoned.await(0))throw new IllegalStateException("resize left a blit pending");
                if(!presenter.acquireNextTexture())throw new IllegalStateException("resize recovery acquire failed");
                presenter.resize(160,144); // Also abandon an acquire whose semaphore has not been consumed yet.
                if(!presenter.acquireNextTexture())throw new IllegalStateException("close fixture acquire failed");
                FrameRing.Submission last=presenter.blitAcquiredImageAsync(secondView);
                last.retireAfterFence(retired::incrementAndGet);
                presenter.close();
                if(!last.await(0)||retired.get()!=25)throw new IllegalStateException("presenter close did not drain final blit/retirement");
            }
        }
        System.out.println("SDL asynchronous split: 24 frames/3 acquire semaphores, timed fences, image pinning, resize/abandon/close verified");
    }
}
