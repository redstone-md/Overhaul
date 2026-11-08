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
 * TACZ's hook on {@code LivingEntity.tick} ticks the gun state of every living entity on the server, though only a gun
 * in hand ever moves it off its start. The hook gets a guard in front that returns at once while the entity is a mob
 * that has never held a gun (see {@link BlazeGunIdle}). Applied to the merged class right after a marker mixin of
 * higher priority, so TACZ's handler is already in it.
 */
public final class TaczUnarmedPatch {
    private static final String HANDLER = "$tacz$onTickServerSide";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String IDLE = "dev/overhaul/minecraft/blaze/BlazeGunIdle";

    private TaczUnarmedPatch() {}

    public static void apply(ClassNode living) {
        int patched = 0;
        for (MethodNode method : living.methods) {
            if (!method.name.startsWith("handler$") || !method.name.endsWith(HANDLER) || !method.desc.equals("(L" + CALLBACK + ";)V")) continue;
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            LabelNode proceed = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, IDLE, "overhaul$gunIdle", "()Z", true));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(proceed);
            guard.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {living.name, CALLBACK}, 0, new Object[0]));
            method.instructions.insert(guard);
            patched++;
        }
        LoggerFactory.getLogger(TaczUnarmedPatch.class).info("Overhaul taczUnarmed guarded {} TACZ tick handler(s)", patched);
    }
}
