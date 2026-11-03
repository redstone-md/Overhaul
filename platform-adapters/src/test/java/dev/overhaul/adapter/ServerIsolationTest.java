package dev.overhaul.adapter;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServerIsolationTest {
    @Test void serverRuntimeLoadsAndTicksWithGraphicsAndClientClassesForbidden() throws Exception {
        ClassLoader isolated=new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.startsWith("org.lwjgl.") || name.startsWith("dev.overhaul.vulkan.")
                        || name.startsWith("net.minecraft.client.")) {
                    throw new AssertionError("server requested client/native type: "+name);
                }
                if (!name.startsWith("dev.overhaul.")) throw new ClassNotFoundException(name);
                String resource=name.replace('.','/')+".class";
                try (InputStream input=ServerIsolationTest.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input==null) throw new ClassNotFoundException(name);
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    byte[] block=new byte[4096]; int count;
                    while ((count=input.read(block))!=-1) bytes.write(block,0,count);
                    byte[] data=bytes.toByteArray();
                    return defineClass(name,data,0,data.length);
                } catch (java.io.IOException error) { throw new ClassNotFoundException(name,error); }
            }
        };
        Class<?> type=isolated.loadClass("dev.overhaul.adapter.ServerRuntime");
        Object runtime=type.getConstructor(int.class,long.class).newInstance(8,1_000_000_000L);
        AtomicInteger ran=new AtomicInteger();
        assertEquals(Boolean.TRUE,type.getMethod("enqueue",Runnable.class).invoke(runtime,(Runnable)ran::incrementAndGet));
        type.getMethod("tick").invoke(runtime);
        assertEquals(1,ran.get());
        assertEquals(1L,type.getMethod("tickCount").invoke(runtime));
        type.getMethod("close").invoke(runtime);
    }
}
