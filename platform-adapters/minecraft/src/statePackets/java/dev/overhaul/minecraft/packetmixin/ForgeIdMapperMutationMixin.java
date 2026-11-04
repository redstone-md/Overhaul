package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.packet.IdMapperSnapshotAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Forge's actual clear/remove paths bypass IdMapper.addMapping. Registered only on Forge. */
@Pseudo
@Mixin(targets="net.minecraftforge.registries.GameData$ClearableObjectIntIdentityMap",remap=false)
public abstract class ForgeIdMapperMutationMixin {
    @WrapMethod(method="clear()V")
    private void overhaul$clear(Operation<Void> original){((IdMapperSnapshotAccess)this).overhaul$mutateIdentity(()->original.call());}
    @WrapMethod(method="remove(Ljava/lang/Object;)V")
    private void overhaul$remove(Object value,Operation<Void> original){((IdMapperSnapshotAccess)this).overhaul$mutateIdentity(()->original.call(value));}
}
