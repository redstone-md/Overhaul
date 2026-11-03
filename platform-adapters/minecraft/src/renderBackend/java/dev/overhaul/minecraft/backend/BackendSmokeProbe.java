package dev.overhaul.minecraft.backend;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImageRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in evidence capture from the actual image copied into the game's swapchain. */
final class BackendSmokeProbe {
    private final OverhaulDeviceBackend owner;
    private final Path directory;
    private final java.util.Set<String> capturedScenes=new java.util.HashSet<>();
    private final java.util.concurrent.CompletableFuture<String> worldCapture=new java.util.concurrent.CompletableFuture<>();
    private long frames,passes,draws,sceneFrames;
    private String scene,pendingScene,lastScreen,lastOverlay,capturedScreen,capturedOverlay;
    private int width,height,distinctPixels;
    private boolean captured,worldRequested;

    BackendSmokeProbe(OverhaulDeviceBackend owner) {
        this.owner=owner;
        String destination=System.getProperty("overhaul.backend.smokeDirectory");
        directory=destination==null?null:Path.of(destination).toAbsolutePath().normalize();
    }
    void rendered(int draws) { passes++; this.draws+=draws; }
    void presented(GpuTextureView source) {
        frames++;
        if (directory==null) return;
        Minecraft minecraft=Minecraft.getInstance();
        var screen=minecraft.gui.screen();
        var overlay=minecraft.gui.overlay();
        lastScreen=screen==null?"none":screen.getClass().getName();
        lastOverlay=overlay==null?"none":overlay.getClass().getName();
        boolean world=minecraft.level!=null;
        if (world&&!worldRequested) return;
        boolean title=screen instanceof TitleScreen;
        if (!minecraft.isGameLoadFinished()||overlay!=null||(world?(minecraft.player==null||screen!=null):screen==null)) { sceneFrames=0; return; }
        String nextScene=world?"world":title?"title":"screen";
        if (capturedScenes.contains(nextScene)) return;
        if (!nextScene.equals(pendingScene)) { pendingScene=nextScene; sceneFrames=0; }
        if (++sceneFrames<100) return;
        scene=nextScene;
        capturedScreen=lastScreen; capturedOverlay=lastOverlay;
        var view=owner.texture(source);
        if (view.image().format()!=ImageFormat.RGBA8_UNORM||view.layerCount()!=1) {
            throw new IllegalStateException("Backend smoke expects the game's RGBA8 surface source");
        }
        width=view.image().width(view.baseMip()); height=view.image().height(view.baseMip());
        var pixels=owner.commands().readback(view.image(),ImageRegion.whole(view.image(),view.baseMip(),view.baseLayer()),0);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        java.util.Set<Integer> colors=new java.util.HashSet<>();
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            int offset=(y*width+x)*4;
            int argb=((pixels.get(offset+3)&255)<<24)|((pixels.get(offset)&255)<<16)
                    |((pixels.get(offset+1)&255)<<8)|(pixels.get(offset+2)&255);
            // Apply the same Y inversion as the native surface blit.
            image.setRGB(x,height-1-y,argb);
            if (colors.size()<4096) colors.add(argb);
        }
        distinctPixels=colors.size();
        if (distinctPixels<16) throw new IllegalStateException("Backend smoke image is blank or has insufficient scene detail");
        try {
            Files.createDirectories(directory);
            Path output=directory.resolve("backend-"+scene+".png");
            if (!ImageIO.write(image,"png",output.toFile())) throw new IOException("PNG writer unavailable");
            var decoded=ImageIO.read(output.toFile());
            if (decoded==null||decoded.getWidth()!=width||decoded.getHeight()!=height) throw new IOException("PNG verification failed");
            captured=true;
            capturedScenes.add(scene);
            writeReceipt(false);
            if (world) worldCapture.complete("backend=Overhaul Vulkan;scene=world;frames="+frames+";draws="+draws+";image="+output.getFileName());
        } catch (IOException failure) { throw new IllegalStateException("Cannot save backend smoke evidence",failure); }
    }
    java.util.concurrent.CompletableFuture<String> awaitWorldCapture() {
        if (directory==null) throw new IllegalStateException("Enable backend smoke capture before requesting world evidence");
        worldRequested=true;
        return worldCapture.copy();
    }
    void close() {
        if (directory!=null) writeReceipt(true);
        if (!worldCapture.isDone()) worldCapture.completeExceptionally(new IllegalStateException("Device closed before world capture"));
    }
    private void writeReceipt(boolean closed) {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("status",captured&&draws>0&&frames>0?"PASS":"INCOMPLETE");
        report.put("backend",owner.getDeviceInfo().backendName()); report.put("device",owner.getDeviceInfo().name());
        report.put("scene",scene); report.put("frames",frames); report.put("passes",passes); report.put("draws",draws);
        report.put("screen",capturedScreen); report.put("overlay",capturedOverlay);
        report.put("lastScreen",lastScreen); report.put("lastOverlay",lastOverlay);
        report.put("width",width); report.put("height",height); report.put("distinctPixelsAtLeast",distinctPixels);
        report.put("captured",captured); report.put("closed",closed);
        report.put("capture","GPU surface source with presentation Y flip applied");
        report.put("submissionStatistics",owner.submissionStatistics());
        report.put("bufferStorageStatistics",owner.bufferStorage().snapshot());
        report.put("descriptorPoolStatistics",owner.descriptorPools().stats());
        report.put("attachmentPoolStatistics",owner.attachments().statistics());
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("receipt.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (IOException failure) { throw new IllegalStateException("Cannot save backend smoke receipt",failure); }
    }
}
