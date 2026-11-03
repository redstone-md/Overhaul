package dev.overhaul.minecraft.smoke;

import com.google.gson.GsonBuilder;
import dev.overhaul.adapter.SectionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Development-only proof against the actual integrated-server entity manager and ordinary query APIs. */
public final class EntityQueryWorldProbe implements WorldSmokeProbe {
    private final Thread owner=Thread.currentThread();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private IntegratedServer server;
    private EntityQueryScenario scenario;
    private CompletableFuture<Map<String,Object>> work;
    private Map<String,Object> evidence=Map.of();
    private Path receipt;
    private long started;

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey ignored){
        requireOwner();
        if(work!=null)throw new IllegalStateException("Entity query smoke is already active");
        server=minecraft.getSingleplayerServer();
        if(server==null||minecraft.level==null)throw new IllegalStateException("Entity query smoke requires its loaded disposable world");
        receipt=minecraft.gameDirectory.toPath().resolve("overhaul-entity-query-smoke-latest.json");
        started=System.nanoTime();write("RUNNING","");
        work=server.submit(()->{scenario=new EntityQueryScenario(server);return scenario.start();});
        return result.copy();
    }
    @Override public void tick(){
        requireOwner();if(result.isDone())return;
        try{
            if(System.nanoTime()-started>TimeUnit.SECONDS.toNanos(45))throw new IllegalStateException("Entity query smoke timed out");
            if(!work.isDone())return;
            var completed=work.join();
            if(completed==null){work=server.submit(scenario::advance);return;}
            evidence=completed;write("CAPTURED","");result.complete("entityQueries=ordered-oracle,movement,removal,visibility,abort,reentrant;ordinaryTicks=40");
        }catch(RuntimeException failure){
            if(scenario!=null)server.execute(scenario::close);
            write("FAIL",failure.toString());result.completeExceptionally(failure);throw failure;
        }
    }
    @Override public void disconnected(){
        requireOwner();
        if(!result.isDone()||result.isCompletedExceptionally()||evidence.isEmpty())throw new IllegalStateException("Entity query lifecycle proof is incomplete");
        write("PASS","");
    }
    private void write(String status,String failure){
        var report=new LinkedHashMap<String,Object>();report.put("status",status);report.put("failure",failure);
        report.put("evidence",evidence);report.put("disconnected",status.equals("PASS"));
        try{Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(IOException problem){throw new IllegalStateException("Cannot write entity query receipt",problem);}
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Entity query driver requires the client owner");}
    @Override public void close(){
        requireOwner();
        if(!result.isDone()){
            if(scenario!=null)server.execute(scenario::close);
            result.completeExceptionally(new IllegalStateException("Entity query smoke closed before completion"));
        }
    }
}
