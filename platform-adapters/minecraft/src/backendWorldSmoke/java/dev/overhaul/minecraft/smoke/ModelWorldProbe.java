package dev.overhaul.minecraft.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.model.ModelRendererAccess;
import dev.overhaul.minecraft.model.OverhaulModelRenderer;
import dev.overhaul.minecraft.terrain.TerrainSectionAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ChestMenu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real pigs and a real chest enter ordinary model features, including animation, sorting, OIT and teardown. */
public final class ModelWorldProbe implements WorldSmokeProbe {
    private enum Phase { EDIT,CAPTURE,VERIFY_CAPTURE,RELOAD,RESTORE,DONE }
    private static final String[] LABELS={"models-normal","models-animated","models-oit","models-reloaded","models-clear"};
    private final Thread owner=Thread.currentThread();
    private final ModelFixtures fixtures=new ModelFixtures();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final List<Map<String,Object>> checkpoints=new ArrayList<>();
    private Minecraft minecraft;
    private OverhaulModelRenderer renderer;
    private OverhaulGpuDevice device;
    private CompletableFuture<?> work;
    private Map<String,Object> stageBefore,before;
    private Map<String,Object> openMenuProof=Map.of(),restoredFixture=Map.of();
    private Map<String,Object> beforeInvisibilityProof=Map.of(),afterInvisibilityProof=Map.of();
    private Map<String,Object> currentReadiness=Map.of();
    private CompletableFuture<Map<String,Object>> menuVerification;
    private CompletableFuture<Map<String,Object>> invisibilityVerification,captureInvisibilityVerification;
    private CompletableFuture<Boolean> navigationAdmission;
    private Path receipt;
    private Phase phase;
    private int stage;
    private long progress,baselineFrames,pipelineBeforeReload,resourceBeforeReload,lastReadinessUpdate;
    private float walkStart,maximumWalkDelta,maximumWalkSpeed,maximumChestOpen;
    private String failure="",capturedImage="";
    private boolean chestGuiHidden,navigationAccepted;

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey ignored){
        requireOwner();this.minecraft=minecraft;
        renderer=((ModelRendererAccess)minecraft.gameRenderer.featureRenderDispatcher()).overhaul$models();
        if(renderer==null)throw new IllegalStateException("Ordinary model feature replacement is absent");
        device=(OverhaulGpuDevice)RenderSystem.getDevice();
        receipt=minecraft.gameDirectory.toPath().resolve("overhaul-model-render-smoke-latest.json");
        minecraft.mouseHandler.releaseMouse();
        minecraft.particleEngine.clearParticles();begin(0);return result.copy();
    }
    private boolean improved(){return stage>=2;}
    private void begin(int next){
        stage=next;minecraft.options.improvedTransparency().set(improved());stageBefore=renderer.snapshot();
        invisibilityVerification=null;beforeInvisibilityProof=Map.of();afterInvisibilityProof=Map.of();
        baselineFrames=number(stageBefore,"frames");
        if(stage==1){var pig=fixtures.visible(minecraft.level);if(pig==null)throw new IllegalStateException("Animation fixture pig is absent");walkStart=pig.walkAnimation.position();}
        var server=minecraft.getSingleplayerServer();var player=minecraft.player.getUUID();
        work=switch(stage){
            case 0->fixtures.place(server,player);
            case 1->fixtures.animate(server,player);
            case 2->fixtures.translucentView(server,player);
            case 4->fixtures.clear(server,player);
            default->CompletableFuture.completedFuture(null);
        };
        phase=Phase.EDIT;progress=System.nanoTime();writeReceipt("RUNNING");
    }
    @Override public void tick(){
        requireOwner();if(result.isDone())return;
        try{
            if(System.nanoTime()-lastReadinessUpdate>TimeUnit.SECONDS.toNanos(1)){
                lastReadinessUpdate=System.nanoTime();currentReadiness=readiness();writeReceipt("RUNNING");
            }
            if(System.nanoTime()-progress>TimeUnit.SECONDS.toNanos(40))throw new IllegalStateException("Model smoke stalled at "+LABELS[stage]+"/"+phase+"; camera="+camera()+"; fixture="+fixtures.snapshot(minecraft.level,minecraft.player)+"; models="+renderer.snapshot());
            if(phase==Phase.EDIT&&work.isDone()){
                work.join();advanceNavigation();hideChestGui();observeAnimation();if(!ready())return;
                before=renderer.snapshot();work=device.awaitWorldCapture(LABELS[stage]);phase=Phase.CAPTURE;progress=System.nanoTime();
            }else if(phase==Phase.CAPTURE&&work.isDone()){
                String image=work.join().toString();observeAnimation();if(!ready())throw new IllegalStateException("Model fixture changed during GPU capture");
                if(stage==4)finishCapture(image);
                else{capturedImage=image;captureInvisibilityVerification=fixtures.verifyPersistentInvisibility(minecraft.getSingleplayerServer());
                    work=captureInvisibilityVerification;phase=Phase.VERIFY_CAPTURE;progress=System.nanoTime();}
            }else if(phase==Phase.VERIFY_CAPTURE&&work.isDone()){
                afterInvisibilityProof=captureInvisibilityVerification.join();
                if(number(afterInvisibilityProof,"entityTicks")<=0||number(afterInvisibilityProof,"serverGameTime")<number(beforeInvisibilityProof,"serverGameTime"))
                    throw new IllegalStateException("Server invisibility capture oracle was not a fresh ticked entity observation");
                finishCapture(capturedImage);
            }else if(phase==Phase.RELOAD&&work.isDone()){
                work.join();if(minecraft.gui.overlay()!=null||!Boolean.TRUE.equals(renderer.snapshot().get("eligible")))return;begin(3);
            }else if(phase==Phase.RESTORE&&work.isDone()){
                work.join();if(!fixtures.restoreMatches(minecraft.player))return;
                restoredFixture=fixtures.snapshot(minecraft.level,minecraft.player);
                phase=Phase.DONE;writeReceipt("CAPTURED");result.complete("modelStages=5;ordinaryPigAndChest=true;cleanup=true");
            }
        }catch(RuntimeException problem){failure=problem.toString();currentReadiness=readiness();cleanup();result.completeExceptionally(problem);writeReceipt("FAIL");throw problem;}
    }
    private Map<String,Object> readiness(){
        var state=new LinkedHashMap<String,Object>();var stats=renderer.snapshot();
        state.put("stage",LABELS[stage]);state.put("phase",phase.toString());state.put("workDone",work!=null&&work.isDone());
        state.put("worldLoaded",minecraft.level!=null&&minecraft.player!=null);
        if(minecraft.level==null||minecraft.player==null)return Map.copyOf(state);
        state.put("elapsedSeconds",(System.nanoTime()-progress)/1_000_000_000d);state.put("cameraAccepted",cameraAccepted());
        state.put("camera",camera());state.put("fixtureMatches",fixtures.matches(minecraft.level,minecraft.player,stage));
        state.put("fixture",fixtures.snapshot(minecraft.level,minecraft.player));state.put("chestMetadataMatches",chestMetadataMatches());
        state.put("improvedTransparencyMatches",minecraft.gameRenderer.useImprovedTransparency()==improved());
        state.put("rendererEligible",stats.get("eligible"));state.put("freshFrames",number(stats,"frames")-baselineFrames);
        state.put("pigModelSubmitDelta",family(stats,true)-family(stageBefore,true));state.put("chestModelSubmitDelta",family(stats,false)-family(stageBefore,false));
        state.put("chestGuiHidden",chestGuiHidden);state.put("menuVerificationDone",menuVerification!=null&&menuVerification.isDone());
        state.put("serverEffectVerificationDone",invisibilityVerification!=null&&invisibilityVerification.isDone());
        state.put("navigationAccepted",navigationAccepted);state.put("navigationAdmissionDone",navigationAdmission!=null&&navigationAdmission.isDone());
        state.put("serverEffectBefore",beforeInvisibilityProof);state.put("serverEffectAfter",afterInvisibilityProof);
        state.put("expectedMenuId",fixtures.openedMenuId());state.put("clientMenuId",minecraft.player.containerMenu.containerId);
        state.put("screen",minecraft.gui.screen()==null?"none":minecraft.gui.screen().getClass().getName());
        state.put("maximumWalkPositionDelta",maximumWalkDelta);state.put("maximumWalkSpeed",maximumWalkSpeed);
        state.put("maximumChestOpenness",maximumChestOpen);return Map.copyOf(state);
    }
    private boolean ready(){
        if(minecraft.level==null||minecraft.player==null||!cameraAccepted()||minecraft.gameRenderer.useImprovedTransparency()!=improved())return false;
        if(!fixtures.matches(minecraft.level,minecraft.player,stage)||!chestMetadataMatches())return false;
        var stats=renderer.snapshot();if(!Boolean.TRUE.equals(stats.get("eligible"))||number(stats,"frames")<=baselineFrames+2)return false;
        if(stage==4)return true;
        if(!serverInvisibilityReady())return false;
        if(family(stats,true)<=family(stageBefore,true)||family(stats,false)<=family(stageBefore,false))return false;
        if(stage==1){
            if(!navigationAccepted||!chestGuiHidden||menuVerification==null||!menuVerification.isDone())return false;
            openMenuProof=menuVerification.join();
            return minecraft.gui.screen()==null&&minecraft.player.containerMenu instanceof ChestMenu menu&&menu.containerId==fixtures.openedMenuId()
                    &&fixtures.chestOpenness(minecraft.level)>.8f&&maximumWalkDelta>.03f&&maximumWalkSpeed>.01f;
        }
        return fixtures.chestOpenness(minecraft.level)<.05f;
    }
    private void advanceNavigation(){
        if(stage!=1||navigationAccepted)return;
        if(navigationAdmission!=null){if(!navigationAdmission.isDone())return;navigationAccepted=navigationAdmission.join();}
        if(!navigationAccepted)navigationAdmission=fixtures.advanceNavigation(minecraft.getSingleplayerServer());
    }
    private boolean serverInvisibilityReady(){
        if(invisibilityVerification==null){invisibilityVerification=fixtures.verifyPersistentInvisibility(minecraft.getSingleplayerServer());return false;}
        if(!invisibilityVerification.isDone())return false;
        beforeInvisibilityProof=invisibilityVerification.join();
        if(number(beforeInvisibilityProof,"entityTicks")>0)return true;
        invisibilityVerification=null;return false;
    }
    private void finishCapture(String image){
        observeAnimation();if(!ready())throw new IllegalStateException("Model fixture changed before completing its server capture oracle");
        var after=renderer.snapshot();check(after);record(image,after);
        if(stage==2){pipelineBeforeReload=number(after,"pipelineGenerations");resourceBeforeReload=number(after,"resourceGeneration");
            work=minecraft.reloadResourcePacks();phase=Phase.RELOAD;progress=System.nanoTime();writeReceipt("RUNNING");}
        else if(stage<4)begin(stage+1);
        else{work=fixtures.restorePlayer(minecraft.getSingleplayerServer(),minecraft.player.getUUID());phase=Phase.RESTORE;progress=System.nanoTime();}
    }
    private void hideChestGui(){
        if(stage!=1||chestGuiHidden||!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen))return;
        var menu=minecraft.player.containerMenu;
        if(!(menu instanceof ChestMenu)||menu.containerId!=fixtures.openedMenuId()||screen.getMenu()!=menu)return;
        // Gui.setScreen invokes removed(), not onClose(); the latter sends the server close-container packet.
        minecraft.gui.setScreen(null);
        minecraft.mouseHandler.releaseMouse();
        if(minecraft.gui.screen()!=null||minecraft.player.containerMenu!=menu)throw new IllegalStateException("Hiding chest GUI replaced its client menu");
        chestGuiHidden=true;menuVerification=fixtures.verifyOpen(minecraft.getSingleplayerServer(),minecraft.player.getUUID());
    }
    private boolean chestMetadataMatches(){
        var section=((TerrainSectionAccess)minecraft.levelRenderer).overhaul$section(ModelFixtures.CHEST);
        if(section==null||section.getSectionNode()!=SectionPos.asLong(ModelFixtures.CHEST)||section.getSectionMesh()==null)return false;
        boolean present=section.getSectionMesh().getRenderableBlockEntities().stream().anyMatch(entity->entity.getBlockPos().equals(ModelFixtures.CHEST));
        return present==(stage!=4);
    }
    private void observeAnimation(){
        if(stage!=1||minecraft.level==null)return;
        var pig=fixtures.visible(minecraft.level);if(pig!=null){maximumWalkDelta=Math.max(maximumWalkDelta,Math.abs(pig.walkAnimation.position()-walkStart));maximumWalkSpeed=Math.max(maximumWalkSpeed,pig.walkAnimation.speed());}
        maximumChestOpen=Math.max(maximumChestOpen,fixtures.chestOpenness(minecraft.level));
    }
    private void check(Map<String,Object> after){
        if(number(after,"fallbackGroups")!=number(before,"fallbackGroups")||number(after,"fallbackSubmits")!=number(before,"fallbackSubmits"))throw new IllegalStateException("Model capture used expanded fallback geometry");
        if(stage==4)return;
        long quads=number(after,"preparedQuads")-number(before,"preparedQuads");
        if(quads<=0||number(after,"preparedInstanceBytes")-number(before,"preparedInstanceBytes")!=124*quads
                ||number(after,"recordedInstances")<=number(before,"recordedInstances")||number(after,"normalCalls")<=number(before,"normalCalls")
                ||family(after,true)<=family(before,true)||family(after,false)<=family(before,false))throw new IllegalStateException("Ordinary pig/chest model records did not execute: "+after);
        if(stage==0&&number(after,"sortedQuads")<=number(before,"sortedQuads"))throw new IllegalStateException("Classic translucent pig did not execute quad sorting");
        if(stage==2||stage==3)for(String name:List.of("DEPTH_BOUNDS","TRANSMITTANCE","ACCUMULATE"))
            if(stageCalls(after,name)<=stageCalls(before,name))throw new IllegalStateException("Translucent pig omitted model OIT stage "+name);
        if(stage==3&&(number(after,"pipelineGenerations")<=pipelineBeforeReload||number(after,"resourceGeneration")<=resourceBeforeReload))throw new IllegalStateException("Reload did not rebuild the model pipeline cache");
    }
    private void record(String image,Map<String,Object> after){
        var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("stage",LABELS[stage]);checkpoint.put("image",image);
        checkpoint.put("before",before);checkpoint.put("after",after);checkpoint.put("stageBefore",stageBefore);
        checkpoint.put("fixture",fixtures.snapshot(minecraft.level,minecraft.player));checkpoint.put("camera",camera());checkpoint.put("cameraAccepted",cameraAccepted());
        checkpoint.put("chestMetadataMatches",chestMetadataMatches());checkpoint.put("improvedTransparency",minecraft.gameRenderer.useImprovedTransparency());
        checkpoint.put("maximumWalkPositionDelta",maximumWalkDelta);checkpoint.put("maximumWalkSpeed",maximumWalkSpeed);checkpoint.put("maximumChestOpenness",maximumChestOpen);
        checkpoint.put("chestGuiHidden",stage==1&&chestGuiHidden);checkpoint.put("openMenuProof",stage==1?openMenuProof:Map.of());
        checkpoint.put("clientMenuId",stage==1?minecraft.player.containerMenu.containerId:-1);
        checkpoint.put("serverInvisibilityBefore",beforeInvisibilityProof);checkpoint.put("serverInvisibilityAfter",afterInvisibilityProof);
        checkpoint.put("navigationAccepted",navigationAccepted);
        checkpoint.put("pigModelSubmits",family(after,true)-family(before,true));checkpoint.put("chestModelSubmits",family(after,false)-family(before,false));
        checkpoint.put("clearScope","Owned pig IDs and chest block/entity plus installed mesh metadata; other ordinary world model submissions may remain");
        checkpoints.add(Map.copyOf(checkpoint));writeReceipt("RUNNING");
        java.util.logging.Logger.getLogger("overhaul").info("Overhaul captured "+LABELS[stage]+"; fixture="+checkpoint.get("fixture")+"; models="+after);
    }
    private boolean cameraAccepted(){
        var player=minecraft.player;if(player==null)return false;var actual=minecraft.gameRenderer.mainCamera();
        return Math.abs(player.getX()-ModelFixtures.CAMERA_X)<.05&&Math.abs(player.getY()-ModelFixtures.CAMERA_Y)<.05&&Math.abs(player.getZ()-ModelFixtures.CAMERA_Z)<.05
                &&Math.abs(Mth.wrapDegrees(player.getYRot()-ModelFixtures.CAMERA_YAW))<.1&&Math.abs(player.getXRot()-ModelFixtures.CAMERA_PITCH)<.1
                &&Math.abs(Mth.wrapDegrees(actual.yRot()-ModelFixtures.CAMERA_YAW))<.1&&Math.abs(actual.xRot()-ModelFixtures.CAMERA_PITCH)<.1
                &&actual.position().distanceTo(player.getEyePosition())<.1;
    }
    private Map<String,Object> camera(){
        var player=minecraft.player;var actual=minecraft.gameRenderer.mainCamera();
        return Map.of("player",List.of(player.getX(),player.getY(),player.getZ()),"playerRotation",List.of(player.getYRot(),player.getXRot()),
                "render",List.of(actual.position().x,actual.position().y,actual.position().z),"renderRotation",List.of(actual.yRot(),actual.xRot()));
    }
    @SuppressWarnings("unchecked") private static long family(Map<String,Object> stats,boolean pig){
        var types=(Map<String,Long>)stats.get("modelTypes");long count=0;
        for(var entry:types.entrySet())if(pig?entry.getKey().contains(".model.animal.pig.")&&entry.getKey().endsWith("PigModel")
                :entry.getKey().equals("net.minecraft.client.model.object.chest.ChestModel"))count+=entry.getValue();
        return count;
    }
    @SuppressWarnings("unchecked") private static long stageCalls(Map<String,Object> stats,String name){return((Number)((Map<String,Object>)stats.get("oitCalls")).get(name)).longValue();}
    @Override public void disconnected(){
        requireOwner();if(checkpoints.size()!=5||!result.isDone()||result.isCompletedExceptionally()||number(renderer.snapshot(),"pendingGroups")!=0)throw new IllegalStateException("Model lifecycle scenarios are incomplete");
        writeReceipt("PASS");
    }
    private void cleanup(){
        var server=minecraft.getSingleplayerServer();if(server==null||minecraft.player==null)return;
        var id=minecraft.player.getUUID();fixtures.clear(server,id).thenCompose(ignored->fixtures.restorePlayer(server,id)).whenComplete((ignored,problem)->{
            if(problem!=null)java.util.logging.Logger.getLogger("overhaul").log(java.util.logging.Level.SEVERE,"Model fixture cleanup failed",problem);
        });
    }
    private void writeReceipt(String status){
        var report=new LinkedHashMap<String,Object>();report.put("status",status);report.put("phase",phase.toString());report.put("failure",failure);
        report.put("stages",checkpoints);report.put("models",renderer.snapshot());report.put("instanceStride",124);
        report.put("restoredFixture",restoredFixture);
        report.put("readiness",currentReadiness);
        report.put("effectSyncContract","Ordinary remote mobs synchronize the invisible entity flag; active-effect packets target affected players/passengers. Client effect-list diagnostics are retained but are not a rendering oracle. Ticked server effect/flag futures verify persistent invisibility before and after capture.");
        report.put("source","Actual server pigs and chest menu events; ordinary model feature registration and ModelPart hierarchy");
        try{Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(IOException problem){throw new IllegalStateException("Cannot write ordinary model receipt",problem);}
    }
    private static long number(Map<String,Object> values,String name){return((Number)values.get(name)).longValue();}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Model smoke requires the client owner");}
    @Override public void close(){requireOwner();if(!result.isDone()){cleanup();result.completeExceptionally(new IllegalStateException("Model smoke closed before completion"));}}
}
