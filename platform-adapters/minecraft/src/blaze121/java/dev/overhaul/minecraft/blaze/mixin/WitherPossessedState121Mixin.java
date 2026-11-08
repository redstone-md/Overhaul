package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeIdleState;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets the possession handler see that a mob's saved possession state is still the fresh default: not possessed, no
 * cooldown, no possessor and nothing pending to send. Ticking that state writes the same values back.
 */
@Pseudo
@Mixin(targets = "com.alexander.witherreincarnated.capabilities.possessed.PossessedCapability", remap = false)
abstract class WitherPossessedState121Mixin implements BlazeIdleState {
    @Shadow public int possessionTime;
    @Shadow public int possessedFor;
    @Shadow public int possessionCooldown;
    @Shadow public LivingEntity possessedBy;
    @Shadow public UUID possessedByUUID;
    @Shadow public boolean wasPossessed;
    @Shadow public boolean worldLoaded;

    @Override
    public boolean overhaul$idle() {
        return possessionTime <= 0 && possessedFor == 0 && possessionCooldown <= 0 && possessedBy == null
                && possessedByUUID == null && !wasPossessed && !worldLoaded;
    }
}
