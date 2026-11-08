package dev.overhaul.minecraft.blaze;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.LoggerFactory;

/**
 * Sable's head hook on {@code EntityRenderer.shouldRender} looks up the sub-level plot under every entity on every
 * frame. With no sub-level loaded and the entity tracking none, the hook ends without an answer and vanilla culls as
 * usual, so the hook gets a guard in front that returns at once in that case. Applied to the merged class right after
 * a marker mixin of higher priority, so Sable's handler is already in it.
 */
public final class SableCullPatch {
    private static final String HANDLER = "$sable$shouldRender";
    private static final String DESC = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD"
            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
    private static final String GUARD = "dev/overhaul/minecraft/blaze/BlazeSableCull";

    private SableCullPatch() {}

    public static void apply(ClassNode renderer) {
        int patched = 0;
        for (MethodNode method : renderer.methods) {
            if (!method.name.startsWith("handler$") || !method.name.endsWith(HANDLER) || !method.desc.equals(DESC)) continue;
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            LabelNode proceed = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "skip", "(Lnet/minecraft/world/entity/Entity;)Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(proceed);
            guard.add(new FrameNode(Opcodes.F_NEW, 7, new Object[] {renderer.name, "net/minecraft/world/entity/Entity",
                    "net/minecraft/client/renderer/culling/Frustum", Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE,
                    "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"}, 0, new Object[0]));
            method.instructions.insert(guard);
            patched++;
        }
        LoggerFactory.getLogger(SableCullPatch.class).info("Overhaul sableEntityCull guarded {} Sable cull handler(s)", patched);
    }
}
