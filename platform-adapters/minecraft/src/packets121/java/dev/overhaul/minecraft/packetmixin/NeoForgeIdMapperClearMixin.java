package dev.overhaul.minecraft.packetmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.packet.IdMapperSnapshotAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** NeoForge's real registry-bake clear writes the base mapper's fields directly. Registered only on NeoForge. */
@Pseudo
@Mixin(targets="net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks$ClearableObjectIntIdentityMap",remap=false)
public abstract class NeoForgeIdMapperClearMixin {
    @WrapMethod(method="clear()V")
    private void overhaul$clear(Operation<Void> original){((IdMapperSnapshotAccess)this).overhaul$mutateIdentity(()->original.call());}
}
