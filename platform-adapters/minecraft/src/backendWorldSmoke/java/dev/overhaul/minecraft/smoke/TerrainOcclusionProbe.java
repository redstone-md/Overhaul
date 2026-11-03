package dev.overhaul.minecraft.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.terrain.TerrainCullingAccess;
import dev.overhaul.minecraft.terrain.TerrainCullingController;
import dev.overhaul.minecraft.terrain.TerrainGeometryAccess;
import dev.overhaul.minecraft.terrain.TerrainSectionAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Requires normal draw-group rejection and restoration, then captures the real GPU surface. */
final class TerrainOcclusionProbe implements WorldSmokeProbe {
    private enum Phase { EDIT,CAPTURE,DONE }
    private static final String[] LABELS={"occlusion-wall","occlusion-open","occlusion-closed","occlusion-camera"};
    private static final long TARGET_SECTION=SectionPos.asLong(0,1,1);
    private final Thread owner=Thread.currentThread();
    private final OcclusionFixtures fixtures=new OcclusionFixtures();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final List<Map<String,Object>> checkpoints=new ArrayList<>();
    private Minecraft minecraft;
    private OverhaulGpuDevice device;
    private TerrainCullingController culling;
    private CompletableFuture<?> work;
    private Path receipt;
    private Phase phase=Phase.EDIT;
    private int stage;
    private long barrier,baselineFrame,lastProgress;
    private long nextDiagnostic;
    private String failure="";

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey ignored) {
        requireOwner();this.minecraft=minecraft;device=(OverhaulGpuDevice)RenderSystem.getDevice();
        culling=((TerrainCullingAccess)minecraft.levelRenderer).overhaul$culling();
        for(var position:OcclusionFixtures.SECTIONS)culling.watch(OcclusionFixtures.sectionNode(position));
        receipt=minecraft.gameDirectory.toPath().resolve("overhaul-occlusion-smoke-latest.json");
        begin(0);return result.copy();
    }
    private void begin(int next) {
        stage=next;barrier=0;baselineFrame=number(culling.observation(TARGET_SECTION),"frame");
        work=fixtures.place(minecraft.getSingleplayerServer(),minecraft.player.getUUID(),stage);
        phase=Phase.EDIT;lastProgress=System.nanoTime();writeReceipt("RUNNING");
    }
    @Override public void tick() {
        requireOwner();if(result.isDone())return;
        try {
            if(System.nanoTime()>=nextDiagnostic){writeReceipt("RUNNING");nextDiagnostic=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);}
            if(System.nanoTime()-lastProgress>TimeUnit.SECONDS.toNanos(40))
                throw new IllegalStateException("Occlusion smoke stalled at "+LABELS[stage]+"/"+phase+"; "+culling.snapshot()+"; target="+culling.observation(TARGET_SECTION)+"; readiness="+readiness());
            if(phase==Phase.EDIT&&work.isDone()) {
                work.join();if(!ready())return;
                work=device.awaitWorldCapture(LABELS[stage]);phase=Phase.CAPTURE;lastProgress=System.nanoTime();
            } else if(phase==Phase.CAPTURE&&work.isDone()) {
                String image=work.join().toString();
                if(!ready())throw new IllegalStateException("Occlusion state changed during GPU capture: "+culling.observation(TARGET_SECTION));
                record(image);
                if(stage<3)begin(stage+1);
                else {phase=Phase.DONE;writeReceipt("CAPTURED");result.complete("occlusionStages=4;"+image);}
            }
        }catch(RuntimeException problem){failure=problem.toString();result.completeExceptionally(problem);writeReceipt("FAIL");throw problem;}
    }
    private boolean ready() {
        if(minecraft.level==null||!fixtures.matches(minecraft.level,stage))return false;
        double expectedX=stage==3?28:8;
        if(Math.abs(minecraft.player.getX()-expectedX)>0.05||Math.abs(minecraft.player.getY()-20)>0.05)return false;
        if(barrier==0) {
            barrier=Util.getNanos();baselineFrame=number(culling.observation(TARGET_SECTION),"frame");
            for(var position:OcclusionFixtures.SECTIONS)minecraft.level.setSectionDirtyWithNeighbors(
                    SectionPos.blockToSectionCoord(position.getX()),1,SectionPos.blockToSectionCoord(position.getZ()));
            return false;
        }
        for(var position:OcclusionFixtures.SECTIONS) {
            var section=((TerrainSectionAccess)minecraft.levelRenderer).overhaul$section(position);
            var mesh=section==null?null:section.getSectionMesh();
            if(mesh==null||section.getSectionNode()!=OcclusionFixtures.sectionNode(position)||mesh.getCompileTaskStartTime()<=barrier||!mesh.hasRenderableLayers())return false;
        }
        var mesh=target();
        if(!(mesh instanceof TerrainGeometryAccess access)||access.overhaul$geometry()==null||!access.overhaul$geometry().hasBounds())return false;
        var observed=culling.observation(TARGET_SECTION);
        return Boolean.TRUE.equals(observed.get("eligible"))&&Boolean.TRUE.equals(observed.get("coherent"))
                &&number(observed,"frame")>baselineFrame&&number(observed,"lastTestedFrame")==number(observed,"frame")
                &&number(observed,"frame")==number(culling.snapshot(),"frames")
                &&number(observed,"lastMeshStart")==mesh.getCompileTaskStartTime()
                &&Boolean.valueOf(stage==0||stage==2).equals(observed.get("lastCulled"));
    }
    private SectionMesh target() {
        var section=((TerrainSectionAccess)minecraft.levelRenderer).overhaul$section(OcclusionFixtures.TARGET);
        return section==null||section.getSectionNode()!=TARGET_SECTION?null:section.getSectionMesh();
    }
    private void record(String image) {
        var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("stage",LABELS[stage]);checkpoint.put("image",image);
        checkpoint.put("expectedCulled",stage==0||stage==2);checkpoint.put("finalClientStateBarrier",barrier);
        checkpoint.put("targetCompileStart",target().getCompileTaskStartTime());checkpoint.put("baselineFrame",baselineFrame);
        checkpoint.put("observation",culling.observation(TARGET_SECTION));checkpoint.put("culling",culling.snapshot());
        checkpoint.put("sections",sectionDetails());
        checkpoint.put("camera",List.of(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ()));
        checkpoints.add(Map.copyOf(checkpoint));writeReceipt("RUNNING");
        java.util.logging.Logger.getLogger("overhaul").info("Overhaul captured "+LABELS[stage]+"; "+culling.observation(TARGET_SECTION));
    }
    private List<Map<String,Object>> sectionDetails() {
        var details=new ArrayList<Map<String,Object>>();
        for(var position:OcclusionFixtures.SECTIONS) {
            var section=((TerrainSectionAccess)minecraft.levelRenderer).overhaul$section(position);
            var geometry=((TerrainGeometryAccess)section.getSectionMesh()).overhaul$geometry();
            var detail=new LinkedHashMap<String,Object>();detail.put("section",section.getSectionNode());
            detail.put("origin",section.getRenderOrigin().toString());detail.put("observation",culling.observation(section.getSectionNode()));
            detail.put("faces",geometry.faceCount());detail.put("bounds",List.of(geometry.min(0),geometry.min(1),geometry.min(2),geometry.max(0),geometry.max(1),geometry.max(2)));
            details.add(Map.copyOf(detail));
        }
        return details;
    }
    @Override public void disconnected() {
        requireOwner();if(checkpoints.size()!=4||!result.isDone()||result.isCompletedExceptionally())throw new IllegalStateException("Occlusion scenarios are incomplete");
        writeReceipt("PASS");
    }
    private void writeReceipt(String status) {
        var report=new LinkedHashMap<String,Object>();report.put("status",status);report.put("phase",phase.toString());report.put("stages",checkpoints);
        report.put("failure",failure);report.put("targetSection",TARGET_SECTION);report.put("culling",culling.snapshot());
        report.put("observation",culling.observation(TARGET_SECTION));report.put("finalClientStateBarrier",barrier);
        report.put("readiness",readiness());
        try{Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(IOException problem){throw new IllegalStateException("Cannot write occlusion receipt",problem);}
    }
    private Map<String,Object> readiness() {
        if(minecraft.level==null||minecraft.player==null)return Map.of("level","absent");
        var result=new LinkedHashMap<String,Object>();result.put("clientMatches",fixtures.matches(minecraft.level,stage));
        result.put("camera",List.of(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ()));
        result.put("rotation",List.of(minecraft.player.getYRot(),minecraft.player.getXRot()));
        var installed=new ArrayList<Map<String,Object>>();
        for(var position:OcclusionFixtures.SECTIONS) {
            var section=((TerrainSectionAccess)minecraft.levelRenderer).overhaul$section(position);var mesh=section==null?null:section.getSectionMesh();
            var details=new LinkedHashMap<String,Object>();details.put("expectedNode",OcclusionFixtures.sectionNode(position));
            details.put("node",section==null?"absent":section.getSectionNode());details.put("compileStart",mesh==null?0:mesh.getCompileTaskStartTime());
            details.put("hasLayers",mesh!=null&&mesh.hasRenderableLayers());installed.add(details);
        }
        result.put("installed",installed);return result;
    }
    private static long number(Map<String,Object> values,String key){return((Number)values.get(key)).longValue();}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Occlusion smoke requires the client owner");}
    @Override public void close(){requireOwner();if(!result.isDone())result.completeExceptionally(new IllegalStateException("Occlusion smoke closed before completion"));}
}
