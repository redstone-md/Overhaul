package dev.overhaul.minecraft.world;

import dev.overhaul.core.spatial.HorizontalCellIndex;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player grid valid for one ChunkMap tick, in which players do not move. Outside that
 * scope, or for few players, callers keep the game's plain per-player loop.
 */
public final class EntityTrackingScope {
    /** Below this a distance check per player costs less than probing cells. */
    public static final int MIN_PLAYERS = 16;
    /** Wider footprints probe more cells than players are worth checking. */
    public static final int MAX_CELL_SPAN = 8;
    private static final int CELL_SHIFT = 7;
    private static final ThreadLocal<EntityTrackingScope> CURRENT = ThreadLocal.withInitial(EntityTrackingScope::new);
    private final HorizontalCellIndex<ServerPlayer> grid = new HorizontalCellIndex<>(CELL_SHIFT);
    private List<ServerPlayer> indexed;
    private int indexedSize, depth;
    private boolean open;

    private EntityTrackingScope() {}

    public static void begin() {
        EntityTrackingScope scope = CURRENT.get();
        scope.open = true;
        scope.indexed = null;
    }

    public static void end() {
        EntityTrackingScope scope = CURRENT.get();
        scope.open = false;
        scope.indexed = null;
        scope.grid.begin(0);
    }

    /** The scope indexing these players, or null to use the plain loop. Pair with {@link #exit()}. */
    public static EntityTrackingScope enter(List<ServerPlayer> players) {
        EntityTrackingScope scope = CURRENT.get();
        if (!scope.open) return null;
        if (players != scope.indexed || players.size() != scope.indexedSize) {
            // A nested update from a pairing callback must not rebuild under the outer traversal.
            if (scope.depth > 0) return null;
            scope.rebuild(players);
        }
        scope.depth++;
        return scope;
    }

    public void exit() { depth--; }

    public HorizontalCellIndex<ServerPlayer> grid() { return grid; }

    private void rebuild(List<ServerPlayer> players) {
        grid.begin(players.size());
        for (ServerPlayer player : players) grid.add(player, player.getX(), player.getZ());
        indexed = players;
        indexedSize = players.size();
    }
}
