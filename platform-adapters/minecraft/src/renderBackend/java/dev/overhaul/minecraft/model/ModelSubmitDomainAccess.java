package dev.overhaul.minecraft.model;

import dev.overhaul.minecraft.shader.SceneDrawDomain;

/** Domain lives on the actual deferred Submit, surviving phase grouping and sorting. */
public interface ModelSubmitDomainAccess {
    SceneDrawDomain overhaul$sceneDomain();
    void overhaul$sceneDomain(SceneDrawDomain domain);

    static SceneDrawDomain domain(Object submit) {
        return submit instanceof ModelSubmitDomainAccess access ? access.overhaul$sceneDomain() : SceneDrawDomain.UNKNOWN;
    }
}
