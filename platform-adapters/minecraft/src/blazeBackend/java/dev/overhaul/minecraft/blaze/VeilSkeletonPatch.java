package dev.overhaul.minecraft.blaze;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.LoggerFactory;

/**
 * Veil's head hook on {@code LevelRenderer.tick} walks every client entity on every client tick to tick skeleton
 * animators. Its entity list call is swapped for one that returns an empty list while the level holds no skeleton
 * parent; both take the level and leave an {@code Iterable}, so the stack and frames stay the same. Applied to the
 * merged class right after a marker mixin of higher priority, so Veil's handler is already in it.
 */
public final class VeilSkeletonPatch {
    private static final String HANDLER = "$veil$levelRenderTick";
    private static final String LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    private static final String LIST = "()Ljava/lang/Iterable;";

    private VeilSkeletonPatch() {}

    public static void apply(ClassNode renderer) {
        int patched = 0;
        for (MethodNode method : renderer.methods) {
            if (!method.name.startsWith("handler$") || !method.name.endsWith(HANDLER)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if (!call.owner.equals(LEVEL) || !call.name.equals("entitiesForRendering") || !call.desc.equals(LIST)) continue;
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/overhaul/minecraft/blaze/BlazeVeilSkeletons",
                        "candidates", "(L" + LEVEL + ";)Ljava/lang/Iterable;", false));
                patched++;
                break;
            }
        }
        LoggerFactory.getLogger(VeilSkeletonPatch.class).info("Overhaul veilSkeletons patched {} Veil skeleton tick handler(s)", patched);
    }
}
