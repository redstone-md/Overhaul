package dev.overhaul.minecraft.blaze;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.LoggerFactory;

/**
 * The rain fast path calls vanilla's height lookup itself, skipping every wrapper inside its own, so it is only safe
 * while Sable's wrapper is the only other one on that call. Checked once on the merged class, right after the fast
 * path's own mixin applies, so every mixin of lower priority is already in it.
 */
public final class RainHeightCheck {
    private static final String WRAPPER = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/levelgen/Heightmap$Types;II"
            + "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)I";
    /** A property, not a field: the mixin plugin and the game may load this class through different loaders. */
    private static final String RESULT = "overhaul.sableRain.clean";

    private RainHeightCheck() {}

    public static boolean clean() {
        return Boolean.getBoolean(RESULT);
    }

    public static void inspect(ClassNode renderer, String ownWrapper) {
        List<String> wrappers = new ArrayList<>();
        for (MethodNode method : renderer.methods) {
            if (method.name.startsWith("wrapOperation$") && method.desc.equals(WRAPPER)) wrappers.add(method.name);
        }
        boolean clean = wrappers.size() == 2
                && wrappers.stream().anyMatch(name -> name.endsWith("$sable$preventRainThoughSubLevel"))
                && wrappers.stream().anyMatch(name -> name.endsWith("$" + ownWrapper));
        System.setProperty(RESULT, Boolean.toString(clean));
        LoggerFactory.getLogger(RainHeightCheck.class).info("Overhaul sableRain fast path {}: rain height wrapped by {}", clean ? "on" : "off", wrappers);
    }
}
