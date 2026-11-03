package dev.overhaul.minecraft.weather;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Read-only scalar geometry tables and an explicit diagnostic reference path on the actual renderer. */
public interface WeatherGeometryAccess {
    float overhaul$columnX(int index);
    float overhaul$columnZ(int index);
    void overhaul$prepareReference(VertexConsumer output,List<WeatherEffectRenderer.ColumnInstance> columns,
            Vec3 camera,float nearOpacity,int radius,float intensity);
}
