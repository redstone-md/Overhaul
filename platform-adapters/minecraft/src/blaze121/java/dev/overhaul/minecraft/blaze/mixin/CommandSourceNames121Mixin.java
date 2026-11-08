package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Datapack functions run {@code execute as @e} over every entity each tick, and each target built its name string
 * and its styled display name with a hover tooltip, which only chat feedback ever reads. A source made for an entity
 * now leaves both unset, and they are taken from that entity the first time something asks. Every other way to
 * derive a source keeps the entity and copies the unset names along, so they still name that entity.
 */
@Mixin(CommandSourceStack.class)
abstract class CommandSourceNames121Mixin {
    @Shadow @Final @Mutable private String textName;
    @Shadow @Final @Mutable private Component displayName;
    @Shadow @Final private Entity entity;

    @Redirect(method = "withEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getName()Lnet/minecraft/network/chat/Component;"), require = 0)
    private Component overhaul$noName(Entity target) {
        return null;
    }

    @Redirect(method = "withEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"), require = 0)
    private String overhaul$noText(Component name) {
        return null;
    }

    @Redirect(method = "withEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDisplayName()Lnet/minecraft/network/chat/Component;"), require = 0)
    private Component overhaul$noDisplayName(Entity target) {
        return null;
    }

    @Inject(method = "getTextName", at = @At("HEAD"), require = 0)
    private void overhaul$nameText(CallbackInfoReturnable<String> cir) {
        if (textName == null && entity != null) textName = entity.getName().getString();
    }

    @Inject(method = "getDisplayName", at = @At("HEAD"), require = 0)
    private void overhaul$nameDisplay(CallbackInfoReturnable<Component> cir) {
        if (displayName == null && entity != null) displayName = entity.getDisplayName();
    }
}
