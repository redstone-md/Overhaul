package dev.overhaul.minecraft.mixin;

import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * KubeJS enables its local web server by default, and the server's loop polls a non-blocking accept with no pause,
 * which burns a whole core at maximum thread priority. That loop lives in a library Mixin cannot transform, so this
 * adds a pause to the loop condition from the subclass. The accept thread alone sleeps; other callers return at once.
 */
@Pseudo
@Mixin(targets = "dev.latvian.mods.kubejs.web.KJSHTTPServer", remap = false)
abstract class KubeJsWebServerMixin {
    @Unique
    private static final long overhaul$IDLE_POLL_MILLIS = 2;
    @Unique
    private volatile Thread overhaul$acceptThread;
    @Unique
    private Field overhaul$channel;

    protected void serverStarted() {
        overhaul$acceptThread = Thread.currentThread();
    }

    public boolean isRunning() {
        if (Thread.currentThread() == overhaul$acceptThread) {
            try {
                Thread.sleep(overhaul$IDLE_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return overhaul$readChannel() != null;
    }

    @Unique
    private Object overhaul$readChannel() {
        try {
            if (overhaul$channel == null) {
                Field field = getClass().getSuperclass().getDeclaredField("serverSocketChannel");
                field.setAccessible(true);
                overhaul$channel = field;
            }
            return overhaul$channel.get(this);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("KubeJS web server layout changed", failure);
        }
    }
}
