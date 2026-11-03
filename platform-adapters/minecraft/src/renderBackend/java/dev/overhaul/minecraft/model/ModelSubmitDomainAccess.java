package dev.overhaul.minecraft.model;

import dev.overhaul.minecraft.shader.SceneDrawDomain;
import java.util.Objects;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Domain lives on the actual deferred geometry Submit, surviving phase grouping and sorting. */
public interface ModelSubmitDomainAccess {
    SceneDrawDomain overhaul$sceneDomain();
    void overhaul$sceneDomain(SceneDrawDomain domain);

    static SceneDrawDomain domain(Object submit) {
        return submit instanceof ModelSubmitDomainAccess access ? access.overhaul$sceneDomain() : SceneDrawDomain.UNKNOWN;
    }

    static void capture(Object submit, SceneDrawDomain domain) {
        Objects.requireNonNull(domain, "domain");
        // A scope with no origin evidence must not erase a previously tagged node.
        if (domain != SceneDrawDomain.UNKNOWN && submit instanceof ModelSubmitDomainAccess access)
            access.overhaul$sceneDomain(domain);
    }

    static RenderType renderType(RenderType original, Object submit) {
        return ((DomainRenderTypeAccess) original).overhaul$forDomain(domain(submit));
    }
}
