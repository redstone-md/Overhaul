package dev.overhaul.minecraft.world;

import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.overhaul.minecraft.world.SectionQueryFixture.box;

/** Controlled in-process comparison; actual game oracle, warmed interleaved pairs, no timing gates. */
public final class SectionQueryBenchmark {
    private static final int PAIRS = 6;
    private static volatile long checksumSink;

    private SectionQueryBenchmark() {}

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args.length == 0 ? "build/section-query-benchmark.json" : args[0]).toAbsolutePath();
        List<Result> results = new ArrayList<>();
        for (Dataset dataset : datasets()) results.add(measure(dataset));
        String json = json(results);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        System.out.println("Section query benchmark: " + output);
        for (Result result : results) {
            System.out.printf(java.util.Locale.ROOT, "%s: original=%.1f ns/query, candidate=%.1f ns/query, ratio=%.3f%n",
                    result.dataset.name, median(result.originalNanos), median(result.candidateNanos), median(result.ratios));
        }
    }

    private static List<Dataset> datasets() {
        SectionQueryFixture populated = new SectionQueryFixture(false, false);
        for (int z = 0; z < 2000; z++) populated.add(0, 0, z);
        Dataset sparse = new Dataset("populated-column-local-hits", populated, new AABB[] {
                box(0,0,0,0,0,0), box(0,0,512,0,0,512),
                box(0,0,1024,0,0,1024), box(0,0,1999,0,0,1999)
        }, 10_000);
        Dataset empty = new Dataset("populated-column-local-misses", populated, new AABB[] {
                box(0,0,2000,0,0,2000), box(0,0,2400,0,0,2400),
                box(0,0,2800,0,0,2800), box(0,0,3200,0,0,3200)
        }, 10_000);

        SectionQueryFixture shortColumns = new SectionQueryFixture(false, false);
        for (int x = 0; x < 2048; x++) shortColumns.add(x, 0, 0);
        Dataset shortColumn = new Dataset("many-global-sections-short-local-columns", shortColumns, new AABB[] {
                box(0,0,0,0,0,0), box(512,0,0,512,0,0),
                box(1024,0,0,1024,0,0), box(2047,0,0,2047,0,0)
        }, 100_000);

        SectionQueryFixture denseLocal = new SectionQueryFixture(false, false);
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = -8; y <= 7; y++) {
            denseLocal.add(x, y, z);
        }
        Dataset dense = new Dataset("dense-local-256-cells-32-seek-budget", denseLocal,
                new AABB[] {box(0,-8,0,3,7,3)}, 100_000);

        SectionQueryFixture liveFixtureShape = new SectionQueryFixture(false, false);
        for (int x : new int[] {-1, 0}) for (int z : new int[] {-1, 0}) for (int y = 0; y < 12; y++) {
            liveFixtureShape.add(x, y, z);
        }
        Dataset liveShaped = new Dataset("short-populated-48-section-layout", liveFixtureShape, new AABB[] {
                box(-1,0,-1,-1,0,-1), box(-1,5,0,-1,5,0),
                box(0,11,-1,0,11,-1), box(0,0,0,0,0,0)
        }, 100_000);

        SectionQueryFixture live72Shape = new SectionQueryFixture(false, false);
        for (int x : new int[] {-1, 0}) for (int z : new int[] {-1, 0, 1}) for (int y = 0; y < 12; y++) {
            live72Shape.add(x, y, z);
        }
        Dataset live72 = new Dataset("live-fixture-shaped-72-sections", live72Shape, new AABB[] {
                box(-1,0,-1,-1,0,-1), box(-1,5,1,-1,5,1),
                box(0,11,-1,0,11,-1), box(0,0,1,0,0,1)
        }, 100_000);

        SectionQueryFixture smallTree = new SectionQueryFixture(false, false);
        for (int x = 0; x < 8; x++) smallTree.add(x, 0, 0);
        Dataset fallback = new Dataset("small-tree-vanilla-fallback", smallTree, new AABB[] {
                box(0,0,0,0,0,0), box(2,0,0,2,0,0), box(4,0,0,4,0,0), box(7,0,0,7,0,0)
        }, 100_000);
        return List.of(sparse, empty, shortColumn, dense, liveShaped, live72, fallback);
    }

    private static Result measure(Dataset dataset) {
        CountingConsumer consumer = new CountingConsumer();
        int warmup = Math.min(dataset.iterations, 5000);
        for (int round = 0; round < 4; round++) {
            sample(dataset, false, warmup, consumer);
            sample(dataset, true, warmup, consumer);
        }
        Result result = new Result(dataset);
        for (int pair = 0; pair < PAIRS; pair++) {
            Sample reference, proposed;
            if ((pair & 1) == 0) {
                reference = sample(dataset, false, dataset.iterations, consumer);
                proposed = sample(dataset, true, dataset.iterations, consumer);
            } else {
                proposed = sample(dataset, true, dataset.iterations, consumer);
                reference = sample(dataset, false, dataset.iterations, consumer);
            }
            if (reference.checksum != proposed.checksum || reference.accepted != proposed.accepted) {
                throw new IllegalStateException("Benchmark oracle mismatch for " + dataset.name);
            }
            result.originalNanos[pair] = reference.nanosPerQuery;
            result.candidateNanos[pair] = proposed.nanosPerQuery;
            result.ratios[pair] = proposed.nanosPerQuery / reference.nanosPerQuery;
            result.checksums[pair] = reference.checksum;
            result.accepted[pair] = reference.accepted;
        }
        return result;
    }

    private static Sample sample(Dataset dataset, boolean optimized, int iterations, CountingConsumer consumer) {
        long initialChecksum = consumer.checksum, initialAccepted = consumer.accepted;
        AABB[] queries = dataset.queries;
        int mask = queries.length - 1;
        long start = System.nanoTime();
        if (optimized) {
            for (int i = 0; i < iterations; i++) {
                AABB query = queries[i & mask];
                if (!dataset.fixture.traversal.tryTraverse(query, consumer)) {
                    dataset.fixture.storage.forEachAccessibleNonEmptySection(query, consumer);
                }
            }
        } else {
            for (int i = 0; i < iterations; i++) {
                dataset.fixture.storage.forEachAccessibleNonEmptySection(queries[i & mask], consumer);
            }
        }
        long elapsed = System.nanoTime() - start;
        long checksum = consumer.checksum - initialChecksum;
        checksumSink = checksumSink * 31 + checksum;
        return new Sample((double) elapsed / iterations, checksum, consumer.accepted - initialAccepted);
    }

    private static String json(List<Result> results) {
        StringBuilder json = new StringBuilder(8192);
        json.append("{\n  \"timestamp\": ").append(quote(Instant.now().toString()))
                .append(",\n  \"javaVersion\": ").append(quote(System.getProperty("java.version")))
                .append(",\n  \"javaVm\": ").append(quote(System.getProperty("java.vm.name")))
                .append(",\n  \"javaVendor\": ").append(quote(System.getProperty("java.vendor")))
                .append(",\n  \"os\": ").append(quote(System.getProperty("os.name")))
                .append(",\n  \"osVersion\": ").append(quote(System.getProperty("os.version")))
                .append(",\n  \"architecture\": ").append(quote(System.getProperty("os.arch")))
                .append(",\n  \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors())
                .append(",\n  \"maxHeapBytes\": ").append(Runtime.getRuntime().maxMemory())
                .append(",\n  \"jvmArguments\": [");
        List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < arguments.size(); i++) {
            if (i != 0) json.append(',');
            json.append(quote(arguments.get(i)));
        }
        json.append(']')
                .append(",\n  \"pairs\": ").append(PAIRS)
                .append(",\n  \"warmupRounds\": 4,\n  \"diagnostics\": false,")
                .append("\n  \"pairOrder\": [\"original-first\",\"candidate-first\",\"original-first\",\"candidate-first\",\"original-first\",\"candidate-first\"],")
                .append("\n  \"ratioDefinition\": \"candidate ns/query divided by original ns/query; above 1 is slower\",")
                .append("\n  \"checksumSink\": ").append(checksumSink).append(",\n  \"datasets\": [");
        for (int i = 0; i < results.size(); i++) {
            if (i != 0) json.append(',');
            Result result = results.get(i);
            Dataset dataset = result.dataset;
            json.append("\n    {\"name\": ").append(quote(dataset.name))
                    .append(", \"sections\": ").append(dataset.fixture.storage.count())
                    .append(", \"queries\": ").append(dataset.queries.length)
                    .append(", \"iterationsPerRound\": ").append(dataset.iterations)
                    .append(", \"warmupIterationsPerPathPerRound\": ").append(Math.min(dataset.iterations, 5000))
                    .append(", \"querySectionDimensions\": ").append(dimensions(dataset.queries))
                    .append(",\n      \"originalNsPerQuery\": ").append(Arrays.toString(result.originalNanos))
                    .append(",\n      \"candidateNsPerQuery\": ").append(Arrays.toString(result.candidateNanos))
                    .append(",\n      \"candidateToOriginalRatios\": ").append(Arrays.toString(result.ratios))
                    .append(",\n      \"acceptedSectionsPerRound\": ").append(Arrays.toString(result.accepted))
                    .append(",\n      \"checksums\": ").append(Arrays.toString(result.checksums))
                    .append(", \"medianOriginalNsPerQuery\": ").append(median(result.originalNanos))
                    .append(", \"medianCandidateNsPerQuery\": ").append(median(result.candidateNanos))
                    .append(", \"medianPairRatio\": ").append(median(result.ratios)).append('}');
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static String dimensions(AABB[] queries) {
        StringBuilder dimensions = new StringBuilder("[");
        for (int i = 0; i < queries.length; i++) {
            if (i != 0) dimensions.append(',');
            AABB box = queries[i];
            int x = SectionPos.posToSectionCoord(box.maxX + 2) - SectionPos.posToSectionCoord(box.minX - 2) + 1;
            int y = SectionPos.posToSectionCoord(box.maxY) - SectionPos.posToSectionCoord(box.minY - 4) + 1;
            int z = SectionPos.posToSectionCoord(box.maxZ + 2) - SectionPos.posToSectionCoord(box.minZ - 2) + 1;
            dimensions.append('[').append(x).append(',').append(y).append(',').append(z).append(']');
        }
        return dimensions.append(']').toString();
    }

    private static double median(double[] values) {
        double[] ordered = values.clone();
        Arrays.sort(ordered);
        return (ordered[(ordered.length - 1) / 2] + ordered[ordered.length / 2]) * 0.5;
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' || character == '"') quoted.append('\\').append(character);
            else if (character < 32) quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
            else quoted.append(character);
        }
        return quoted.append('"').toString();
    }

    private static final class CountingConsumer implements AbortableIterationConsumer<EntitySection<SectionQueryFixture.FakeEntity>> {
        private long accepted, checksum;
        @Override public Continuation accept(EntitySection<SectionQueryFixture.FakeEntity> section) {
            accepted++;
            checksum += section.size() * 31L + System.identityHashCode(section);
            return Continuation.CONTINUE;
        }
    }

    private record Dataset(String name, SectionQueryFixture fixture, AABB[] queries, int iterations) {}
    private record Sample(double nanosPerQuery, long checksum, long accepted) {}
    private static final class Result {
        private final Dataset dataset;
        private final double[] originalNanos = new double[PAIRS], candidateNanos = new double[PAIRS], ratios = new double[PAIRS];
        private final long[] checksums = new long[PAIRS], accepted = new long[PAIRS];
        private Result(Dataset dataset) { this.dataset = dataset; }
    }
}
