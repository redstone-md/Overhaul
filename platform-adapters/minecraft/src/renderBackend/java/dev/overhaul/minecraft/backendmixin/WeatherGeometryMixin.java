package dev.overhaul.minecraft.backendmixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.weather.WeatherGeometryAccess;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.List;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherGeometryMixin implements WeatherGeometryAccess {
    @Shadow @Final private float[] columnSizeX;
    @Shadow @Final private float[] columnSizeZ;
    @Override public float overhaul$columnX(int index){return columnSizeX[index];}
    @Override public float overhaul$columnZ(int index){return columnSizeZ[index];}
    @Override @Invoker("prepareInstances")
    public abstract void overhaul$prepareReference(VertexConsumer output,List<WeatherEffectRenderer.ColumnInstance> columns,
            Vec3 camera,float nearOpacity,int radius,float intensity);
}
