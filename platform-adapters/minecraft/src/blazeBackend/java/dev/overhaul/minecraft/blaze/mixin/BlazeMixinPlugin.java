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
            Map.entry("LevelRendererSections121Mixin", BlazeOptimization.SECTION_LISTS),
            Map.entry("BufferBuilderBulk121Mixin", BlazeOptimization.BULK_VERTICES),
            Map.entry("ModelCube121Mixin", BlazeOptimization.CUBE_MESH),
            Map.entry("ItemQuads121Mixin", BlazeOptimization.ITEM_QUADS),
            Map.entry("EntityTypeRenderer121Mixin", BlazeOptimization.ENTITY_RENDERER_CACHE),
            Map.entry("EntityRendererLookup121Mixin", BlazeOptimization.ENTITY_RENDERER_CACHE),
            Map.entry("ClientLevelBiomes121Mixin", BlazeOptimization.BIOME_CACHE),
            Map.entry("FogRendererBiomes121Mixin", BlazeOptimization.BIOME_CACHE),
            Map.entry("SpriteInterpolationBlazeMixin", BlazeOptimization.SPRITE_INTERPOLATION),
            Map.entry("XaeroMapWriter121Mixin", BlazeOptimization.XAERO_LIMITS),
            Map.entry("XaeroRadar121Mixin", BlazeOptimization.XAERO_LIMITS));

    @Override public void onLoad(String mixinPackage) { BlazeOptimization.describeInFile(); }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        BlazeOptimization owner = OWNERS.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
        return owner == null || owner.on();
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
