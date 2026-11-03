package dev.overhaul.minecraft.modelmixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Allows copying the real lightmap for the development-only native comparison. */
@Mixin(Lightmap.class)
public abstract class LightmapReadbackMixin {
    @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"),index=1,require=1)
    private int overhaul$allowDiagnosticCopy(int usage) { return usage|GpuTexture.USAGE_COPY_SRC; }
}
