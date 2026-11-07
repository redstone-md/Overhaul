package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.StringReader;
import dev.overhaul.minecraft.blaze.BlazeSelectorTags;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes the tag each {@code tag=name} option requires, read back from the text the option consumed, and hands them to
 * the selector. Inverted {@code tag=!name} and bare {@code tag=} options require nothing and are left out.
 */
@Mixin(EntitySelectorParser.class)
abstract class EntitySelectorParserTags121Mixin {
    @Shadow @Final private StringReader reader;
    @Unique private String overhaul$option;
    @Unique private List<String> overhaul$tags;

    @WrapOperation(method = "parseOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions;get(Lnet/minecraft/commands/arguments/selector/EntitySelectorParser;Ljava/lang/String;I)Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions$Modifier;"),
            require = 1)
    private EntitySelectorOptions.Modifier overhaul$noteOption(EntitySelectorParser parser, String key, int cursor, Operation<EntitySelectorOptions.Modifier> get) {
        overhaul$option = key;
        return get.call(parser, key, cursor);
    }

    @WrapOperation(method = "parseOptions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions$Modifier;handle(Lnet/minecraft/commands/arguments/selector/EntitySelectorParser;)V"),
            require = 1)
    private void overhaul$noteTag(EntitySelectorOptions.Modifier modifier, EntitySelectorParser parser, Operation<Void> handle) {
        int start = reader.getCursor();
        handle.call(modifier, parser);
        if (!"tag".equals(overhaul$option)) return;
        String value = reader.getString().substring(start, reader.getCursor()).trim();
        if (value.isEmpty() || value.charAt(0) == '!') return;
        if (overhaul$tags == null) overhaul$tags = new ArrayList<>(2);
        overhaul$tags.add(value);
    }

    @Inject(method = "getSelector", at = @At("RETURN"))
    private void overhaul$handTags(CallbackInfoReturnable<EntitySelector> info) {
        if (overhaul$tags != null) ((BlazeSelectorTags) info.getReturnValue()).overhaul$requiredTags(overhaul$tags.toArray(String[]::new));
    }
}
