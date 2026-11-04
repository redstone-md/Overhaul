package dev.overhaul.minecraft.mixin;

import dev.overhaul.core.spatial.HorizontalCellIndex;
import dev.overhaul.minecraft.world.EntityTrackingScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * For a player that is out of range and not already seeing the entity the game's
 * updatePlayer changes nothing, so only the others are visited. Their own
 * updatePlayer call still decides visibility, and mods hooking it still run.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityIndexMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow public abstract void updatePlayer(ServerPlayer player);
    @Shadow private int getEffectiveRange() { throw new AssertionError(); }

    @Inject(method = "updatePlayers(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void overhaul$updateNearby(List<ServerPlayer> players, CallbackInfo callback) {
        if (players.size() < EntityTrackingScope.MIN_PLAYERS) return;
        EntityTrackingScope scope = EntityTrackingScope.enter(players);
        if (scope == null) return;
        try {
            if (overhaul$visit(scope.grid(), players)) callback.cancel();
        } finally {
            scope.exit();
        }
    }

    @Unique
    private boolean overhaul$visit(HorizontalCellIndex<ServerPlayer> grid, List<ServerPlayer> players) {
        // The effective range bounds the game's check, which also limits it by view distance.
        double range = getEffectiveRange(), x = entity.getX(), z = entity.getZ();
        int minX = grid.cell(x - range), maxX = grid.cell(x + range), minZ = grid.cell(z - range), maxZ = grid.cell(z + range);
        if (maxX - minX >= EntityTrackingScope.MAX_CELL_SPAN || maxZ - minZ >= EntityTrackingScope.MAX_CELL_SPAN) return false;
        for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
            for (int cellX = minX; cellX <= maxX; cellX++) {
                for (int cursor = grid.first(cellX, cellZ); cursor >= 0; cursor = grid.next(cursor)) {
                    ServerPlayer player = grid.item(cursor);
                    double dx = player.getX() - x, dz = player.getZ() - z;
                    if (dx * dx + dz * dz <= range * range || seenBy.contains(player.connection)) updatePlayer(player);
                }
            }
        }
        // Viewers beyond the visited cells must still lose sight; updatePlayer edits seenBy, so copy them first.
        List<ServerPlayer> departed = null;
        for (ServerPlayerConnection connection : seenBy) {
            ServerPlayer player = connection.getPlayer();
            int cellX = grid.cell(player.getX()), cellZ = grid.cell(player.getZ());
            if (cellX < minX || cellX > maxX || cellZ < minZ || cellZ > maxZ) {
                if (departed == null) departed = new ArrayList<>();
                departed.add(player);
            }
        }
        if (departed != null) for (ServerPlayer player : departed) if (players.contains(player)) updatePlayer(player);
        return true;
    }
}
