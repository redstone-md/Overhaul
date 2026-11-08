package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.ParticleMoveCheck;
import dev.overhaul.minecraft.blaze.RainHeightCheck;
import dev.overhaul.minecraft.blaze.SableCullPatch;
import dev.overhaul.minecraft.blaze.VeilSkeletonPatch;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** A switched-off optimisation's mixins are never applied, so a comparison run carries none of its code. */
public final class BlazeMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String, BlazeOptimization> OWNERS = Map.ofEntries(
            Map.entry("LevelRendererSectionBatch121Mixin", BlazeOptimization.SECTION_BATCH),
            Map.entry("LevelRendererSectionBatch120Mixin", BlazeOptimization.SECTION_BATCH),
            Map.entry("LevelRendererEntityOcclusion121Mixin", BlazeOptimization.ENTITY_OCCLUSION),
            Map.entry("LevelRendererBlockEntityOcclusion121Mixin", BlazeOptimization.BLOCK_ENTITY_OCCLUSION),
            Map.entry("SectionRenderDispatcherCompact121Mixin", BlazeOptimization.COMPACT_SECTIONS),
            Map.entry("LevelRendererSections121Mixin", BlazeOptimization.SECTION_LISTS),
            Map.entry("BufferBuilderBulk121Mixin", BlazeOptimization.BULK_VERTICES),
            Map.entry("ModelCube121Mixin", BlazeOptimization.CUBE_MESH),
            Map.entry("ItemQuads121Mixin", BlazeOptimization.ITEM_QUADS),
            Map.entry("EntityTypeRenderer121Mixin", BlazeOptimization.ENTITY_RENDERER_CACHE),
            Map.entry("EntityRendererLookup121Mixin", BlazeOptimization.ENTITY_RENDERER_CACHE),
            Map.entry("ClientLevelBiomes121Mixin", BlazeOptimization.BIOME_CACHE),
            Map.entry("FogRendererBiomes121Mixin", BlazeOptimization.BIOME_CACHE),
            Map.entry("SpriteInterpolationBlazeMixin", BlazeOptimization.SPRITE_INTERPOLATION),
            Map.entry("DebugTextBlazeMixin", BlazeOptimization.DEBUG_TEXT),
            Map.entry("RenderSectionBuffers121Mixin", BlazeOptimization.SECTION_BATCH),
            Map.entry("XaeroMapWriter121Mixin", BlazeOptimization.XAERO_LIMITS),
            Map.entry("XaeroRadar121Mixin", BlazeOptimization.XAERO_LIMITS),
            Map.entry("XaeroMapProcessor121Mixin", BlazeOptimization.XAERO_LIMITS),
            Map.entry("ShoulderCrosshair121Mixin", BlazeOptimization.CROSSHAIR_LIMIT),
            Map.entry("SectionOcclusionFrustum121Mixin", BlazeOptimization.SECTION_BOUNDS),
            Map.entry("RenderSectionOrigin121Mixin", BlazeOptimization.SECTION_BOUNDS),
            Map.entry("CataclysmScans121Mixin", BlazeOptimization.ENTITY_SCANS),
            Map.entry("YoukaiHeadCurios121Mixin", BlazeOptimization.HEAD_CURIOS),
            Map.entry("AlexsCavesScans121Mixin", BlazeOptimization.ENTITY_SCANS),
            Map.entry("WitherGlowScan121Mixin", BlazeOptimization.WITHER_GLOWS),
            Map.entry("WitherMusicScan121Mixin", BlazeOptimization.WITHER_GLOWS),
            Map.entry("LevelRendererVeilSkeletons121Mixin", BlazeOptimization.VEIL_SKELETONS),
            Map.entry("AlexsMobsEffectPoses121Mixin", BlazeOptimization.EFFECT_POSES),
            Map.entry("VillagerLayerTextures121Mixin", BlazeOptimization.VILLAGER_TEXTURES),
            Map.entry("DhClouds121Mixin", BlazeOptimization.DH_CLOUDS),
            Map.entry("DhRenderList121Mixin", BlazeOptimization.DH_RENDER_LIST),
            Map.entry("DhVertexBufferBlazeMixin", BlazeOptimization.DH_DEVICE_CACHE),
            Map.entry("ViewAreaColumns121Mixin", BlazeOptimization.SECTION_GRID),
            Map.entry("BufferBuilderParticles121Mixin", BlazeOptimization.PARTICLE_QUADS),
            Map.entry("ParticleQuads121Mixin", BlazeOptimization.PARTICLE_QUADS),
            Map.entry("LevelEntityClasses121Mixin", BlazeOptimization.ENTITY_CLASS_SCANS),
            Map.entry("EntityLight121Mixin", BlazeOptimization.ENTITY_LIGHT),
            Map.entry("EntityDispatcherLight121Mixin", BlazeOptimization.ENTITY_LIGHT),
            Map.entry("EntitySelectorAlive121Mixin", BlazeOptimization.SELECTOR_ORDER),
            Map.entry("EntitySectionLookup121Mixin", BlazeOptimization.SECTION_LOOKUP),
            Map.entry("EntityCollisions121Mixin", BlazeOptimization.COLLISION_SCANS),
            Map.entry("BornInChaosAnimationTick121Mixin", BlazeOptimization.MOD_ENTITY_TICKS),
            Map.entry("DamageTextSight121Mixin", BlazeOptimization.DAMAGE_TEXT_SIGHT),
            Map.entry("ParticleSableMove121Mixin", BlazeOptimization.SABLE_PARTICLES),
            Map.entry("LevelRendererSableRain121Mixin", BlazeOptimization.SABLE_RAIN),
            Map.entry("EntityRendererSableCull121Mixin", BlazeOptimization.SABLE_ENTITY_CULL),
            Map.entry("AtmosphereWeatherProxies121Mixin", BlazeOptimization.ATMOSPHERE_WEATHER),
            Map.entry("AtmosphereLocalWeather121Access", BlazeOptimization.ATMOSPHERE_WEATHER),
            Map.entry("LevelRendererRainBiomes121Mixin", BlazeOptimization.RAIN_BIOMES),
            Map.entry("ParticleLight121Mixin", BlazeOptimization.PARTICLE_LIGHT),
            Map.entry("ParticleCollisions121Mixin", BlazeOptimization.PARTICLE_COLLISIONS),
            Map.entry("LevelRendererRainLight121Mixin", BlazeOptimization.RAIN_LIGHT),
            Map.entry("LevelHeight121Mixin", BlazeOptimization.LEVEL_HEIGHT),
            Map.entry("EntityBlockCollisions121Mixin", BlazeOptimization.ENTITY_BLOCK_COLLISIONS),
            Map.entry("EntitySupportingBlock121Mixin", BlazeOptimization.SUPPORTING_BLOCK),
            Map.entry("EntityTagCounts121Mixin", BlazeOptimization.SELECTOR_TAGS),
            Map.entry("EntitySelectorParserTags121Mixin", BlazeOptimization.SELECTOR_TAGS),
            Map.entry("EntitySelectorTags121Mixin", BlazeOptimization.SELECTOR_TAGS),
            Map.entry("MoveToBlockSearch121Mixin", BlazeOptimization.BLOCK_SEARCH),
            Map.entry("RemoveBlockGoalAccess121Mixin", BlazeOptimization.BLOCK_SEARCH),
            Map.entry("BufferBuilderBlockIds121Mixin", BlazeOptimization.PACK_BLOCK_IDS),
            Map.entry("SectionCompilerBlockIds121Mixin", BlazeOptimization.PACK_BLOCK_IDS));
    /** Mixins that serve several optimisations, applied while any of them is on. */
    private static final Map<String, List<BlazeOptimization>> SHARED = Map.of(
            "EntitySectionManagerClasses121Mixin", List.of(BlazeOptimization.ENTITY_CLASS_SCANS, BlazeOptimization.COLLISION_SCANS,
                    BlazeOptimization.WITHER_GLOWS, BlazeOptimization.VEIL_SKELETONS),
            "ServerEntityClasses121Mixin", List.of(BlazeOptimization.SERVER_CLASS_SCANS, BlazeOptimization.COLLISION_SCANS));

    @Override public void onLoad(String mixinPackage) {
        BlazeOptimization.describeInFile();
        // The cache also serves builds without this frontend, so it reads a property rather than this enum.
        if (!BlazeOptimization.HUD_CACHE.on()) System.setProperty("overhaul.hudCache", "false");
        // F3 text already refreshes at 10 Hz here, so a 30 Hz HUD shows nothing staler.
        if (BlazeOptimization.DEBUG_TEXT.on() && System.getProperty("overhaul.hudCache.debug") == null) System.setProperty("overhaul.hudCache.debug", "true");
    }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (name.equals("MouseGrabBlazeMixin")) return Boolean.getBoolean("overhaul.dev.noGrab");
        // Vanilla targets: without Sable these wrappers would only add a boxed call to every particle move or rain column.
        if (name.contains("Sable") && BlazeMixinPlugin.class.getClassLoader().getResource("dev/ryanhcode/sable/Sable.class") == null) return false;
        if (name.startsWith("Atmosphere") && BlazeMixinPlugin.class.getClassLoader().getResource("com/possible_triangle/atmosphere/impl/ServerLevelWeather.class") == null) return false;
        List<BlazeOptimization> shared = SHARED.get(name);
        if (shared != null) return shared.stream().anyMatch(BlazeOptimization::on);
        BlazeOptimization owner = OWNERS.get(name);
        return owner == null || owner.on();
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".ParticleSableMove121Mixin")) ParticleMoveCheck.inspect(targetClass, "overhaul$skipIdleSable");
        else if (mixinClassName.endsWith(".LevelRendererSableRain121Mixin")) RainHeightCheck.inspect(targetClass, "overhaul$skipEmptySable");
        else if (mixinClassName.endsWith(".EntityRendererSableCull121Mixin")) SableCullPatch.apply(targetClass);
        else if (mixinClassName.endsWith(".LevelRendererVeilSkeletons121Mixin")) VeilSkeletonPatch.apply(targetClass);
    }
}
