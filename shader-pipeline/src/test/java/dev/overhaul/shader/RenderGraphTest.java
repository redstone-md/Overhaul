package dev.overhaul.shader;

import dev.overhaul.shader.graph.DeferredGraph;
import dev.overhaul.shader.graph.RenderGraph;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static dev.overhaul.shader.graph.RenderGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderGraphTest {
    @Test void schedulesProducerBeforeConsumerRegardlessOfInsertionAndTracksLifetime() {
        var graph = new Builder()
                .resource(new Resource("input",Format.RGBA8,10,10,true))
                .resource(new Resource("color",Format.RGBA16F,10,10,false))
                .resource(new Resource("final",Format.RGBA8,10,10,false))
                .pass(new Pass("post",Set.of("color"),Set.of("final"),Set.of()))
                .pass(new Pass("draw",Set.of("input"),Set.of("color"),Set.of())).build();
        assertEquals(List.of("draw","post"),graph.passes().stream().map(Pass::name).toList());
        assertEquals(new Lifetime(0,1),graph.lifetimes().get("color"));
        assertEquals(List.of(new Barrier("color","draw","post")),graph.barriers());
        assertThrows(UnsupportedOperationException.class,() -> graph.passes().clear());
    }

    @Test void rejectsCyclesUndefinedReadsFeedbackAndMultipleProducers() {
        assertThrows(IllegalArgumentException.class,() -> new Builder()
                .pass(new Pass("a",Set.of(),Set.of(),Set.of("b")))
                .pass(new Pass("b",Set.of(),Set.of(),Set.of("a"))).build());
        assertThrows(IllegalArgumentException.class,() -> new Builder()
                .resource(new Resource("missing",Format.RGBA8,1,1,false))
                .pass(new Pass("consumer",Set.of("missing"),Set.of(),Set.of())).build());
        assertThrows(IllegalArgumentException.class,() -> new Pass("feedback",Set.of("x"),Set.of("x"),Set.of()));
        assertThrows(IllegalArgumentException.class,() -> new Builder()
                .resource(new Resource("x",Format.RGBA8,1,1,false))
                .pass(new Pass("a",Set.of(),Set.of("x"),Set.of()))
                .pass(new Pass("b",Set.of(),Set.of("x"),Set.of())).build());
    }

    @Test void deferredPlanIncludesCascadedShadowDepthAndPostprocessDependencies() {
        RenderGraph graph = DeferredGraph.create(1920,1080,2048,4);
        assertEquals(8,graph.passes().size());
        Pass lighting = graph.passes().stream().filter(pass -> pass.name().equals("lighting")).findFirst().orElseThrow();
        assertTrue(lighting.reads().containsAll(Set.of("depth","albedo","normal","shadow-0","shadow-3")));
        assertEquals("postprocess",graph.passes().get(7).name());
        assertEquals(7,graph.lifetimes().get("final").lastPass());
    }
}
