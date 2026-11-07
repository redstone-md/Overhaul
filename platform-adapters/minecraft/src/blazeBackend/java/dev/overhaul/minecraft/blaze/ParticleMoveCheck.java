package dev.overhaul.minecraft.blaze;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.LoggerFactory;

/**
 * The idle-particle fast path runs its own copy of vanilla {@code Particle.move}, so it is only safe while Sable's
 * wrapper is the only other code around or inside that method. Checked once on the merged class, right after the
 * fast path's own mixin applies, so every mixin of lower priority is already in it.
 */
public final class ParticleMoveCheck {
    private static final String WRAPPER = "(DDDLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V";
    /** A property, not a field: the mixin plugin and the game may load this class through different loaders. */
    private static final String RESULT = "overhaul.sableParticles.clean";

    private ParticleMoveCheck() {}

    public static boolean clean() {
        return Boolean.getBoolean(RESULT);
    }

    public static void inspect(ClassNode particle, String ownWrapper) {
        List<String> wrappers = new ArrayList<>();
        MethodNode vanilla = null;
        for (MethodNode method : particle.methods) {
            if (method.name.startsWith("wrapMethod$") && method.desc.equals(WRAPPER)) wrappers.add(method.name);
            // MixinExtras applies method wrappers after the last mixin, so here move itself may still be the body.
            else if ((method.name.equals("move") || method.name.startsWith("move$mixinextras$wrapped$"))
                    && method.desc.equals("(DDD)V") && !callsGenerated(particle, method, "wrapMethod$")) vanilla = method;
        }
        boolean onlySable = wrappers.size() == 2
                && wrappers.stream().anyMatch(name -> name.endsWith("$sable$moveWithSubLevels"))
                && wrappers.stream().anyMatch(name -> name.endsWith("$" + ownWrapper));
        boolean clean = onlySable && vanilla != null && !callsGenerated(particle, vanilla, "");
        System.setProperty(RESULT, Boolean.toString(clean));
        LoggerFactory.getLogger(ParticleMoveCheck.class).info("Overhaul sableParticles idle path {}: Particle.move wrapped by {}", clean ? "on" : "off", wrappers);
    }

    /** Whether the method calls a mixin-generated method of the class itself, which a plain vanilla body never does. */
    private static boolean callsGenerated(ClassNode owner, MethodNode method, String prefix) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)
                    && call.name.indexOf('$') >= 0 && call.name.startsWith(prefix)) return true;
        }
        return false;
    }
}
