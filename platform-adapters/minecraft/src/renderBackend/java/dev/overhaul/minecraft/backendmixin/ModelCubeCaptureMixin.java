package dev.overhaul.minecraft.backendmixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.model.ModelCubeCapture;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps vanilla animation/hierarchy traversal and intercepts only an explicitly owned capture consumer. */
@Mixin(ModelPart.Cube.class)
public abstract class ModelCubeCaptureMixin {
    @Inject(method="compile",at=@At("HEAD"),cancellable=true)
    private void overhaul$capture(PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color,CallbackInfo callback){
        if(consumer instanceof ModelCubeCapture capture){
            capture.overhaul$captureCube((ModelPart.Cube)(Object)this,pose,light,overlay,color);
            callback.cancel();
        }
    }
}
