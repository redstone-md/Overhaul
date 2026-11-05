package dev.overhaul.minecraft.blaze.shader;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** What the level renderer was handed for this frame; shader-pack uniforms are derived from nothing else. */
record BlazePackCapture(Minecraft minecraft, Camera camera, float partial, Matrix4f view, Matrix4f projection, int width, int height) {
}
