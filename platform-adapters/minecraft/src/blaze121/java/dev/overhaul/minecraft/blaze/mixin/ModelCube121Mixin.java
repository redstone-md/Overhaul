package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.blaze.BlazeBulkVertices;
import dev.overhaul.minecraft.blaze.BlazeCubeMesh;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Late priority places this after other HEAD edits of the arguments, such as Shoulder Surfing's camera alpha.
@Mixin(value=ModelPart.Cube.class,priority=2000)
abstract class ModelCube121Mixin {
    @Unique private BlazeCubeMesh overhaul$mesh;

    @Inject(method="compile",at=@At("HEAD"),cancellable=true)
    private void overhaul$compile(PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color,CallbackInfo info){
        if(!(consumer instanceof BlazeBulkVertices target))return;
        BlazeCubeMesh mesh=overhaul$mesh;
        if(mesh==null)overhaul$mesh=mesh=BlazeCubeMesh.of((ModelPart.Cube)(Object)this);
        if(mesh.write(pose,target,light,overlay,color))info.cancel();
    }
}
