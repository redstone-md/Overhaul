package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeScoreHolders;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps, beside each objective, the names of the holders given a score in it: every score is made through
 * {@code getOrCreatePlayerScore} or read in by {@code loadPlayerScores}, and a name is noted before the score exists.
 * Names leave as their scores are reset or the objective is removed. The sets may hold a name whose score was never
 * made, never miss one that was.
 */
@Mixin(Scoreboard.class)
abstract class ScoreboardHolders121Mixin implements BlazeScoreHolders {
    @Unique private final Reference2ObjectOpenHashMap<Objective, Set<String>> overhaul$holders = new Reference2ObjectOpenHashMap<>();

    @Override
    public Set<String> overhaul$holders(Objective objective) {
        return overhaul$holders.get(objective);
    }

    @Unique
    private void overhaul$note(String name, Objective objective) {
        overhaul$holders.computeIfAbsent(objective, ignored -> new ObjectOpenHashSet<>()).add(name);
    }

    @Unique
    private void overhaul$drop(String name, Objective objective) {
        Set<String> names = overhaul$holders.get(objective);
        if (names != null && names.remove(name) && names.isEmpty()) overhaul$holders.remove(objective);
    }

    @Inject(method = "getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;",
            at = @At("HEAD"), require = 1)
    private void overhaul$scored(ScoreHolder holder, Objective objective, boolean readOnly, CallbackInfoReturnable<ScoreAccess> callback) {
        overhaul$note(holder.getScoreboardName(), objective);
    }

    @Inject(method = "loadPlayerScores", at = @At("RETURN"), require = 1)
    private void overhaul$loaded(ListTag scores, HolderLookup.Provider registries, CallbackInfo callback) {
        Scoreboard board = (Scoreboard) (Object) this;
        for (ScoreHolder holder : board.getTrackedPlayers()) {
            for (Objective objective : board.listPlayerScores(holder).keySet()) overhaul$note(holder.getScoreboardName(), objective);
        }
    }

    @Inject(method = "resetSinglePlayerScore", at = @At("HEAD"), require = 1)
    private void overhaul$resetOne(ScoreHolder holder, Objective objective, CallbackInfo callback) {
        overhaul$drop(holder.getScoreboardName(), objective);
    }

    @Inject(method = "resetAllPlayerScores", at = @At("HEAD"), require = 1)
    private void overhaul$resetAll(ScoreHolder holder, CallbackInfo callback) {
        String name = holder.getScoreboardName();
        for (Objective objective : ((Scoreboard) (Object) this).listPlayerScores(holder).keySet()) overhaul$drop(name, objective);
    }

    @Inject(method = "removeObjective", at = @At("HEAD"), require = 1)
    private void overhaul$removed(Objective objective, CallbackInfo callback) {
        overhaul$holders.remove(objective);
    }
}
