package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;

/** An owned vertex consumer can capture a cube before expansion; the mutable pose is borrowed for this call. */
public interface ModelCubeCapture {
    void overhaul$captureCube(ModelPart.Cube cube,PoseStack.Pose pose,int packedLight,int packedOverlay,int argb);
}
