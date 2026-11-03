package dev.overhaul.minecraft.shadow;

import net.minecraft.client.renderer.StagedVertexBuffer;

/** Used once on a newly constructed, shadow-owned dispatcher before its first prepare. */
public interface ShadowFeatureDispatcherAccess {
    void overhaul$ownShadowStaging(StagedVertexBuffer staging);
}
