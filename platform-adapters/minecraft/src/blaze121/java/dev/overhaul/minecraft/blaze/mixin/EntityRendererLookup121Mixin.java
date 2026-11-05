package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeEntityTypeRenderer;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wraps only the map read, so other mods still see getRenderer run. Player skin models are not entity types and keep the map. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRendererLookup121Mixin {
    @WrapOperation(method="getRenderer",at=@At(value="INVOKE",target="Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object overhaul$typeRenderer(Map<?,?> renderers,Object key,Operation<Object> lookup){
        if(!(key instanceof BlazeEntityTypeRenderer type))return lookup.call(renderers,key);
        if(type.overhaul$rendererSource()==renderers)return type.overhaul$renderer();
        Object renderer=lookup.call(renderers,key);
        type.overhaul$renderer(renderers,(EntityRenderer<?>)renderer);
        return renderer;
    }
}
