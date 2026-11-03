package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.compat.UniformBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/** One actual camera/world snapshot shared by every phase; it retains no level, camera or entity. */
final class ShaderPackEnvironmentUniforms {
    private int worldTime,worldDay,moonPhase,submersion,blockLight,skyLight;
    private float red,green,blue,rain,thunder,altitude,brightness;

    void capture(Minecraft minecraft,LevelRenderState state){
        var level=minecraft.level;var camera=state.cameraRenderState;float partial=state.worldPartialTicks;
        long clock=level.getDefaultClockTime();var dimension=level.dimension();
        boolean fixed=level.dimensionType().hasFixedTime()&&!dimension.equals(Level.NETHER)&&!dimension.equals(Level.END);
        worldTime=fixed?0:(int)Math.floorMod(clock,24000L);worldDay=(int)(clock/24000L);
        var probe=minecraft.gameRenderer.mainCamera().attributeProbe();
        var sky=probe.getValue(EnvironmentAttributes.SKY_COLOR,partial);
        red=sky.x();green=sky.y();blue=sky.z();
        moonPhase=probe.getValue(EnvironmentAttributes.MOON_PHASE,partial).index();
        rain=clampWeather(level.getRainLevel(partial));thunder=clampWeather(level.getThunderLevel(partial));
        altitude=(float)camera.pos.y;brightness=minecraft.options.gamma().get().floatValue();
        boolean spectator=minecraft.player!=null&&minecraft.player.isSpectator();
        submersion=switch(camera.fogType){case WATER->1;case LAVA->spectator?0:2;case POWDER_SNOW->3;default->0;};
        var entity=minecraft.getCameraEntity();
        if(entity==null){blockLight=skyLight=0;}
        else{
            var eye=BlockPos.containing(entity.getX(),entity.getEyeY(),entity.getZ());
            blockLight=level.getBrightness(LightLayer.BLOCK,eye)*16;skyLight=level.getBrightness(LightLayer.SKY,eye)*16;
        }
    }

    boolean fill(String name,UniformBlock values){
        switch(name){
            case "worldTime"->values.setInt(name,worldTime);
            case "worldDay"->values.setInt(name,worldDay);
            case "moonPhase"->values.setInt(name,moonPhase);
            case "skyColor"->values.setVector(name,red,green,blue);
            case "rainStrength"->values.setFloat(name,rain);
            case "thunderStrength"->values.setFloat(name,thunder);
            case "isEyeInWater"->values.setInt(name,submersion);
            case "eyeAltitude"->values.setFloat(name,altitude);
            case "eyeBrightness"->values.setIntVector(name,blockLight,skyLight);
            case "screenBrightness"->values.setFloat(name,brightness);
            case "pi"->values.setFloat(name,(float)Math.PI);
            default->{return false;}
        }
        return true;
    }

    private static float clampWeather(float value){
        if(!Float.isFinite(value))throw new IllegalStateException("Shader-pack weather snapshot must be finite");
        return Math.max(0,Math.min(1,value));
    }
}
