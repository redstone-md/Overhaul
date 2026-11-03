package dev.overhaul.minecraft.legacy;

import dev.overhaul.adapter.ClientRuntime;
import dev.overhaul.adapter.ServerRuntime;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleTransformerTest {
    @Test void mappedClientAndSrgServerCallbacksVerifyExecuteAndRemainInstanceOwned() throws Exception {
        verify("net.minecraft.client.Minecraft","runTick","shutdownMinecraftApplet",true);
        verify("net.minecraft.server.MinecraftServer","func_71217_p","func_71260_j",false);
    }

    @Test void failsOnMissingHooksAndLeavesUnrelatedBytesUntouched() {
        byte[] input=fixture("example/Unrelated","other","close");
        LifecycleTransformer transformer=new LifecycleTransformer();
        assertSame(input,transformer.transform("example.Unrelated","example.Unrelated",input));
        assertThrows(IllegalStateException.class,()->transformer.transform("example.Unrelated","net.minecraft.client.Minecraft",input));
    }

    private static void verify(String name,String tick,String close,boolean client) throws Exception {
        byte[] input=fixture(name.replace('.','/'),tick,close);
        LifecycleTransformer transformer=new LifecycleTransformer();
        byte[] output=transformer.transform(name,name,input);
        assertArrayEquals(output,transformer.transform(name,name,output));
        Class<?> type=new ClassLoader(LifecycleTransformerTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(name,output,0,output.length); }
        }.define();
        Object first=type.newInstance(),second=type.newInstance();
        type.getMethod(tick).invoke(first); type.getMethod(tick).invoke(first); type.getMethod(tick).invoke(second);
        Field field=type.getDeclaredField(client ? "overhaul$clientRuntime" : "overhaul$serverRuntime");
        field.setAccessible(true);
        Object firstRuntime=field.get(first),secondRuntime=field.get(second);
        assertNotSame(firstRuntime,secondRuntime);
        assertEquals(2,client ? ((ClientRuntime)firstRuntime).tickCount() : ((ServerRuntime)firstRuntime).tickCount());
        type.getMethod(close).invoke(first); type.getMethod(close).invoke(second);
    }

    private static byte[] fixture(String name,String tick,String close) {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        MethodVisitor constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD,0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0,0); constructor.visitEnd();
        for (String methodName:new String[]{tick,close}) {
            MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,methodName,"()V",null,null);
            method.visitCode(); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0,0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
}
