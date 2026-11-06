package dev.overhaul.minecraft.blaze.shader;

import dev.overhaul.shader.compat.UniformBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/** One camera and world snapshot shared by every pass of a frame; it retains no level, camera or entity. */
final class BlazePackEnvironment {
    private int worldTime, worldDay, moonPhase, submersion, blockLight, skyLight, height, bottom, logicalHeight;
    private float red, green, blue, rain, thunder, altitude, brightness, cloudHeight, ambientLight;

    void capture(BlazePackCapture frame) {
        var minecraft = frame.minecraft();
        var level = minecraft.level;
        var position = frame.camera().getPosition();
        float partial = frame.partial();
        long clock = level.getDayTime();
        var dimension = level.dimension();
        boolean fixed = level.dimensionType().hasFixedTime() && !dimension.equals(Level.NETHER) && !dimension.equals(Level.END);
        worldTime = fixed ? 0 : (int) Math.floorMod(clock, 24000L);
        worldDay = (int) (clock / 24000L);
        var sky = level.getSkyColor(position, partial);
        red = (float) sky.x;
        green = (float) sky.y;
        blue = (float) sky.z;
        moonPhase = level.getMoonPhase();
        height = level.getHeight();
        bottom = level.getMinBuildHeight();
        logicalHeight = level.dimensionType().logicalHeight();
        cloudHeight = level.effects().getCloudHeight();
        ambientLight = level.dimensionType().ambientLight();
        rain = weather(level.getRainLevel(partial));
        thunder = weather(level.getThunderLevel(partial));
        altitude = (float) position.y;
        brightness = minecraft.options.gamma().get().floatValue();
        boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
        submersion = switch (frame.camera().getFluidInCamera()) {
            case WATER -> 1;
            case LAVA -> spectator ? 0 : 2;
            case POWDER_SNOW -> 3;
            default -> 0;
        };
        var entity = minecraft.getCameraEntity();
        if (entity == null) {
            blockLight = skyLight = 0;
        } else {
            var eye = BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ());
            blockLight = level.getBrightness(LightLayer.BLOCK, eye) * 16;
            skyLight = level.getBrightness(LightLayer.SKY, eye) * 16;
        }
    }

    boolean fill(String name, UniformBlock values) {
        switch (name) {
            case "worldTime" -> values.setInt(name, worldTime);
            case "worldDay" -> values.setInt(name, worldDay);
            case "moonPhase" -> values.setInt(name, moonPhase);
            case "skyColor" -> values.setVector(name, red, green, blue);
            case "rainStrength" -> values.setFloat(name, rain);
            case "thunderStrength" -> values.setFloat(name, thunder);
            case "isEyeInWater" -> values.setInt(name, submersion);
            case "eyeAltitude" -> values.setFloat(name, altitude);
            case "eyeBrightness" -> values.setIntVector(name, blockLight, skyLight);
            case "screenBrightness" -> values.setFloat(name, brightness);
            case "pi" -> values.setFloat(name, (float) Math.PI);
            case "heightLimit" -> values.setInt(name, height);
            case "bedrockLevel" -> values.setInt(name, bottom);
            case "logicalHeightLimit" -> values.setInt(name, logicalHeight);
            case "cloudHeight" -> values.setFloat(name, cloudHeight);
            case "ambientLight" -> values.setFloat(name, ambientLight);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Reads a value {@link #fill} owns for custom uniform expressions; NaN marks any other name. */
    double read(String name, int component) {
        return switch (name) {
            case "worldTime" -> worldTime;
            case "worldDay" -> worldDay;
            case "moonPhase" -> moonPhase;
            case "skyColor" -> component == 0 ? red : component == 1 ? green : blue;
            case "rainStrength" -> rain;
            case "thunderStrength" -> thunder;
            case "isEyeInWater" -> submersion;
            case "eyeAltitude" -> altitude;
            case "eyeBrightness" -> component == 0 ? blockLight : skyLight;
            case "screenBrightness" -> brightness;
            case "pi" -> Math.PI;
            case "heightLimit" -> height;
            case "bedrockLevel" -> bottom;
            case "logicalHeightLimit" -> logicalHeight;
            case "cloudHeight" -> cloudHeight;
            case "ambientLight" -> ambientLight;
            default -> Double.NaN;
        };
    }

    float rain() {
        return rain;
    }

    int blockLight() {
        return blockLight;
    }

    int skyLight() {
        return skyLight;
    }

    private static float weather(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
