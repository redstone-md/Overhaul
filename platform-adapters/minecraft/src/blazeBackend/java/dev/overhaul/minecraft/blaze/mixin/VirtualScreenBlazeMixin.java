package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import dev.overhaul.minecraft.blaze.MinecraftBlazeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.VirtualScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** A failed Window constructor cannot publish a partial native device into its client. */
@Mixin(VirtualScreen.class)
public abstract class VirtualScreenBlazeMixin {
    @Shadow @Final private Minecraft minecraft;
    @WrapMethod(method="newWindow")
    private Window overhaul$construct(DisplayData display,String mode,String title,Operation<Window> original){
        var owner=(MinecraftBlazeAccess)minecraft;var previous=owner.overhaul$blazeRenderer();
        try{return original.call(display,mode,title);}
        catch(RuntimeException|Error failure){
            var installed=owner.overhaul$blazeRenderer();
            if(installed!=null&&installed!=previous){
                owner.overhaul$removeBlazeRenderer(installed);
                try{installed.abortWindowConstruction();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            }
            throw failure;
        }
    }
}
