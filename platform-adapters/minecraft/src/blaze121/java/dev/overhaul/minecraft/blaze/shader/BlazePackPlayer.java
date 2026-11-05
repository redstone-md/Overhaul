package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.mixin.TextureAtlas121Accessor;
import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.PackIdMap;
import dev.overhaul.shader.pack.PackSmoothing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

/** Effects, held items, fog and the smoothed values, sampled from the camera entity once per frame. */
final class BlazePackPlayer {
    private static final int GL_LINEAR = 9729, TERRAIN_ICON_SIZE = 16, NO_BOSS = 0;
    private final BlazePackBiome biome = new BlazePackBiome();
    private float wetness, eyeBlock, eyeSky, centerDepth, nightVision, blindness, darkness, darknessLight, mood;
    private float fogStart, fogEnd, fogDensity, health, maxHealth;
    private int fogShape, lightMain, lightOff, itemMain, itemOff, atlasWidth, atlasHeight;
    private boolean hideGui, started;
    private Entity entity;

    void capture(BlazePackCapture frame, float seconds, PackSmoothing smoothing, PackIdMap ids, BlazePackEnvironment environment, float near, float far) {
        Minecraft minecraft = frame.minecraft();
        entity = minecraft.getCameraEntity();
        LivingEntity living = entity instanceof LivingEntity found ? found : null;
        effects(minecraft, living, frame.partial());
        health = living == null ? 0 : living.getHealth();
        maxHealth = living == null ? 0 : living.getMaxHealth();
        mood = minecraft.player instanceof LocalPlayer player ? player.getCurrentMood() : 0;
        lightMain = living == null ? 0 : light(living.getMainHandItem());
        lightOff = living == null ? 0 : light(living.getOffhandItem());
        itemMain = living == null ? PackIdMap.UNKNOWN : id(ids, living.getMainHandItem());
        itemOff = living == null ? PackIdMap.UNKNOWN : id(ids, living.getOffhandItem());
        hideGui = minecraft.options.hideGui;
        fogStart = RenderSystem.getShaderFogStart();
        fogEnd = RenderSystem.getShaderFogEnd();
        fogShape = RenderSystem.getShaderFogShape().getIndex();
        fogDensity = fogEnd > 0 ? 1 / fogEnd : 0;
        TextureAtlas atlas = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        atlasWidth = ((TextureAtlas121Accessor) atlas).overhaul$width();
        atlasHeight = ((TextureAtlas121Accessor) atlas).overhaul$height();
        float depth = centerDepth(minecraft, near, far), rain = environment.rain(), block = environment.blockLight(), sky = environment.skyLight();
        if (!started) {
            wetness = rain;
            eyeBlock = block;
            eyeSky = sky;
            centerDepth = depth;
            started = true;
        } else {
            wetness = PackSmoothing.step(wetness, rain, rain > wetness ? smoothing.wetness() : smoothing.dryness(), seconds);
            eyeBlock = PackSmoothing.step(eyeBlock, block, smoothing.eyeBrightness(), seconds);
            eyeSky = PackSmoothing.step(eyeSky, sky, smoothing.eyeBrightness(), seconds);
            centerDepth = PackSmoothing.step(centerDepth, depth, smoothing.centerDepth(), seconds);
        }
        biome.invalidate(minecraft);
    }

    private void effects(Minecraft minecraft, LivingEntity living, float partial) {
        nightVision = blindness = darkness = darknessLight = 0;
        if (living == null) return;
        if (living.hasEffect(MobEffects.NIGHT_VISION)) nightVision = GameRenderer.getNightVisionScale(living, partial);
        var blind = living.getEffect(MobEffects.BLINDNESS);
        if (blind != null) blindness = blind.isInfiniteDuration() ? 1 : Math.min(1, blind.getDuration() / 20f);
        var dark = living.getEffect(MobEffects.DARKNESS);
        float blend = dark == null ? 0 : dark.getBlendFactor(living, partial);
        darkness = blend * minecraft.options.darknessEffectScale().get().floatValue();
        darknessLight = Math.max(0, (float) Math.cos((living.tickCount - partial) * Math.PI * 0.025f) * 0.45f * blend);
    }

    private static int id(PackIdMap ids, ItemStack stack) {
        return stack.isEmpty() ? PackIdMap.UNKNOWN : ids.item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static int light(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState().getLightEmission() : 0;
    }

    /** Depth of what the crosshair hits, so reading it costs no GPU readback; a miss reads as the far plane. */
    private static float centerDepth(Minecraft minecraft, float near, float far) {
        HitResult hit = minecraft.hitResult;
        var camera = minecraft.getCameraEntity();
        if (hit == null || hit.getType() == HitResult.Type.MISS || camera == null) return 1;
        float distance = (float) hit.getLocation().distanceTo(camera.getEyePosition());
        if (distance <= near) return 0;
        if (distance >= far) return 1;
        return far * (distance - near) / (distance * (far - near));
    }

    void reset() {
        started = false;
    }

    boolean fill(String name, UniformBlock values) {
        switch (name) {
            case "wetness" -> values.setFloat(name, wetness);
            case "eyeBrightnessSmooth" -> values.setIntVector(name, Math.round(eyeBlock), Math.round(eyeSky));
            case "centerDepthSmooth" -> values.setFloat(name, centerDepth);
            case "nightVision" -> values.setFloat(name, nightVision);
            case "blindness" -> values.setFloat(name, blindness);
            case "darknessFactor" -> values.setFloat(name, darkness);
            case "darknessLightFactor" -> values.setFloat(name, darknessLight);
            case "playerMood" -> values.setFloat(name, mood);
            case "fogStart", "overhaulFogStart" -> values.setFloat(name, fogStart);
            case "fogEnd", "overhaulFogEnd" -> values.setFloat(name, fogEnd);
            case "fogDensity", "overhaulFogDensity" -> values.setFloat(name, fogDensity);
            case "overhaulFogScale" -> values.setFloat(name, fogEnd > fogStart ? 1 / (fogEnd - fogStart) : 0);
            case "fogMode" -> values.setInt(name, GL_LINEAR);
            case "fogShape" -> values.setInt(name, fogShape);
            case "hideGUI" -> values.setInt(name, hideGui ? 1 : 0);
            case "heldBlockLightValue" -> values.setInt(name, lightMain);
            case "heldBlockLightValue2" -> values.setInt(name, lightOff);
            case "heldItemId" -> values.setInt(name, itemMain);
            case "heldItemId2" -> values.setInt(name, itemOff);
            case "entityId", "blockEntityId", "currentRenderedItemId" -> values.setInt(name, PackIdMap.UNKNOWN);
            case "atlasSize", "terrainTextureSize" -> values.setIntVector(name, atlasWidth, atlasHeight);
            case "terrainIconSize" -> values.setInt(name, TERRAIN_ICON_SIZE);
            case "bossBattle" -> values.setInt(name, NO_BOSS);
            case "endFlashIntensity" -> values.setFloat(name, 0);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Reads what custom uniform expressions may name; a name this class does not own is an error in the pack. */
    double read(String name, int component) {
        return switch (name) {
            case "wetness" -> wetness;
            case "eyeBrightnessSmooth" -> component == 0 ? Math.round(eyeBlock) : Math.round(eyeSky);
            case "centerDepthSmooth" -> centerDepth;
            case "nightVision" -> nightVision;
            case "blindness" -> blindness;
            case "darknessFactor" -> darkness;
            case "darknessLightFactor" -> darknessLight;
            case "playerMood" -> mood;
            case "fogStart" -> fogStart;
            case "fogEnd" -> fogEnd;
            case "fogDensity" -> fogDensity;
            case "fogMode" -> GL_LINEAR;
            case "fogShape" -> fogShape;
            case "hideGUI" -> hideGui ? 1 : 0;
            case "heldBlockLightValue" -> lightMain;
            case "heldBlockLightValue2" -> lightOff;
            case "heldItemId" -> itemMain;
            case "heldItemId2" -> itemOff;
            case "entityId", "blockEntityId", "currentRenderedItemId" -> PackIdMap.UNKNOWN;
            case "atlasSize", "terrainTextureSize" -> component == 0 ? atlasWidth : atlasHeight;
            case "terrainIconSize" -> TERRAIN_ICON_SIZE;
            case "bossBattle" -> NO_BOSS;
            case "endFlashIntensity" -> 0;
            case "is_alive" -> flag(entity != null && entity.isAlive());
            case "is_burning" -> flag(entity != null && entity.isOnFire());
            case "is_child" -> flag(entity instanceof LivingEntity living && living.isBaby());
            case "is_glowing" -> flag(entity != null && entity.isCurrentlyGlowing());
            case "is_hurt" -> flag(entity instanceof LivingEntity living && living.hurtTime > 0);
            case "is_in_lava" -> flag(entity != null && entity.isInLava());
            case "is_in_water" -> flag(entity != null && entity.isInWater());
            case "is_invisible" -> flag(entity != null && entity.isInvisible());
            case "is_on_ground" -> flag(entity != null && entity.onGround());
            case "is_ridden" -> flag(entity != null && entity.isVehicle());
            case "is_riding" -> flag(entity != null && entity.isPassenger());
            case "is_sneaking" -> flag(entity != null && entity.isCrouching());
            case "is_sprinting" -> flag(entity != null && entity.isSprinting());
            case "is_wet" -> flag(entity != null && entity.isInWaterOrRain());
            case "health" -> health;
            case "max_health" -> maxHealth;
            default -> {
                double value = biome.read(name);
                if (Double.isNaN(value)) throw new UnsupportedShaderException("Custom uniform reads an unbound value: " + name);
                yield value;
            }
        };
    }

    private static double flag(boolean value) {
        return value ? 1 : 0;
    }
}
