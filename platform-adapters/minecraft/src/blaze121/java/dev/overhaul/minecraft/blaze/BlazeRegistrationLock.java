package dev.overhaul.minecraft.blaze;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Map;

/** Renderer registrations take turns on the vanilla map; the map is the lock, see the registration mixins. */
public final class BlazeRegistrationLock {
    private BlazeRegistrationLock() {
    }

    public static Object put(Map<Object, Object> providers, Object type, Object provider, Operation<Object> put) {
        synchronized (providers) {
            return put.call(providers, type, provider);
        }
    }
}
