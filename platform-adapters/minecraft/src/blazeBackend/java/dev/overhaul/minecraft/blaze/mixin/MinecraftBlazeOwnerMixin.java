package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.MinecraftBlazeAccess;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Minecraft.class)
public abstract class MinecraftBlazeOwnerMixin implements MinecraftBlazeAccess {
    @Unique private volatile BlazeRenderer overhaul$blazeRenderer;
    @Unique private volatile boolean overhaul$blazeWasSelected;
    @Override public BlazeRenderer overhaul$blazeRenderer(){return overhaul$blazeRenderer;}
    @Override public boolean overhaul$blazeWasSelected(){return overhaul$blazeWasSelected;}
    @Override public void overhaul$installBlazeRenderer(BlazeRenderer renderer){
        Objects.requireNonNull(renderer,"renderer");
        if(overhaul$blazeRenderer!=null&&overhaul$blazeRenderer!=renderer)throw new IllegalStateException("Blaze3D device is already selected");
        overhaul$blazeWasSelected=true;overhaul$blazeRenderer=renderer;
    }
    @Override public void overhaul$removeBlazeRenderer(BlazeRenderer renderer){if(overhaul$blazeRenderer==renderer)overhaul$blazeRenderer=null;}
}
