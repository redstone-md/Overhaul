package dev.overhaul.minecraft;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Validates injection descriptors against the resolved game artifact without initializing it. */
class InjectionTargetsTest {
    @Test void strictLifecycleTargetsExistInThisMinecraftVersion() throws Exception {
        Set<String> client=methods("net/minecraft/client/Minecraft.class");
        assertTrue(client.contains("tick()V"));
        assertTrue(client.contains("close()V"));
        assertTrue(client.contains("stop()V"));
        Set<String> server=methods("net/minecraft/server/MinecraftServer.class");
        assertTrue(server.contains("tickServer(Ljava/util/function/BooleanSupplier;)V"));
        assertTrue(server.contains("stopServer()V"));
    }

    private static Set<String> methods(String resource) throws Exception {
        Set<String> result=new HashSet<String>();
        try (InputStream input=InjectionTargetsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input,resource);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    result.add(name+descriptor); return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return result;
    }
}
