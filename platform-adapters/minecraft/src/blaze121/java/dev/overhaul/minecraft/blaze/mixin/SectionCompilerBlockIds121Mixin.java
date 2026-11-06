package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazePackBlockFormat;
import dev.overhaul.minecraft.blaze.BlazePackBlockIds;
import dev.overhaul.minecraft.blaze.BlazePackVertexTag;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Builds pack terrain in the block-id format and tags each layer buffer with the block about to draw into it and where
 * that block sits in the section: the loop's block position, the third after the section's two corners. The
 * compile loop fetches the buffer right before each fluid and model render, so the tag always names that block.
 * Geometry mods add after the loop keeps the last block's tag.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerBlockIds121Mixin {
    /** Vanilla's compile, and NeoForge's overload that adds section geometry; the vanilla one only delegates there. */
    private static final String VANILLA = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";
    private static final String NEOFORGE = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";
    private static final String LAYER = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;getOrBeginLayer(Ljava/util/Map;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/BufferBuilder;";

    @ModifyArg(method = "getOrBeginLayer", require = 1, index = 2, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;<init>(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;)V"))
    private VertexFormat overhaul$packFormat(VertexFormat format) {
        return BlazePackBlockIds.active() == null ? format : BlazePackBlockFormat.FORMAT;
    }

    @WrapOperation(method = {VANILLA, NEOFORGE}, require = 1, at = @At(value = "INVOKE", target = LAYER, ordinal = 0))
    private BufferBuilder overhaul$fluidLayer(SectionCompiler compiler, Map<RenderType, BufferBuilder> layers, SectionBufferBuilderPack pack,
                                              RenderType type, Operation<BufferBuilder> original, @Local FluidState fluid, @Local(ordinal = 2) BlockPos at) {
        BufferBuilder buffer = original.call(compiler, layers, pack, type);
        if (BlazePackBlockIds.active() != null) {
            BlockState block = fluid.createLegacyBlock();
            ((BlazePackVertexTag) buffer).overhaul$tag(BlazePackBlockFormat.id(block), BlazePackBlockFormat.FLUID, at.getX() & 15, at.getY() & 15, at.getZ() & 15, block.getLightEmission());
        }
        return buffer;
    }

    @WrapOperation(method = {VANILLA, NEOFORGE}, require = 1, at = @At(value = "INVOKE", target = LAYER, ordinal = 1))
    private BufferBuilder overhaul$blockLayer(SectionCompiler compiler, Map<RenderType, BufferBuilder> layers, SectionBufferBuilderPack pack,
                                              RenderType type, Operation<BufferBuilder> original, @Local BlockState state, @Local(ordinal = 2) BlockPos at) {
        BufferBuilder buffer = original.call(compiler, layers, pack, type);
        if (BlazePackBlockIds.active() != null) {
            ((BlazePackVertexTag) buffer).overhaul$tag(BlazePackBlockFormat.id(state), BlazePackBlockFormat.BLOCK, at.getX() & 15, at.getY() & 15, at.getZ() & 15, state.getLightEmission());
        }
        return buffer;
    }
}
