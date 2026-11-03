package dev.overhaul.shader.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable dependency/lifetime plan. Resource names denote versions: ping-pong
 * writes use a new name. Native execution must supply barriers from these usages.
 */
public final class RenderGraph {
    public enum Format { RGBA8,RGBA8_SRGB,BGRA8,BGRA8_SRGB,RGBA16F,RGBA32F,R32F,DEPTH16,DEPTH24_STENCIL8,DEPTH32F,DEPTH32F_STENCIL8 }
    /** Imported storage can hold graph-written versions while remaining externally owned. */
    public record Resource(String name, Format format, int width, int height, boolean imported) {
        public Resource {
            checkName(name);
            if (format == null || width < 1 || height < 1) throw new IllegalArgumentException("Invalid render target");
        }
    }
    public record Pass(String name, Set<String> reads, Set<String> writes, Set<String> after) {
        public Pass {
            checkName(name); reads = Set.copyOf(reads); writes = Set.copyOf(writes); after = Set.copyOf(after);
            if (!java.util.Collections.disjoint(reads,writes)) throw new IllegalArgumentException("Read/write feedback requires distinct resource versions");
        }
    }
    public record Lifetime(int firstPass, int lastPass) {}
    public record Barrier(String resource, String producer, String consumer) {}
    private final List<Pass> passes;
    private final Map<String, Resource> resources;
    private final Map<String, Lifetime> lifetimes;
    private final List<Barrier> barriers;

    private RenderGraph(List<Pass> passes, Map<String,Resource> resources,
                        Map<String,Lifetime> lifetimes, List<Barrier> barriers) {
        this.passes = List.copyOf(passes); this.resources = Map.copyOf(resources);
        this.lifetimes = Map.copyOf(lifetimes); this.barriers = List.copyOf(barriers);
    }
    public List<Pass> passes() { return passes; }
    public Map<String,Resource> resources() { return resources; }
    public Map<String,Lifetime> lifetimes() { return lifetimes; }
    public List<Barrier> barriers() { return barriers; }

    public static final class Builder {
        private final Map<String,Resource> resources = new LinkedHashMap<>();
        private final Map<String,Pass> passes = new LinkedHashMap<>();

        public Builder resource(Resource resource) {
            if (resources.putIfAbsent(resource.name(),resource) != null) throw new IllegalArgumentException("Duplicate resource: " + resource.name());
            return this;
        }
        public Builder pass(Pass pass) {
            if (passes.putIfAbsent(pass.name(),pass) != null) throw new IllegalArgumentException("Duplicate pass: " + pass.name());
            return this;
        }
        public RenderGraph build() {
            Map<String,String> producers = new HashMap<>();
            for (Pass pass : passes.values()) {
                for (String name : pass.writes()) {
                    Resource resource = requireResource(name);
                    if (producers.putIfAbsent(name,pass.name()) != null) throw new IllegalArgumentException("Multiple writers for resource version: " + name);
                }
            }
            Map<String,Set<String>> dependencies = new LinkedHashMap<>();
            List<Barrier> barriers = new ArrayList<>();
            for (Pass pass : passes.values()) {
                Set<String> required = new LinkedHashSet<>(pass.after());
                for (String after : required) if (!passes.containsKey(after)) throw new IllegalArgumentException("Unknown dependency: " + after);
                for (String read : pass.reads()) {
                    Resource resource = requireResource(read);
                    String producer = producers.get(read);
                    if (producer == null && !resource.imported()) throw new IllegalArgumentException("Read before definition: " + read);
                    if (producer != null) { required.add(producer); barriers.add(new Barrier(read,producer,pass.name())); }
                }
                dependencies.put(pass.name(),required);
            }
            List<Pass> ordered = new ArrayList<>();
            Set<String> emitted = new LinkedHashSet<>();
            while (ordered.size() < passes.size()) {
                boolean progress = false;
                for (Pass pass : passes.values()) {
                    if (!emitted.contains(pass.name()) && emitted.containsAll(dependencies.get(pass.name()))) {
                        emitted.add(pass.name()); ordered.add(pass); progress = true;
                    }
                }
                if (!progress) throw new IllegalArgumentException("Render graph contains a dependency cycle");
            }
            Map<String,Lifetime> lifetimes = new LinkedHashMap<>();
            for (int index = 0; index < ordered.size(); index++) {
                Pass pass = ordered.get(index);
                Set<String> used = new LinkedHashSet<>(pass.reads()); used.addAll(pass.writes());
                for (String name : used) {
                    Lifetime prior = lifetimes.get(name);
                    lifetimes.put(name,new Lifetime(prior == null ? index : prior.firstPass(),index));
                }
            }
            return new RenderGraph(ordered,resources,lifetimes,barriers);
        }
        private Resource requireResource(String name) {
            Resource resource = resources.get(name);
            if (resource == null) throw new IllegalArgumentException("Unknown resource: " + name);
            return resource;
        }
    }
    private static void checkName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");
    }
}
