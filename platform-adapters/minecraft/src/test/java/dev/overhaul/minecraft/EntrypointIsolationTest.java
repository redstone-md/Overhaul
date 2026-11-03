package dev.overhaul.minecraft;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Entry linkage test, distinct from a full dedicated-server/world startup. */
class EntrypointIsolationTest {
    @Test void selectedEntrypointInitializesWithoutClientOrNativeClasses() throws Exception {
        String loader=System.getProperty("overhaul.loader");
        String entry="dev.overhaul.minecraft."+loader+".Overhaul"
                +(loader.equals("fabric") ? "Fabric" : loader.equals("forge") ? "Forge" : "NeoForge");
        ClassLoader isolated=new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("org.lwjgl.")
                        || name.startsWith("dev.overhaul.vulkan.") || name.startsWith("dev.overhaul.shader.")) {
                    throw new AssertionError("Server entrypoint loaded client/native dependency: "+name);
                }
                try (InputStream input=EntrypointIsolationTest.class.getClassLoader()
                        .getResourceAsStream(name.replace('.','/')+".class")) {
                    if (input==null) throw new ClassNotFoundException(name);
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    byte[] block=new byte[4096]; int count;
                    while ((count=input.read(block))!=-1) bytes.write(block,0,count);
                    byte[] data=bytes.toByteArray(); return defineClass(name,data,0,data.length);
                } catch(java.io.IOException error) { throw new ClassNotFoundException(name,error); }
            }
        };
        Object instance=isolated.loadClass(entry).getConstructor().newInstance();
        assertNotNull(instance);
        if (loader.equals("fabric")) instance.getClass().getMethod("onInitialize").invoke(instance);
    }
}
