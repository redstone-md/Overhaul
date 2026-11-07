package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeOptimization;
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
            Map.entry("DhClouds121Mixin", BlazeOptimization.DH_CLOUDS),
            Map.entry("DhRenderList121Mixin", BlazeOptimization.DH_RENDER_LIST),
            Map.entry("DhVertexBufferBlazeMixin", BlazeOptimization.DH_DEVICE_CACHE),
            Map.entry("ViewAreaColumns121Mixin", BlazeOptimization.SECTION_GRID),
            Map.entry("BufferBuilderParticles121Mixin", BlazeOptimization.PARTICLE_QUADS),
            Map.entry("ParticleQuads121Mixin", BlazeOptimization.PARTICLE_QUADS),
            Map.entry("LevelEntityClasses121Mixin", BlazeOptimization.ENTITY_CLASS_SCANS),
            Map.entry("EntitySectionManagerClasses121Mixin", BlazeOptimization.ENTITY_CLASS_SCANS),
            Map.entry("EntityLight121Mixin", BlazeOptimization.ENTITY_LIGHT),
            Map.entry("EntityDispatcherLight121Mixin", BlazeOptimization.ENTITY_LIGHT),
            Map.entry("EntitySelectorAlive121Mixin", BlazeOptimization.SELECTOR_ORDER),
            Map.entry("ServerEntityClasses121Mixin", BlazeOptimization.SERVER_CLASS_SCANS),
            Map.entry("BornInChaosAnimationTick121Mixin", BlazeOptimization.MOD_ENTITY_TICKS),
            Map.entry("BufferBuilderBlockIds121Mixin", BlazeOptimization.PACK_BLOCK_IDS),
            Map.entry("SectionCompilerBlockIds121Mixin", BlazeOptimization.PACK_BLOCK_IDS));

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
        BlazeOptimization owner = OWNERS.get(name);
        return owner == null || owner.on();
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
