package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.blaze.BlazeBakedQuadWriter;
import dev.overhaul.minecraft.blaze.BlazeBulkVertices;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only the call NeoForge adds matches, so a loader without it keeps the ordinary path. Foil and other wrapping consumers do too. */
@Mixin(ItemRenderer.class)
abstract class ItemQuads121Mixin {
    @WrapOperation(method="renderQuadList",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFIIZ)V"),require=0)
    private void overhaul$putQuad(VertexConsumer consumer,PoseStack.Pose pose,BakedQuad quad,float red,float green,float blue,float alpha,int light,int overlay,boolean readExistingColor,Operation<Void> original){
        if(readExistingColor&&consumer instanceof BlazeBulkVertices target&&BlazeBakedQuadWriter.write(target,pose,quad,red,green,blue,alpha,light,overlay))return;
        original.call(consumer,pose,quad,red,green,blue,alpha,light,overlay,readExistingColor);
    }
}
