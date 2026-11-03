package dev.overhaul.minecraft.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.particle.OverhaulQuadParticleRenderer;
import dev.overhaul.minecraft.particle.ParticleRendererAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Actual providers enter the ordinary engine/extraction/feature path, including improved transparency and reload. */
final class ParticleWorldProbe implements WorldSmokeProbe {
    private enum Phase { CAPTURE,RELOAD,DONE }
    private static final String[] LABELS={"particles-normal","particles-oit","particles-reloaded"};
    private final Thread owner=Thread.currentThread();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final List<Map<String,Object>> stages=new ArrayList<>();
    private Minecraft minecraft;
    private OverhaulQuadParticleRenderer renderer;
    private OverhaulGpuDevice device;
    private CompletableFuture<?> work;
    private Map<String,Object> before;
    private Path receipt;
    private Phase phase;
    private int stage;
    private long progress;
    private String failure="";

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey ignored) {
        requireOwner();this.minecraft=minecraft;
        renderer=((ParticleRendererAccess)minecraft.gameRenderer.featureRenderDispatcher()).overhaul$particles();
        if(renderer==null)throw new IllegalStateException("Ordinary particle renderer replacement is absent");
        device=(OverhaulGpuDevice)RenderSystem.getDevice();receipt=minecraft.gameDirectory.toPath().resolve("overhaul-particle-smoke-latest.json");
        begin(0);return result.copy();
    }
    private void begin(int next) {
        stage=next;minecraft.options.improvedTransparency().set(stage!=0);
        minecraft.particleEngine.clearParticles();before=renderer.snapshot();
        var eye=minecraft.player.getEyePosition();var direction=minecraft.player.getLookAngle();var center=eye.add(direction.scale(5));
        for(int type=0;type<2;type++)for(int i=0;i<64;i++) {
            double horizontal=(i%8-3.5)*.14+(type==0?-.8:.8),vertical=(i/8-3.5)*.14;
            var particle=minecraft.particleEngine.createParticle(type==0?ParticleTypes.FLAME:ParticleTypes.END_ROD,
                    center.x+direction.z*horizontal,center.y+vertical,center.z-direction.x*horizontal,0,0,0);
            if(particle==null)throw new IllegalStateException("Actual particle provider did not accept the live fixture");
            particle.setLifetime(2000);particle.setParticleSpeed(0,0,0);particle.scale(2);
        }
        work=device.awaitWorldCapture(LABELS[stage]);phase=Phase.CAPTURE;progress=System.nanoTime();writeReceipt("RUNNING");
    }
    @Override public void tick() {
        requireOwner();if(result.isDone())return;
        try {
            if(System.nanoTime()-progress>TimeUnit.SECONDS.toNanos(40))throw new IllegalStateException("Particle smoke stalled at "+LABELS[stage]+"/"+phase+"; "+renderer.snapshot());
            if(phase==Phase.CAPTURE&&work.isDone()) {
                String image=work.join().toString();var after=renderer.snapshot();
                if(!Boolean.TRUE.equals(after.get("eligible"))||number(after,"preparedParticles")-number(before,"preparedParticles")<128
                        ||number(after,"normalCalls")<=number(before,"normalCalls")||number(after,"maxLayerInstances")<64
                        ||stage>0&&number(after,"oitCalls")<=number(before,"oitCalls")
                        ||stage==0&&number(after,"oitCalls")!=number(before,"oitCalls")
                        ||number(after,"fallbackGroups")!=number(before,"fallbackGroups"))
                    throw new IllegalStateException("Ordinary particles did not execute the required instanced stages: "+after+"; before="+before);
                var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("stage",LABELS[stage]);checkpoint.put("image",image);
                checkpoint.put("spawnedFlame",64);checkpoint.put("spawnedEndRod",64);checkpoint.put("before",before);checkpoint.put("after",after);
                checkpoint.put("improvedTransparency",minecraft.gameRenderer.useImprovedTransparency());stages.add(Map.copyOf(checkpoint));
                java.util.logging.Logger.getLogger("overhaul").info("Overhaul captured "+LABELS[stage]+"; "+after);
                if(stage==0)begin(1);
                else if(stage==1){work=minecraft.reloadResourcePacks();phase=Phase.RELOAD;progress=System.nanoTime();writeReceipt("RUNNING");}
                else {phase=Phase.DONE;writeReceipt("CAPTURED");result.complete("particleStages=3;"+image);}
            }else if(phase==Phase.RELOAD&&work.isDone()) {
                work.join();if(minecraft.gui.overlay()!=null||!Boolean.TRUE.equals(renderer.snapshot().get("eligible")))return;begin(2);
            }
        }catch(RuntimeException problem){failure=problem.toString();result.completeExceptionally(problem);writeReceipt("FAIL");throw problem;}
    }
    @Override public void disconnected() {
        requireOwner();if(stages.size()!=3||!result.isDone()||result.isCompletedExceptionally()||number(renderer.snapshot(),"pendingGroups")!=0)
            throw new IllegalStateException("Particle lifecycle scenarios are incomplete");
        writeReceipt("PASS");
    }
    private void writeReceipt(String status) {
        var report=new LinkedHashMap<String,Object>();report.put("status",status);report.put("phase",phase.toString());report.put("stages",stages);
        report.put("failure",failure);report.put("particles",renderer.snapshot());report.put("providerPath","ParticleEngine.createParticle: FLAME and END_ROD; ordinary extraction/feature groups");
        try{Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(IOException problem){throw new IllegalStateException("Cannot write ordinary particle receipt",problem);}
    }
    private static long number(Map<String,Object> values,String name){return((Number)values.get(name)).longValue();}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Particle smoke requires the client owner");}
    @Override public void close(){requireOwner();if(!result.isDone())result.completeExceptionally(new IllegalStateException("Particle smoke closed before completion"));}
}
