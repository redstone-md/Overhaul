package dev.overhaul.shader.graph;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.graph.PackTargetSwaps.Target;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackDiagnostic;
import dev.overhaul.shader.pack.PackDiagnostic.Category;
import dev.overhaul.shader.pack.PackProgramFallbacks;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The frame order of a pack and which side of each colortex every program touches, computed with
 * the swap rules of {@link PackTargetSwaps} without executing anything. The runtime does not
 * consume this plan yet. A gbuffers phase says where a program's own geometry belongs; draw order
 * inside it follows the scene, and fallbacks mean one program can serve both gbuffers phases.
 * Depth targets and shadowcolor sides are not modeled.
 */
public final class PackPassPlan {
    public enum Phase { BEGIN,SHADOW,SHADOWCOMP,PREPARE,GBUFFERS_OPAQUE,DEFERRED,GBUFFERS_TRANSLUCENT,COMPOSITE,FINAL }
    public record Input(String name, boolean enabled, List<Integer> colorOutputs, Set<String> samplers, Map<String,Boolean> flips) {
        public Input {
            colorOutputs = List.copyOf(colorOutputs); samplers = Set.copyOf(samplers); flips = Map.copyOf(flips);
        }
        public static Input of(CompiledShaderPack.Program program) {
            return new Input(program.name(),true,program.code().framebuffer().colorTargets(),
                    program.code().descriptors().samplers().keySet(),program.state().flips());
        }
        /** A compiled pack holds only its enabled programs; disabled ones need the catalog's state. */
        public static List<Input> of(CompiledShaderPack pack) { return pack.programs().stream().map(Input::of).toList(); }
    }
    public record Step(String name, Phase phase, int ordinal, boolean virtual, List<Target> writes, List<Target> reads,
                       Set<Integer> flipped, List<Integer> shadowOutputs) {
        public Step {
            writes = List.copyOf(writes); reads = List.copyOf(reads); flipped = Set.copyOf(flipped); shadowOutputs = List.copyOf(shadowOutputs);
        }
    }
    private record Placed(Input input, Phase phase, int ordinal) {}

    private static final Set<String> TRANSLUCENT = Set.of("gbuffers_water","gbuffers_hand_water","gbuffers_weather",
            "gbuffers_block_translucent","gbuffers_entities_translucent","gbuffers_particles_translucent");
    private static final String GRAPH_OUTPUT = "screen";
    private final List<Step> steps;
    private final List<PackDiagnostic> diagnostics;
    private final Set<Integer> endOfFrameCopies;

    private PackPassPlan(List<Step> steps, List<PackDiagnostic> diagnostics, Set<Integer> endOfFrameCopies) {
        this.steps = List.copyOf(steps); this.diagnostics = List.copyOf(diagnostics); this.endOfFrameCopies = Set.copyOf(endOfFrameCopies);
    }
    public List<Step> steps() { return steps; }
    public List<PackDiagnostic> diagnostics() { return diagnostics; }
    /** Targets that end the frame on their alternate side; Iris copies these back so the next frame starts on main. */
    public Set<Integer> endOfFrameCopies() { return endOfFrameCopies; }
    public boolean blocked() { return diagnostics.stream().anyMatch(PackDiagnostic::blocking); }

    public static PackPassPlan plan(List<Input> inputs, ShaderPackProperties properties) {
        List<PackDiagnostic> diagnostics = new ArrayList<>();
        boolean particlesFirst = particlesBeforeDeferred(properties,diagnostics);
        List<Placed> placed = new ArrayList<>();
        for (Input input : inputs) place(input,particlesFirst,placed,diagnostics);
        placed.sort(Comparator.comparingInt(Placed::ordinal).thenComparing(entry -> entry.input().name()));
        reportFinal(placed,diagnostics);
        boolean shadowEnabled = !"false".equals(properties.values().get("shadow.enabled"));
        List<Step> steps = new ArrayList<>();
        int[] current = new int[16];
        for (Phase phase : order(properties)) {
            boolean shadowPhase = phase == Phase.SHADOW || phase == Phase.SHADOWCOMP;
            if (phase == Phase.DEFERRED) preFlip("deferred_pre",phase,properties,current,steps,diagnostics);
            if (phase == Phase.COMPOSITE) preFlip("composite_pre",phase,properties,current,steps,diagnostics);
            for (Placed program : placed) {
                if (program.phase() != phase) continue;
                if (shadowPhase && !shadowEnabled) {
                    diagnostics.add(PackDiagnostic.info(Category.STAGE,program.input().name(),"Left out of the plan by shadow.enabled=false"));
                    continue;
                }
                steps.add(step(program,current,diagnostics));
            }
        }
        if (shadowEnabled && placed.stream().anyMatch(program -> program.phase() == Phase.SHADOW || program.phase() == Phase.SHADOWCOMP)) {
            diagnostics.add(PackDiagnostic.info(Category.STAGE,"shadow","shadowcolor sides are owned by the backend's shadow images and are not planned here"));
        }
        Set<Integer> copies = new TreeSet<>();
        for (int index = 0; index < 16; index++) if (current[index] != 0) copies.add(index);
        return new PackPassPlan(steps,diagnostics,copies);
    }

    private static void place(Input input, boolean particlesFirst, List<Placed> placed, List<PackDiagnostic> diagnostics) {
        String bare = input.name().substring(input.name().lastIndexOf('/')+1);
        if (!input.enabled()) {
            String stand = PackProgramFallbacks.parent(bare).map(parent -> "; its geometry falls back toward "+parent).orElse("");
            diagnostics.add(PackDiagnostic.info(Category.PROGRAM,input.name(),"Disabled by the pack"+stand));
            return;
        }
        if (bare.startsWith("dh_")) {
            diagnostics.add(PackDiagnostic.degraded(Category.PROGRAM,input.name(),"Left out of the plan; there is no Distant Horizons integration"));
            return;
        }
        try { placed.add(new Placed(input,phase(bare,particlesFirst),CompiledShaderPack.ordinal(bare))); }
        catch (UnsupportedShaderException failure) { diagnostics.add(PackDiagnostic.blocking(Category.PROGRAM,input.name(),failure.getMessage())); }
    }

    private static Phase phase(String bare, boolean particlesFirst) {
        return switch (CompiledShaderPack.stage(bare)) {
            case BEGIN -> Phase.BEGIN;
            case SHADOW -> Phase.SHADOW;
            case SHADOWCOMPOSITE -> Phase.SHADOWCOMP;
            case PREPARE -> Phase.PREPARE;
            case GBUFFER -> TRANSLUCENT.contains(bare) || (bare.equals("gbuffers_particles") && !particlesFirst) ? Phase.GBUFFERS_TRANSLUCENT : Phase.GBUFFERS_OPAQUE;
            case DEFERRED -> Phase.DEFERRED;
            case COMPOSITE -> Phase.COMPOSITE;
            case FINAL -> Phase.FINAL;
        };
    }

    /** The OptiFine documentation places gbuffers_particles after deferred unless a pack asks otherwise. */
    private static boolean particlesBeforeDeferred(ShaderPackProperties properties, List<PackDiagnostic> diagnostics) {
        String ordering = properties.values().get("particles.ordering");
        if (ordering == null) return "true".equals(properties.values().get("particles.before.deferred"));
        String mode = ordering.toUpperCase(Locale.ROOT);
        if (mode.equals("MIXED")) diagnostics.add(PackDiagnostic.degraded(Category.STAGE,"particles.ordering","Mixed particle ordering is planned as after deferred"));
        else if (!mode.equals("BEFORE") && !mode.equals("AFTER")) diagnostics.add(PackDiagnostic.degraded(Category.STAGE,"particles.ordering","Unknown value planned as after deferred: "+ordering));
        return mode.equals("BEFORE");
    }

    private static void reportFinal(List<Placed> placed, List<PackDiagnostic> diagnostics) {
        List<String> finals = placed.stream().filter(program -> program.phase() == Phase.FINAL).map(program -> program.input().name()).toList();
        if (finals.size() > 1) diagnostics.add(PackDiagnostic.blocking(Category.STAGE,"final","Only one final program can present: "+finals));
        if (finals.isEmpty()) diagnostics.add(PackDiagnostic.info(Category.STAGE,"final","No final program; the baseline copies colortex0 to the screen"));
    }

    private static List<Phase> order(ShaderPackProperties properties) {
        List<Phase> order = new ArrayList<>(List.of(Phase.values()));
        if ("true".equals(properties.values().get("prepareBeforeShadow"))) {
            order.remove(Phase.PREPARE);
            order.add(order.indexOf(Phase.SHADOW),Phase.PREPARE);
        }
        return order;
    }

    /** deferred_pre and composite_pre carry only flips and have no program source. */
    private static void preFlip(String name, Phase phase, ShaderPackProperties properties, int[] current, List<Step> steps, List<PackDiagnostic> diagnostics) {
        Map<String,Boolean> flips;
        try { flips = properties.program(name,Map.of()).flips(); }
        catch (UnsupportedShaderException failure) {
            diagnostics.add(PackDiagnostic.blocking(Category.STAGE,name,failure.getMessage()));
            return;
        }
        Set<Integer> flipped = applyFlips(PackTargetSwaps.flipsAfter(List.of(),flips),current);
        if (flipped.isEmpty()) return;
        steps.add(new Step(name,phase,-1,true,List.of(),List.of(),flipped,List.of()));
        diagnostics.add(PackDiagnostic.degraded(Category.STAGE,name,"Planned, but the runtime has no virtual flip pass and will not apply it"));
    }

    private static Step step(Placed program, int[] current, List<PackDiagnostic> diagnostics) {
        Input input = program.input();
        Phase phase = program.phase();
        List<Target> reads = reads(input,current);
        List<Integer> outputs = input.colorOutputs().stream().filter(index -> index >= 0).toList();
        return switch (phase) {
            case SHADOW, SHADOWCOMP -> new Step(input.name(),phase,program.ordinal(),false,List.of(),reads,Set.of(),outputs);
            case FINAL -> new Step(input.name(),phase,program.ordinal(),false,List.of(),reads,Set.of(),List.of());
            case GBUFFERS_OPAQUE, GBUFFERS_TRANSLUCENT -> {
                List<Target> writes = outputs.stream().map(index -> new Target(index,current[index])).toList();
                for (Target write : writes) if (reads.contains(write)) diagnostics.add(PackDiagnostic.blocking(Category.PROGRAM,input.name(),"Scene program reads its active color attachment: colortex"+write.color()));
                if (input.flips().containsValue(true)) diagnostics.add(PackDiagnostic.degraded(Category.PROGRAM,input.name(),"Buffer flips have no effect on a scene program"));
                yield new Step(input.name(),phase,program.ordinal(),false,writes,reads,Set.of(),List.of());
            }
            default -> {
                List<Target> writes = outputs.stream().map(index -> new Target(index,current[index]^1)).toList();
                Set<Integer> flipped = applyFlips(PackTargetSwaps.flipsAfter(input.colorOutputs(),input.flips()),current);
                yield new Step(input.name(),phase,program.ordinal(),false,writes,reads,flipped,List.of());
            }
        };
    }

    private static List<Target> reads(Input input, int[] current) {
        Set<Target> reads = new LinkedHashSet<>();
        for (String sampler : new TreeSet<>(input.samplers())) {
            int index = PackRenderTargets.colorIndex(sampler);
            if (index >= 0) reads.add(new Target(index,current[index]));
        }
        return List.copyOf(reads);
    }

    private static Set<Integer> applyFlips(boolean[] flip, int[] current) {
        Set<Integer> flipped = new TreeSet<>();
        for (int index = 0; index < 16; index++) if (flip[index]) { current[index] ^= 1; flipped.add(index); }
        return flipped;
    }

    /**
     * Linear dependency plan over versioned colortex sides. A scene write also reads the prior version,
     * because attachments load their contents. Virtual steps only move which side later passes use.
     */
    public RenderGraph toRenderGraph(int width, int height, IntFunction<RenderGraph.Format> formats) {
        if (blocked()) throw new UnsupportedShaderException("Cannot build a render graph from a blocked pack plan");
        RenderGraph.Builder builder = new RenderGraph.Builder();
        Map<Target,Integer> versions = new HashMap<>();
        String previous = null;
        for (Step step : steps) {
            if (step.virtual()) continue;
            Set<String> reads = new LinkedHashSet<>(), writes = new LinkedHashSet<>();
            for (Target read : step.reads()) reads.add(existing(read,versions,builder,width,height,formats));
            boolean scene = step.phase() == Phase.GBUFFERS_OPAQUE || step.phase() == Phase.GBUFFERS_TRANSLUCENT;
            for (Target write : step.writes()) {
                String prior = existing(write,versions,builder,width,height,formats);
                if (scene) reads.add(prior);
                int version = versions.merge(write,1,Integer::sum);
                String name = resource(write,version);
                builder.resource(new RenderGraph.Resource(name,formats.apply(write.color()),width,height,false));
                writes.add(name);
            }
            if (step.phase() == Phase.FINAL) {
                builder.resource(new RenderGraph.Resource(GRAPH_OUTPUT,formats.apply(0),width,height,false));
                writes.add(GRAPH_OUTPUT);
            }
            builder.pass(new RenderGraph.Pass(step.name(),reads,writes,previous == null ? Set.of() : Set.of(previous)));
            previous = step.name();
        }
        return builder.build();
    }

    /** The first use of a side refers to externally owned storage carried over from the last frame. */
    private static String existing(Target target, Map<Target,Integer> versions, RenderGraph.Builder builder, int width, int height, IntFunction<RenderGraph.Format> formats) {
        Integer version = versions.get(target);
        if (version != null) return resource(target,version);
        versions.put(target,0);
        String name = resource(target,0);
        builder.resource(new RenderGraph.Resource(name,formats.apply(target.color()),width,height,true));
        return name;
    }

    private static String resource(Target target, int version) {
        return "colortex"+target.color()+(target.side() == 0 ? ".main" : ".alt")+".v"+version;
    }
}
