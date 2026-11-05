package foundry.veil.api.client.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class CameraMatrices {
    public native Matrix4f getProjectionMatrix();
    public native Matrix4f getInverseProjectionMatrix();
    public native Matrix4f getViewMatrix();
    public native Matrix4f getInverseViewMatrix();
    public native Matrix3f getInverseViewRotMatrix();
    public native Vector3f getCameraPosition();
    public native Vector3f getCameraBobOffset();
    public native float getNearPlane();
    public native float getFarPlane();
}
