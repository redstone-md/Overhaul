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
}
