package dev.overhaul.minecraft.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.terrain.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** End-to-end mutations exercise ordinary worker compilation, installed meshes, reload and native presentation. */
public final class TerrainWorldProbe implements WorldSmokeProbe {
    private enum Phase { INITIAL,EDIT,CAPTURE,RELOAD,DONE }
    private final Thread owner=Thread.currentThread();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final TerrainFixtures fixtures=new TerrainFixtures();
    private final List<Map<String,Object>> checkpoints=new ArrayList<>();
    private final List<OverhaulSectionCompiler> retired=new ArrayList<>();
    private final long[] sections={SectionPos.asLong(0,0,0),SectionPos.asLong(1,0,0)};
    private final long[] beforeCounts=new long[2];
    private final SectionMesh[] beforeMeshes=new SectionMesh[2];
    private Minecraft minecraft;
    private OverhaulGpuDevice device;
    private TerrainDispatcherAccess dispatcher;
    private OverhaulSectionCompiler compiler;
    private CompletableFuture<?> work;
    private Path receipt;
    private Phase phase=Phase.INITIAL;
    private int stage;
    private String imageEvidence="";
    private long lastProgress=System.nanoTime();
    private long finalStateBarrier=-1;
    private boolean invalidationRequested;

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey section) {
        requireOwner();
        this.minecraft=minecraft;
        if(!(RenderSystem.getDevice() instanceof OverhaulGpuDevice actual))throw new IllegalStateException("Terrain smoke requires Overhaul's actual graphics device");
        device=actual;receipt=minecraft.gameDirectory.toPath().resolve("overhaul-terrain-smoke-latest.json");
        try{Files.deleteIfExists(receipt);}catch(IOException failure){throw new IllegalStateException(failure);}
        attachCompiler();
        var capture=device.awaitWorldCapture();
        work=camera(6.5).thenCompose(ignored->capture);
        writeReceipt("RUNNING");
        return result.copy();
    }
    @Override public void tick() {
        requireOwner();if(result.isDone())return;
        try {
            if(System.nanoTime()-lastProgress>java.util.concurrent.TimeUnit.SECONDS.toNanos(40))
                throw new IllegalStateException("Terrain smoke stalled in "+phase+"; workDone="+work.isDone());
            if(phase==Phase.INITIAL&&work.isDone()) {
                imageEvidence=work.join().toString();recordCheckpoint("initial");beginEdit(1);
            } else if(phase==Phase.EDIT) {
                if(!work.isDone())return;work.join();
                if(!rebuilt())return;
                work=device.awaitWorldCapture(stage==1?"boundary":stage==2?"edited":"reloaded");phase=Phase.CAPTURE;
            } else if(phase==Phase.CAPTURE&&work.isDone()) {
                imageEvidence=work.join().toString();recordCheckpoint(stage==1?"boundary":stage==2?"edited":"reloaded");
                if(stage==1)beginEdit(2);
                else if(stage==2){retired.add(compiler);work=minecraft.reloadResourcePacks();phase=Phase.RELOAD;lastProgress=System.nanoTime();writeReceipt("RUNNING");}
                else {phase=Phase.DONE;writeReceipt("CAPTURED");result.complete("backend=Overhaul Vulkan;terrainStages="+checkpoints.size()+";"+imageEvidence);}
            } else if(phase==Phase.RELOAD&&work.isDone()) {
                work.join();
                if(minecraft.gui.overlay()!=null)return;
                if(!invalidationRequested) {
                    minecraft.levelExtractor.allChanged();invalidationRequested=true;lastProgress=System.nanoTime();writeReceipt("RUNNING");return;
                }
                var next=currentCompiler();if(next==compiler)return;
                attachCompiler();beginEdit(3);
            }
        } catch(RuntimeException failure) {
            result.completeExceptionally(failure);writeReceipt("FAIL");throw failure;
        }
    }
    private void attachCompiler() {
        var game=minecraft.levelRenderer.sectionRenderDispatcher();
        if(!(game instanceof TerrainDispatcherAccess access))throw new IllegalStateException("Terrain dispatcher hook is absent");
        dispatcher=access;compiler=currentCompiler();
        for(long section:sections)compiler.monitor().watch(section);
    }
    private OverhaulSectionCompiler currentCompiler() {
        var current=minecraft.levelRenderer.sectionRenderDispatcher();
        if(!(current instanceof TerrainDispatcherAccess actual)||!(actual.overhaul$sectionCompiler() instanceof TerrainCompilerAccess access))throw new IllegalStateException("Terrain compiler hook is absent");
        return access.overhaul$compiler();
    }
    private void beginEdit(int next) {
        stage=next;finalStateBarrier=-1;
        for(int i=0;i<2;i++){beforeCounts[i]=compiler.monitor().completions(sections[i]);beforeMeshes[i]=mesh(i);}
        work=camera(14.5).thenCompose(ignored->fixtures.place(minecraft.getSingleplayerServer(),stage));
        phase=Phase.EDIT;lastProgress=System.nanoTime();writeReceipt("RUNNING");
    }
    private boolean rebuilt() {
        if(minecraft.level==null||!fixtures.matches(minecraft.level,stage))return false;
        if(finalStateBarrier<0) {
            for(int i=0;i<2;i++){beforeCounts[i]=compiler.monitor().completions(sections[i]);beforeMeshes[i]=mesh(i);}
            finalStateBarrier=net.minecraft.util.Util.getNanos();
            minecraft.level.setSectionDirtyWithNeighbors(0,0,0);
            minecraft.level.setSectionDirtyWithNeighbors(1,0,0);
            return false;
        }
        for(int i=0;i<2;i++) {
            var current=mesh(i);
            if(compiler.monitor().completions(sections[i])<=beforeCounts[i]||current==null||current==beforeMeshes[i]
                    ||current.getCompileTaskStartTime()<=finalStateBarrier
                    ||!current.hasRenderableLayers()||!current.hasTranslucentGeometry())return false;
        }
        if(mesh(0).getRenderableBlockEntities().stream().noneMatch(entity->entity.getBlockPos().equals(TerrainFixtures.CHEST)))return false;
        Map<String,Object> stats=compiler.monitor().snapshot();
        if(number(stats,"fluidBlocks")==0)return false;
        checkCompilation(stats);
        return true;
    }
    private SectionMesh mesh(int index) {
        var lookup=(TerrainSectionAccess)minecraft.levelRenderer;
        BlockPos position=index==0?TerrainFixtures.LEFT:TerrainFixtures.RIGHT;
        var section=lookup.overhaul$section(position);
        return section==null||section.getSectionNode()!=sections[index]?null:section.getSectionMesh();
    }
    private CompletableFuture<Void> camera(double x) {
        var server=minecraft.getSingleplayerServer();var id=minecraft.player.getUUID();
        return server.submit(()-> {
            if(!server.isSameThread())throw new IllegalStateException("Terrain smoke camera requires the server owner");
            var player=server.getPlayerList().getPlayer(id);
            if(player==null||!player.teleportTo(player.level(),x,4,-5.5,java.util.Set.of(),0,7,true))throw new IllegalStateException("Cannot place terrain smoke camera");
        });
    }
    private void recordCheckpoint(String label) {
        var statistics=compiler.monitor().snapshot();checkCompilation(statistics);
        var checkpoint=new LinkedHashMap<String,Object>();
        checkpoint.put("stage",label);checkpoint.put("image",imageEvidence);
        checkpoint.put("finalClientStateBarrier",finalStateBarrier);
        checkpoint.put("compiler",statistics);checkpoint.put("workers",dispatcher.overhaul$workerStatistics());
        for(int i=0;i<2;i++) {
            var mesh=mesh(i);
            checkpoint.put(i==0?"leftCompletions":"rightCompletions",compiler.monitor().completions(sections[i]));
            checkpoint.put(i==0?"leftCompileStart":"rightCompileStart",mesh==null?0:mesh.getCompileTaskStartTime());
        }
        if(stage>0)checkpoint.put("chestMetadata",mesh(0).getRenderableBlockEntities().stream().anyMatch(entity->entity.getBlockPos().equals(TerrainFixtures.CHEST)));
        if(stage>0) {
            checkpoint.put("leftClientState",minecraft.level.getBlockState(TerrainFixtures.LEFT).toString());
            checkpoint.put("rightClientState",minecraft.level.getBlockState(TerrainFixtures.RIGHT).toString());
        }
        checkpoints.add(Map.copyOf(checkpoint));
        lastProgress=System.nanoTime();
        java.util.logging.Logger.getLogger("overhaul").info("Overhaul terrain smoke captured "+label+"; "+statistics);
    }
    private static long number(Map<String,Object> stats,String name){return((Number)stats.get(name)).longValue();}
    private static void checkCompilation(Map<String,Object> stats) {
        if(number(stats,"failed")!=0||number(stats,"constructorThreadCompiles")!=0||number(stats,"overhaulWorkerCompiles")==0
                ||number(stats,"overhaulWorkerCompiles")!=number(stats,"started"))
            throw new IllegalStateException("Terrain must compile successfully on Overhaul workers: "+stats);
    }
    @Override public void disconnected() {
        requireOwner();
        if(checkpoints.size()!=4||fixtures.placements()!=3||!result.isDone()||result.isCompletedExceptionally())throw new IllegalStateException("Terrain scenarios are incomplete");
        Map<String,Long> workers=dispatcher.overhaul$workerStatistics();
        for(String key:List.of("terrainActiveTasks","terrainWorkerPending","terrainWorkerPumps"))if(workers.get(key)!=0)throw new IllegalStateException("Terrain workers remain after disconnect: "+workers);
        if(compiler.activeLeases()!=0||retired.stream().anyMatch(old->old.activeLeases()!=0))throw new IllegalStateException("Terrain page leases remain after unload");
        var generations=new ArrayList<>(retired);generations.add(compiler);
        for(var generation:generations) {
            var stats=generation.monitor().snapshot();checkCompilation(stats);
            if(number(stats,"started")!=number(stats,"completed")||generation!=compiler&&!generation.isClosed())throw new IllegalStateException("Terrain compiler generation did not retire cleanly: "+stats);
        }
        writeReceipt("PASS");
    }
    private void writeReceipt(String status) {
        var report=new LinkedHashMap<String,Object>();report.put("status",status);report.put("stages",checkpoints);
        report.put("phase",phase.toString());report.put("workDone",work!=null&&work.isDone());
        report.put("screen",minecraft.gui.screen()==null?"none":minecraft.gui.screen().getClass().getName());
        report.put("overlay",minecraft.gui.overlay()==null?"none":minecraft.gui.overlay().getClass().getName());
        report.put("serverMutationRounds",fixtures.placements());report.put("reloadedCompilerGenerations",retired.size());
        report.put("finalCompiler",compiler.monitor().snapshot());report.put("finalWorkers",dispatcher.overhaul$workerStatistics());
        report.put("activePageLeases",compiler.activeLeases());report.put("retiredPageLeases",retired.stream().mapToInt(OverhaulSectionCompiler::activeLeases).sum());
        report.put("compilerClosed",compiler.isClosed());report.put("retiredCompilersClosed",retired.stream().allMatch(OverhaulSectionCompiler::isClosed));
        report.put("compilerLifetime","Current dispatcher is client-owned in menus; final close is verified in the graphics shutdown receipt");
        report.put("retiredCompilers",retired.stream().map(old->old.monitor().snapshot()).toList());
        report.put("observedLeftCompletions",compiler.monitor().completions(sections[0]));report.put("observedRightCompletions",compiler.monitor().completions(sections[1]));
        report.put("regionContract","Minecraft worker-region light/tint queries; not a fully frozen portable snapshot");
        try{Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(IOException failure){throw new IllegalStateException("Cannot write terrain smoke receipt",failure);}
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Terrain smoke belongs to the client owner");}
    @Override public void close(){requireOwner();if(!result.isDone())result.completeExceptionally(new IllegalStateException("Terrain smoke closed before completion"));}
}
