package dev.overhaul.minecraft.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.adapter.SectionKey;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.weather.OverhaulWeatherRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Server weather/biome packets drive the ordinary extraction, preparation and normal/OIT draw paths. */
final class WeatherWorldProbe implements WorldSmokeProbe {
    private enum Phase { EDIT,CAPTURE,RELOAD,DONE }
    private static final int RADIUS=5;
    private static final String[] LABELS={"weather-rain","weather-rain-oit","weather-snow","weather-snow-oit","weather-reloaded","weather-mixed","weather-clear"};
    private record Counts(int rain,int snow){}
    private final Thread owner=Thread.currentThread();
    private final CompletableFuture<String> result=new CompletableFuture<>();
    private final WeatherFixtures fixtures=new WeatherFixtures();
    private final List<Map<String,Object>> stages=new ArrayList<>();
    private Minecraft minecraft;
    private OverhaulWeatherRenderer renderer;
    private OverhaulGpuDevice device;
    private BlockPos center;
    private Path receipt;
    private CompletableFuture<?> work;
    private Map<String,Object> before,parity=Map.of();
    private Phase phase;
    private int stage;
    private long progress,baselinePreparation;
    private String failure="";

    @Override public CompletableFuture<String> capture(Minecraft minecraft,SectionKey ignored) {
        requireOwner();this.minecraft=minecraft;
        if(!(minecraft.levelRenderer.weatherEffectRenderer() instanceof OverhaulWeatherRenderer actual))throw new IllegalStateException("Ordinary weather renderer replacement is absent");
        renderer=actual;device=(OverhaulGpuDevice)RenderSystem.getDevice();center=BlockPos.containing(minecraft.player.getEyePosition());
        receipt=minecraft.gameDirectory.toPath().resolve("overhaul-weather-smoke-latest.json");minecraft.options.weatherRadius().set(RADIUS);
        parity=WeatherPixelParity.verify(minecraft,renderer);begin(0);return result.copy();
    }
    private boolean snow(){return stage>=2&&stage<=4;}
    private boolean improved(){return stage==1||stage==3||stage>=4;}
    private void begin(int next) {
        stage=next;minecraft.options.improvedTransparency().set(improved());
        baselinePreparation=number(renderer.snapshot(),"preparations");
        work=stage==6?fixtures.clear(minecraft.getSingleplayerServer()):stage==5?fixtures.mixed(minecraft.getSingleplayerServer(),center,RADIUS):
                stage==0||stage==2?fixtures.place(minecraft.getSingleplayerServer(),center,RADIUS,snow()):CompletableFuture.completedFuture(null);
        phase=Phase.EDIT;progress=System.nanoTime();writeReceipt("RUNNING");
    }
    @Override public void tick() {
        requireOwner();if(result.isDone())return;
        try {
            if(System.nanoTime()-progress>TimeUnit.SECONDS.toNanos(40))throw new IllegalStateException("Weather smoke stalled at "+LABELS[stage]+"/"+phase+"; "+renderer.snapshot());
            if(phase==Phase.EDIT&&work.isDone()) {
                work.join();if(!ready())return;before=renderer.snapshot();work=device.awaitWorldCapture(LABELS[stage]);phase=Phase.CAPTURE;progress=System.nanoTime();
            }else if(phase==Phase.CAPTURE&&work.isDone()) {
                String image=work.join().toString();if(!ready())throw new IllegalStateException("Weather changed during GPU capture");
                var after=renderer.snapshot();check(after);
                var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("stage",LABELS[stage]);checkpoint.put("image",image);
                checkpoint.put("before",before);checkpoint.put("after",after);checkpoint.put("snow",snow());checkpoint.put("mixed",stage==5);checkpoint.put("clear",stage==6);
                var counts=clientCounts();checkpoint.put("clientRainBiomes",counts.rain());checkpoint.put("clientSnowBiomes",counts.snow());
                checkpoint.put("clientMixedMatchesServer",stage!=5||fixtures.matchesMixedClient(minecraft.level,center,RADIUS,.9f));
                checkpoint.put("improvedTransparency",minecraft.gameRenderer.useImprovedTransparency());checkpoint.put("clientRainLevel",minecraft.level.getRainLevel(1));
                checkpoint.put("clientPrecipitation",minecraft.level.getPrecipitationAt(center).toString());stages.add(Map.copyOf(checkpoint));
                java.util.logging.Logger.getLogger("overhaul").info("Overhaul captured "+LABELS[stage]+"; "+after);
                if(stage==3){work=minecraft.reloadResourcePacks();phase=Phase.RELOAD;progress=System.nanoTime();writeReceipt("RUNNING");}
                else if(stage<6)begin(stage+1);
                else{phase=Phase.DONE;writeReceipt("CAPTURED");result.complete("weatherStages=7;"+image);}
            }else if(phase==Phase.RELOAD&&work.isDone()) {
                work.join();if(minecraft.gui.overlay()!=null||!Boolean.TRUE.equals(renderer.snapshot().get("eligible")))return;begin(4);
            }
        }catch(RuntimeException problem){failure=problem.toString();result.completeExceptionally(problem);writeReceipt("FAIL");throw problem;}
    }
    private boolean ready() {
        if(minecraft.level==null||minecraft.gameRenderer.useImprovedTransparency()!=improved())return false;
        var stats=renderer.snapshot();if(number(stats,"preparations")<=baselinePreparation||!Boolean.TRUE.equals(stats.get("eligible")))return false;
        if(stage==6)return fixtures.matchesClearClient(minecraft.level)&&number(stats,"lastRainCount")==0&&number(stats,"lastSnowCount")==0;
        if(stage==5) {
            if(!fixtures.matchesMixedClient(minecraft.level,center,RADIUS,.9f))return false;
            var counts=clientCounts();return counts.rain()>0&&counts.snow()>0&&counts.rain()+counts.snow()==121
                    &&number(stats,"lastRainCount")==counts.rain()&&number(stats,"lastSnowCount")==counts.snow();
        }
        if(!fixtures.matchesClient(minecraft.level,center,RADIUS,snow(),.9f))return false;
        return number(stats,snow()?"lastSnowCount":"lastRainCount")==121&&number(stats,snow()?"lastRainCount":"lastSnowCount")==0;
    }
    private void check(Map<String,Object> after) {
        if(number(after,"fallbackPreparations")!=number(before,"fallbackPreparations"))throw new IllegalStateException("Weather instance path fell back during capture");
        if(stage==6){if(totalCalls(after)!=totalCalls(before))throw new IllegalStateException("Cleared weather retained draw calls");return;}
        long records=number(after,"preparedRainInstances")+number(after,"preparedSnowInstances")-number(before,"preparedRainInstances")-number(before,"preparedSnowInstances");
        if(records<2||number(after,"preparedInstanceBytes")-number(before,"preparedInstanceBytes")!=48*records)throw new IllegalStateException("Weather was not packed into 48-byte instances");
        for(String type:stage==5?List.of("Rain","Snow"):List.of(snow()?"Snow":"Rain")) {
            if(improved()) {
                for(String name:List.of("DEPTH_BOUNDS","TRANSMITTANCE","ACCUMULATE"))if(stageCalls(after,type,name)<=stageCalls(before,type,name))throw new IllegalStateException("Weather omitted OIT stage "+name);
            }else if(number(after,"normal"+type+"Calls")<=number(before,"normal"+type+"Calls"))throw new IllegalStateException("Weather omitted normal draws");
        }
    }
    private Counts clientCounts() {
        int rain=0,snow=0;var position=new BlockPos.MutableBlockPos();
        for(int z=-RADIUS;z<=RADIUS;z++)for(int x=-RADIUS;x<=RADIUS;x++) {
            position.set(center.getX()+x,center.getY(),center.getZ()+z);var precipitation=minecraft.level.getPrecipitationAt(position);
            if(precipitation==Biome.Precipitation.RAIN)rain++;else if(precipitation==Biome.Precipitation.SNOW)snow++;
        }
        return new Counts(rain,snow);
    }
    @SuppressWarnings("unchecked") private static long stageCalls(Map<String,Object> values,String type,String stage){return((Number)((Map<String,Object>)values.get("oit"+type+"Calls")).get(stage)).longValue();}
    private static long totalCalls(Map<String,Object> values){long total=number(values,"normalRainCalls")+number(values,"normalSnowCalls");for(String type:List.of("Rain","Snow"))for(String stage:List.of("DEPTH_BOUNDS","TRANSMITTANCE","ACCUMULATE"))total+=stageCalls(values,type,stage);return total;}
    @Override public void disconnected() {
        requireOwner();if(stages.size()!=7||!result.isDone()||result.isCompletedExceptionally()||number(renderer.snapshot(),"lastRainCount")!=0||number(renderer.snapshot(),"lastSnowCount")!=0)
            throw new IllegalStateException("Weather lifecycle scenarios are incomplete");
        writeReceipt("PASS");
    }
    private void writeReceipt(String status) {
        var report=new LinkedHashMap<String,Object>();report.put("status",status);report.put("phase",phase.toString());report.put("stages",stages);
        report.put("failure",failure);report.put("weather",renderer.snapshot());report.put("pixelParity",parity);
        report.put("fixtureCenter",List.of(center.getX(),center.getY(),center.getZ()));report.put("weatherRadius",RADIUS);
        report.put("source","Actual server weather and biome packets; inherited WeatherEffectRenderer world extraction");
        try{Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        catch(IOException problem){throw new IllegalStateException("Cannot write ordinary weather receipt",problem);}
    }
    private static long number(Map<String,Object> values,String name){return((Number)values.get(name)).longValue();}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Weather smoke requires the client owner");}
    @Override public void close(){requireOwner();if(!result.isDone())result.completeExceptionally(new IllegalStateException("Weather smoke closed before completion"));}
}
