package dev.overhaul.minecraft.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Objects;
import net.minecraft.world.level.pathfinder.Node;

/**
 * Uses the evaluator's authoritative search map, without a capturing function per
 * lookup. Vanilla prepare() clears that same map; no independent cache requires
 * invalidation, and no world or mob reference is retained here.
 */
public final class PathNodeLookup {
    private final Int2ObjectMap<Node> nodes;

    public PathNodeLookup(Int2ObjectMap<Node> nodes) {
        this.nodes = Objects.requireNonNull(nodes, "nodes");
    }

    /** Confined to the same search owner as NodeEvaluator; coordinates retain vanilla hash aliases. */
    public Node getOrCreate(int x, int y, int z) {
        int key = Node.createHash(x, y, z);
        Node existing = nodes.get(key);
        // Match fastutil's primitive computeIfAbsent semantics, including a
        // changed default return value or an explicitly present null mapping.
        if (existing != nodes.defaultReturnValue() || nodes.containsKey(key)) return existing;
        Node created = new Node(x, y, z);
        nodes.put(key, created);
        return created;
    }
}
