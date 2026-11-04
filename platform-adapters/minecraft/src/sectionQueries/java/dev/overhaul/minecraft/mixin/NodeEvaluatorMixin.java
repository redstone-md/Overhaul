package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.PathNodeLookup;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One helper per evaluator; the original prepare/done search lifecycle stays authoritative. */
@Mixin(NodeEvaluator.class)
public abstract class NodeEvaluatorMixin {
    @Shadow @Final protected Int2ObjectMap<Node> nodes;
    @Unique private PathNodeLookup overhaul$nodes;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, allow = 1)
    private void overhaul$bindNodeLookup(CallbackInfo callback) {
        // Pinned constructor initializes the map without any virtual getNode call.
        overhaul$nodes = new PathNodeLookup(nodes);
    }

    /**
     * @author Overhaul maintainers
     * @reason Avoid the per-lookup capturing function and callback injection allocation while preserving the authoritative map.
     */
    @Overwrite
    protected Node getNode(int x, int y, int z) {
        return overhaul$nodes.getOrCreate(x, y, z);
    }
}
