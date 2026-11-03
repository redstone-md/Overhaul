package dev.overhaul.minecraft.weather;

import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Copies pinned weather expansion results once; the GPU selects these exact endpoint floats. */
public final class WeatherInstanceWriter {
    private final ByteBuffer target;
    private final int expected,radius;
    private final WeatherGeometryAccess geometry;
    private final Vec3 camera;
    private final float nearOpacity,intensity;
    private int written;

    public WeatherInstanceWriter(ByteBuffer target,int count,WeatherGeometryAccess geometry,Vec3 camera,float nearOpacity,int radius,float intensity){
        this.target=Objects.requireNonNull(target).order(ByteOrder.nativeOrder());this.expected=count;
        this.geometry=Objects.requireNonNull(geometry);this.camera=Objects.requireNonNull(camera);this.nearOpacity=nearOpacity;this.radius=radius;this.intensity=intensity;
        if(count<0||target.remaining()!=(long)count*InstancedWeatherPipelines.INSTANCE_STRIDE)throw new IllegalArgumentException("Weather allocation must contain exactly the expected records");
    }
    public void write(WeatherEffectRenderer.ColumnInstance column){
        if(written==expected)throw new IllegalStateException("Weather column storage grew during preparation");
        float x=(float)(column.x()+.5-camera.x),z=(float)(column.z()+.5-camera.z);
        float squaredRadius=radius*radius;
        float alpha=Mth.lerp(Math.min(Mth.lengthSquared(x,z)/squaredRadius,1f),nearOpacity,.5f)*intensity;
        int color=ARGB.white(alpha);
        int index=(column.z()-Mth.floor(camera.z)+16)*32+column.x()-Mth.floor(camera.x)+16;
        float halfX=geometry.overhaul$columnX(index)/2f,halfZ=geometry.overhaul$columnZ(index)/2f;
        float leftX=x-halfX,rightX=x+halfX,topY=(float)(column.topY()-camera.y),bottomY=(float)(column.bottomY()-camera.y);
        float leftZ=z-halfZ,rightZ=z+halfZ,u0=column.uOffset()+0f,u1=column.uOffset()+1f;
        float vBottom=(float)column.bottomY()*.25f+column.vOffset(),vTop=(float)column.topY()*.25f+column.vOffset();
        target.putFloat(leftX).putFloat(leftZ).putFloat(rightX).putFloat(rightZ).putFloat(topY).putFloat(bottomY)
                .putFloat(u0).putFloat(u1).putFloat(vBottom).putFloat(vTop).putInt(color).putInt(column.lightCoords());written++;
    }
    public void finish(){if(written!=expected)throw new IllegalStateException("Weather column storage changed during preparation: "+written+" != "+expected);}
}
