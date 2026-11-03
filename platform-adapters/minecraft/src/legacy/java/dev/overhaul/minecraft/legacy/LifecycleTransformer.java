package dev.overhaul.minecraft.legacy;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Strict legacy lifecycle injection after Forge's SRG deobfuscation (sorting1001).
 * No render call is removed. Added straight-line instructions preserve existing
 * stack-map frames and avoid loading Minecraft classes while computing frames.
 */
public final class LifecycleTransformer implements IClassTransformer {
    private static final String HELPER="dev/overhaul/minecraft/legacy/LegacyLifecycle";

    @Override public byte[] transform(String name,String transformedName,byte[] bytes) {
        if (bytes==null) return null;
        boolean client="net.minecraft.client.Minecraft".equals(transformedName);
        boolean server="net.minecraft.server.MinecraftServer".equals(transformedName);
        if (!client && !server) return bytes;
        ClassNode node=new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node,0);
        String runtime=client ? "dev/overhaul/adapter/ClientRuntime" : "dev/overhaul/adapter/ServerRuntime";
        String field=client ? "overhaul$clientRuntime" : "overhaul$serverRuntime";
        String descriptor="L"+runtime+";";
        boolean alreadyInjected=false;
        for (Object rawField:node.fields) {
            FieldNode existing=(FieldNode)rawField;
            if (existing.name.equals(field)) {
                if (!existing.desc.equals(descriptor)) throw new IllegalStateException("overhaul field conflict in "+transformedName);
                alreadyInjected=true;
            }
        }
        MethodNode tick=null,close=null;
        for (Object rawMethod:node.methods) {
            MethodNode method=(MethodNode)rawMethod;
            if (!method.desc.equals("()V")) continue;
            if (matches(method.name,client ? "runTick" : "tick",client ? "func_71407_l" : "func_71217_p")) {
                if (tick!=null) throw new IllegalStateException("Ambiguous legacy tick hook");
                tick=method;
            }
            if (matches(method.name,client ? "shutdownMinecraftApplet" : "stopServer",client ? "func_71405_e" : "func_71260_j")) {
                if (close!=null) throw new IllegalStateException("Ambiguous legacy close hook");
                close=method;
            }
        }
        if (tick==null || close==null) throw new IllegalStateException("Missing legacy lifecycle methods in "+transformedName);
        if (((tick.access|close.access)&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0) {
            throw new IllegalStateException("Legacy hooks must be concrete instance methods");
        }
        if (alreadyInjected) {
            if (countHooks(tick,client ? "clientTick" : "serverTick")!=1
                    || countHooks(close,client ? "closeClient" : "closeServer")!=1) {
                throw new IllegalStateException("Existing runtime field lacks matching overhaul hooks");
            }
            return bytes;
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,field,descriptor,null,null));
        InsnList start=new InsnList();
        start.add(new VarInsnNode(Opcodes.ALOAD,0));
        start.add(new VarInsnNode(Opcodes.ALOAD,0));
        start.add(new FieldInsnNode(Opcodes.GETFIELD,node.name,field,descriptor));
        start.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,client ? "clientTick" : "serverTick",
                "("+descriptor+")"+descriptor,false));
        start.add(new FieldInsnNode(Opcodes.PUTFIELD,node.name,field,descriptor));
        tick.instructions.insert(start);
        InsnList end=new InsnList();
        end.add(new VarInsnNode(Opcodes.ALOAD,0));
        end.add(new FieldInsnNode(Opcodes.GETFIELD,node.name,field,descriptor));
        end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,client ? "closeClient" : "closeServer",
                "("+descriptor+")V",false));
        close.instructions.insert(end);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean matches(String actual,String mapped,String srg) { return actual.equals(mapped) || actual.equals(srg); }

    private static int countHooks(MethodNode method,String expected) {
        int count=0;
        for (AbstractInsnNode instruction=method.instructions.getFirst();instruction!=null;instruction=instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)instruction;
                if (call.getOpcode()==Opcodes.INVOKESTATIC && call.owner.equals(HELPER) && call.name.equals(expected)) count++;
            }
        }
        return count;
    }
}
