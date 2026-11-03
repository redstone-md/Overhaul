package dev.overhaul.shader.graph;

import java.util.LinkedHashSet;
import java.util.Set;
import static dev.overhaul.shader.graph.RenderGraph.*;

/** A versioned depth/shadow/gbuffer/lighting/postprocess topology for backend execution. */
public final class DeferredGraph {
    private DeferredGraph() {}

    public static RenderGraph create(int width, int height, int shadowResolution, int cascades) {
        if (cascades < 1 || cascades > 4) throw new IllegalArgumentException("One to four shadow cascades required");
        Builder builder = new Builder();
        builder.resource(new Resource("depth",Format.DEPTH32F,width,height,false));
        builder.resource(new Resource("albedo",Format.RGBA8,width,height,false));
        builder.resource(new Resource("normal",Format.RGBA16F,width,height,false));
        builder.resource(new Resource("lit",Format.RGBA16F,width,height,false));
        builder.resource(new Resource("final",Format.RGBA8,width,height,false));
        builder.pass(new Pass("depth-prepass",Set.of(),Set.of("depth"),Set.of()));
        Set<String> lightingReads = new LinkedHashSet<>(Set.of("depth","albedo","normal"));
        for (int cascade = 0; cascade < cascades; cascade++) {
            String name = "shadow-" + cascade;
            builder.resource(new Resource(name,Format.DEPTH32F,shadowResolution,shadowResolution,false));
            builder.pass(new Pass(name,Set.of(),Set.of(name),Set.of()));
            lightingReads.add(name);
        }
        builder.pass(new Pass("gbuffer",Set.of("depth"),Set.of("albedo","normal"),Set.of()));
        builder.pass(new Pass("lighting",lightingReads,Set.of("lit"),Set.of()));
        builder.pass(new Pass("postprocess",Set.of("lit"),Set.of("final"),Set.of()));
        return builder.build();
    }
}
