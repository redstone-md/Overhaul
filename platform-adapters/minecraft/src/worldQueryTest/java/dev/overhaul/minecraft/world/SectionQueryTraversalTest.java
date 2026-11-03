package dev.overhaul.minecraft.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static dev.overhaul.minecraft.world.SectionQueryFixture.box;
import static org.junit.jupiter.api.Assertions.*;

class SectionQueryTraversalTest {
    @Test void seededQueriesMatchUntouchedGameOrderAcrossSignedSectionCoordinates() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        Random random = new Random(74481);
        for (int i = 0; i < 600; i++) {
            int x = random.nextInt(7) - 3, y = random.nextInt(15) - 7, z = random.nextInt(7) - 3;
            fixture.add(x, y, z);
            if (i % 11 == 0) fixture.status(x, y, z, Visibility.HIDDEN);
            else if (i % 13 == 0) fixture.status(x, y, z, Visibility.TRACKED);
            if (i % 17 == 0) fixture.empty(x, y, z);
        }
        for (int i = 0; i < 300; i++) {
            int x = random.nextInt(7) - 3, y = random.nextInt(13) - 6, z = random.nextInt(7) - 3;
            AABB query = box(x, y, z, x + random.nextInt(2), y + random.nextInt(4), z + random.nextInt(2));
            assertEquals(fixture.trace(false, query, null, 0), fixture.trace(true, query, null, 0));
        }
        assertEquals(300L, fixture.traversal.snapshot().get("queries"));
    }

    @Test void exactEntityBoundsTypedInsertionOrderAndEarlyAbortRemainVanilla() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) {
            fixture.add(0, y, z);
            fixture.add(0, y, z);
        }
        fixture.add(0, 0, 0, new AABB(13, 5, 5, 14, 6, 6)); // touching max X is not an intersection.
        fixture.add(0, 0, 0, new AABB(-100, -100, -100, 12, 12, 12)); // broad bounds, not a point query.
        AABB query = box(0, -1, -1, 0, 1, 1);
        for (int abort : new int[] {0, 1, 4, 1000}) {
            assertEquals(fixture.entityTrace(false, query, null, abort), fixture.entityTrace(true, query, null, abort));
            EntityTypeTest<SectionQueryFixture.FakeEntity, SectionQueryFixture.FakeEntity> marker = new EntityTypeTest<>() {
                @Override public SectionQueryFixture.FakeEntity tryCast(SectionQueryFixture.FakeEntity entity) {
                    return entity instanceof SectionQueryFixture.MarkerEntity ? entity : null;
                }
                @Override public Class<? extends SectionQueryFixture.FakeEntity> getBaseClass() {
                    return SectionQueryFixture.MarkerEntity.class;
                }
            };
            assertEquals(fixture.entityTrace(false, query, marker, abort), fixture.entityTrace(true, query, marker, abort));
        }
    }

    @Test void insertionIntoPrefetchedGapIsSkippedExactlyLikeOriginalIterator() {
        assertMutationMatches((fixture, accepted) -> { if (accepted == 1) fixture.add(0, 1, 0); });
    }

    @Test void removingPrefetchedEntryRetainsOriginalSuccessorChain() {
        assertMutationMatches((fixture, accepted) -> { if (accepted == 1) fixture.remove(0, 2, 0); });
    }

    @Test void replacingPrefetchedEntryStillResolvesCurrentSectionMapValue() {
        assertMutationMatches((fixture, accepted) -> {
            if (accepted == 1) { fixture.remove(0, 2, 0); fixture.add(0, 2, 0); }
        });
    }

    @Test void removingCurrentAndRebuildingColumnPreservesRemovedNodePrefetch() {
        assertMutationMatches((fixture, accepted) -> {
            if (accepted == 1) {
                for (int y : new int[] {0, 2, 3, 200}) fixture.remove(0, y, 0);
                for (int y = 1; y <= 4; y++) fixture.add(0, y, 0);
            }
        });
    }

    @Test void mutationAcrossExcludedSpansAndFutureColumnsMatchesOriginal() {
        SectionQueryFixture.Mutation mutation = (fixture, accepted) -> {
            if (accepted == 1) {
                fixture.remove(0, 200, 0);
                fixture.add(0, -1, 0);
                fixture.add(1, -1, 0);
            }
        };
        SectionQueryFixture reference = new SectionQueryFixture(), optimized = new SectionQueryFixture();
        for (SectionQueryFixture fixture : new SectionQueryFixture[] {reference, optimized}) {
            fixture.add(0, 0, 0); fixture.add(0, 200, 0); fixture.add(0, -2, 0);
        }
        AABB query = box(0, -2, 0, 1, 0, 0);
        assertEquals(reference.trace(false, query, mutation, 0), optimized.trace(true, query, mutation, 0));
        assertEquals(1L, optimized.traversal.snapshot().get("mutationFallbacks"));
    }

    @Test void reentrantQueriesAndNestedMutationLeaveOuterPrefetchIntact() {
        SectionQueryFixture reference = localFixture(), optimized = localFixture();
        AABB query = box(0, 0, 0, 0, 3, 0);
        assertEquals(reference.trace(false, query, nestedMutation(false), 0),
                optimized.trace(true, query, nestedMutation(true), 0));
        assertEquals(2L, optimized.traversal.snapshot().get("mutationFallbacks"));
        SectionQueryFixture fixture = localFixture();
        List<List<SectionQueryFixture.Visit>> nested = new ArrayList<>();
        fixture.trace(true, box(0, 0, 0, 0, 3, 0), (current, accepted) -> {
            nested.add(current.trace(true, box(0, 0, 0, 0, 3, 0), null, 1));
        }, 0);
        assertEquals(3, nested.size());
        assertEquals(4L, fixture.traversal.snapshot().get("queries"));
    }

    @Test void consumerExceptionsPropagateWithoutPoisoningSubsequentQueries() {
        SectionQueryFixture fixture = localFixture();
        AABB query = box(0, 0, 0, 0, 3, 0);
        IllegalStateException expected = new IllegalStateException("consumer failure");
        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> fixture.traversal.tryTraverse(query, ignored -> { throw expected; })));
        assertEquals(fixture.trace(false, query, null, 0), fixture.trace(true, query, null, 0));
    }

    @Test void vanillaScopeIsNestedExceptionSafeAndSeparatelyCounted() {
        SectionQueryFixture fixture = localFixture();
        AABB query = box(0, 0, 0, 0, 3, 0);
        fixture.traversal.withVanilla(() -> {
            assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("bypassed callback")));
            assertThrows(IllegalArgumentException.class, () -> fixture.traversal.withVanilla(() -> {
                assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("nested bypass")));
                throw new IllegalArgumentException("fixture");
            }));
            assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("outer scope retained")));
        });
        assertTrue(fixture.traversal.tryTraverse(query, ignored -> Continuation.CONTINUE));
        Map<String, Long> snapshot = fixture.traversal.snapshot();
        assertEquals(3L, snapshot.get("vanillaBypasses"));
        assertEquals(0L, snapshot.get("fallbacks"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("queries", 0L));
    }

    @Test void unsupportedBoundsAndSmallTreesFallbackWithoutInvokingConsumers() throws Exception {
        SectionQueryFixture fixture = localFixture();
        AABB[] unsupported = {
                new AABB(Double.NaN, 0, 0, 1, 1, 1),
                new AABB(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1),
                box(0, 0, 0, 100, 100, 100),
                box(2097151, 0, 0, 2097151, 0, 0),
                box(0, 524288, 0, 0, 524288, 0)
        };
        for (AABB query : unsupported) assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("fallback callback")));
        var small = new SectionQueryTraversal<SectionQueryFixture.FakeEntity>(new Long2ObjectOpenHashMap<>(), new LongAVLTreeSet(), true);
        assertFalse(small.tryTraverse(box(0,0,0,0,0,0), ignored -> fail("small tree callback")));
        AtomicBoolean handled = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try { handled.set(fixture.traversal.tryTraverse(box(0,0,0,0,0,0), ignored -> fail("foreign callback"))); }
            catch (Throwable problem) { failure.set(problem); }
        });
        foreign.start(); foreign.join();
        assertNull(failure.get()); assertFalse(handled.get());
    }

    @Test void sparseHitSeeksAvoidAllDistantKeysInPopulatedColumn() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        for (int z = 0; z < 500; z++) for (int y = 0; y < 4; y++) fixture.add(0, y, z);
        AABB query = box(0, 0, 0, 0, 0, 0);
        assertEquals(fixture.trace(false, query, null, 0), fixture.trace(true, query, null, 0));
        Map<String, Long> counters = fixture.traversal.snapshot();
        assertEquals(1L, counters.get("seeks"));
        assertEquals(0L, counters.get("prefixKeys"));
        assertEquals(0L, counters.get("prefixDrains"));
        assertTrue(counters.get("visitedKeys") <= 1 + 1, "One matching key and at most one interval overshoot");
        assertEquals(1L, counters.get("acceptedSections"));
    }

    @Test void seekBeginsAtInclusiveLowKeyWithoutEarlierCallbacks() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        for (int y = 0; y <= 20; y++) fixture.add(0, y, 0);
        AABB query = box(0, 6, 0, 0, 10, 0);
        List<SectionQueryFixture.Visit> expected = fixture.trace(false, query, null, 0);
        assertEquals(expected, fixture.trace(true, query, null, 0));
        assertEquals(5, expected.size());
        Map<String, Long> counters = fixture.traversal.snapshot();
        assertEquals(0L, counters.get("prefixKeys"));
        assertEquals(1L, counters.get("seeks"));
        assertTrue(counters.get("visitedKeys") <= 5 + 1);
    }

    @Test void insertingGapAfterSeekRetainsActualOriginalPrefetchedEntry() {
        assertSeekMutationMatches((fixture, accepted) -> { if (accepted == 1) fixture.add(0, 1, 100); });
    }

    @Test void removingOrReplacingPrefetchedEntryAfterSeekPreservesOriginalIteration() {
        assertSeekMutationMatches((fixture, accepted) -> { if (accepted == 1) fixture.remove(0, 2, 100); });
        assertSeekMutationMatches((fixture, accepted) -> {
            if (accepted == 1) { fixture.remove(0, 2, 100); fixture.add(0, 2, 100); }
        });
    }

    @Test void laterSeekCallbackMutationKeepsOriginalEntrySequence() {
        SectionQueryFixture reference = new SectionQueryFixture(), optimized = new SectionQueryFixture();
        for (SectionQueryFixture fixture : new SectionQueryFixture[] {reference, optimized}) {
            for (int y = 0; y <= 20; y++) fixture.add(0, y, 0);
        }
        SectionQueryFixture.Mutation mutation = (fixture, accepted) -> {
            // A later callback replaces the next Entry that the active interval iterator already prefetched.
            if (accepted == 3) { fixture.remove(0, 9, 0); fixture.add(0, 9, 0); }
        };
        AABB query = box(0, 6, 0, 0, 10, 0);
        assertEquals(reference.trace(false, query, mutation, 0), optimized.trace(true, query, mutation, 0));
        assertEquals(1L, optimized.traversal.snapshot().get("seeks"));
        assertEquals(1L, optimized.traversal.snapshot().get("mutationFallbacks"));
    }

    @Test void intervalBoundaryMutationRetainsOutOfRangePrefetchAndFollowingSignedRange() {
        SectionQueryFixture reference = new SectionQueryFixture(), optimized = new SectionQueryFixture();
        for (SectionQueryFixture fixture : new SectionQueryFixture[] {reference, optimized}) {
            fixture.add(0,0,0); fixture.add(0,200,0); fixture.add(0,-2,0);
        }
        SectionQueryFixture.Mutation mutation = (fixture, accepted) -> {
            if (accepted == 1) {
                // y=0 ends the first query interval, but the full-column iterator prefetched excluded y=200.
                fixture.remove(0,200,0);
                fixture.add(0,1,0);
                fixture.add(0,-1,0);
            }
        };
        AABB query = box(0,-2,0,0,0,0);
        assertEquals(reference.trace(false, query, mutation, 0), optimized.trace(true, query, mutation, 0));
        assertEquals(1L, optimized.traversal.snapshot().get("seeks"));
        assertEquals(1L, optimized.traversal.snapshot().get("mutationFallbacks"));
    }

    @Test void denseLocalGeometryAndShortColumnsChooseVanillaBeforeTraversal() {
        SectionQueryFixture dense = new SectionQueryFixture();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = -8; y <= 7; y++) dense.add(x,y,z);
        AABB query = box(0,-8,0,3,7,3);
        assertEquals(dense.trace(false, query, null, 0), dense.trace(true, query, null, 0));
        assertEquals(0L, dense.traversal.snapshot().get("seeks"));
        assertEquals(0L, dense.traversal.snapshot().get("queries"));
        assertEquals(1L, dense.traversal.snapshot().get("fallbacks"));
        assertEquals(0L, dense.traversal.snapshot().get("prefixKeys"));
        assertEquals(0L, dense.traversal.snapshot().get("visitedKeys"));

        SectionQueryFixture shortColumn = new SectionQueryFixture(true, false);
        for (int x = 0; x < 100; x++) shortColumn.add(x,0,0);
        query = box(0,0,0,0,0,0);
        assertEquals(shortColumn.trace(false, query, null, 0), shortColumn.trace(true, query, null, 0));
        assertEquals(0L, shortColumn.traversal.snapshot().get("seeks"));
        assertEquals(0L, shortColumn.traversal.snapshot().get("queries"));
        assertEquals(1L, shortColumn.traversal.snapshot().get("fallbacks"));
        assertEquals(0L, shortColumn.traversal.snapshot().get("visitedKeys"));
        assertEquals(0L, shortColumn.traversal.snapshot().get("longColumns"));
    }

    @Test void minimumPackedColumnAndSignCrossingMatchUntouchedGame() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        for (int x : new int[] {-2097152, -1, 0}) for (int y : new int[] {-1, 0, 1}) for (int z : new int[] {-1, 0, 1}) {
            fixture.add(x, y, z);
        }
        for (int z = 100; z < 140; z++) fixture.add(-2097152, 100, z);
        for (AABB query : new AABB[] {box(-2097152,0,0,-2097152,1,1), box(-1,-1,-1,0,1,1)}) {
            assertEquals(fixture.trace(false, query, null, 0), fixture.trace(true, query, null, 0));
        }
        assertEquals(2L, fixture.traversal.snapshot().get("queries"));
        assertEquals(14L, fixture.traversal.snapshot().get("seeks"));
        assertEquals(0L, fixture.traversal.snapshot().get("prefixKeys"));
    }

    @Test void occupancyAdmissionCountsOnlySuccessfulCreationAndExistingRemoval() {
        SectionQueryFixture fixture = new SectionQueryFixture(true, false);
        AABB query = box(0,0,0,0,0,0);
        for (int z = 0; z < 32; z++) fixture.add(0,0,z);
        assertEquals(0L, fixture.traversal.snapshot().get("longColumns"));
        assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("32-section column uses vanilla")));
        fixture.traversal.structureChanged(); // Failed create HEAD: no create RETURN and no map insertion.
        assertEquals(0L, fixture.traversal.snapshot().get("longColumns"));
        assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("failed creation cannot admit")));

        fixture.add(0,0,32);
        assertEquals(1L, fixture.traversal.snapshot().get("longColumns"));
        assertEquals(fixture.trace(false, query, null, 0), fixture.trace(true, query, null, 0));
        assertEquals(1L, fixture.traversal.snapshot().get("queries"));
        fixture.remove(0,99,0); // Missing key must not reduce this column back to 32.
        assertEquals(1L, fixture.traversal.snapshot().get("longColumns"));
        fixture.remove(0,0,32);
        assertEquals(0L, fixture.traversal.snapshot().get("longColumns"));
        assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("removed threshold entry cannot admit")));
        for (int z = 0; z < 32; z++) fixture.remove(0,0,z);
        assertEquals(0L, fixture.traversal.snapshot().get("columns"));
    }

    @Test void constructorDerivesAdmissionFromExistingAuthoritativeSectionKeys() {
        SectionQueryFixture fixture = new SectionQueryFixture(true, false);
        for (int z = 0; z < 40; z++) fixture.add(0,0,z);
        SectionQueryTraversal<SectionQueryFixture.FakeEntity> reconstructed = fixture.newTraversal(true);
        assertEquals(1L, reconstructed.snapshot().get("columns"));
        assertEquals(1L, reconstructed.snapshot().get("longColumns"));
        List<SectionQueryFixture.Visit> original = fixture.trace(false, box(0,0,0,0,0,0), null, 0);
        List<Integer> count = new ArrayList<>();
        assertTrue(reconstructed.tryTraverse(box(0,0,0,0,0,0), section -> {
            count.add(section.size()); return Continuation.CONTINUE;
        }));
        assertEquals(original.size(), count.size());
    }

    @Test void geometryAdmissionRequiresMoreThanFourTimesTheQueryColumnCells() {
        SectionQueryFixture fixture = new SectionQueryFixture(true, false);
        for (int z = 0; z < 64; z++) fixture.add(0,0,z);
        AABB query = box(0,0,0,0,3,3); // Sixteen query cells per X column; admission needs more than 64.
        assertFalse(fixture.traversal.tryTraverse(query, ignored -> fail("quarter-density boundary uses vanilla")));
        fixture.add(0,0,64);
        assertEquals(fixture.trace(false, query, null, 0), fixture.trace(true, query, null, 0));
        assertEquals(1L, fixture.traversal.snapshot().get("queries"));
        assertEquals(0L, fixture.traversal.snapshot().get("prefixKeys"));
        assertEquals(4L, fixture.traversal.snapshot().get("seeks"));
        assertTrue(fixture.traversal.snapshot().get("visitedKeys") <= 16 + 4,
                "Admitted traversal visits only query cells plus at most one overshoot per interval");
    }

    @Test void mixedEligibleQueryUsesOriginalCursorForShortColumnsIncludingCallbackMutation() {
        SectionQueryFixture reference = mixedFixture(), optimized = mixedFixture();
        SectionQueryFixture.Mutation mutation = (fixture, accepted) -> {
            if (accepted == 1) { fixture.remove(1,2,0); fixture.add(1,1,0); }
        };
        AABB query = box(0,0,0,1,3,0);
        assertEquals(reference.trace(false, query, mutation, 0), optimized.trace(true, query, mutation, 0));
        Map<String, Long> counters = optimized.traversal.snapshot();
        assertEquals(1L, counters.get("queries"));
        assertEquals(1L, counters.get("columnDrains"));
        assertEquals(0L, counters.get("prefixKeys"));
        assertEquals(1L, counters.get("seeks"));
    }

    @Test void foreignLifecycleEventsDisableAdmissionWithoutEditingDerivedCounts() throws Exception {
        SectionQueryFixture fixture = new SectionQueryFixture(true, false);
        for (int z = 0; z < 40; z++) fixture.add(0,0,z);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                fixture.traversal.sectionCreated(SectionPos.asLong(5,0,0));
                fixture.traversal.sectionRemoving(SectionPos.asLong(0,0,0));
            } catch (Throwable problem) { failure.set(problem); }
        });
        foreign.start(); foreign.join();
        assertNull(failure.get());
        assertEquals(1L, fixture.traversal.snapshot().get("columns"));
        assertEquals(1L, fixture.traversal.snapshot().get("longColumns"));
        assertFalse(fixture.traversal.tryTraverse(box(0,0,0,0,0,0), ignored -> fail("foreign state must fall back")));
    }

    private static SectionQueryFixture localFixture() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        fixture.add(0, 0, 0); fixture.add(0, 2, 0); fixture.add(0, 3, 0); fixture.add(0, 200, 0);
        return fixture;
    }

    private static void assertMutationMatches(SectionQueryFixture.Mutation mutation) {
        SectionQueryFixture reference = localFixture(), optimized = localFixture();
        AABB query = box(0, 0, 0, 0, 3, 0);
        assertEquals(reference.trace(false, query, mutation, 0), optimized.trace(true, query, mutation, 0));
        assertEquals(1L, optimized.traversal.snapshot().get("seeks"), "Mutation runs at the first seeking callback");
        assertEquals(1L, optimized.traversal.snapshot().get("mutationFallbacks"));
    }

    private static SectionQueryFixture.Mutation nestedMutation(boolean optimized) {
        return (fixture, accepted) -> {
            if (accepted != 1) return;
            fixture.trace(optimized, box(0, 0, 0, 0, 3, 0), (nested, nestedAccepted) -> {
                if (nestedAccepted == 1) { nested.remove(0, 2, 0); nested.add(0, 1, 0); }
            }, 0);
        };
    }

    private static void assertSeekMutationMatches(SectionQueryFixture.Mutation mutation) {
        SectionQueryFixture reference = seekFixture(), optimized = seekFixture();
        AABB query = box(0, 0, 100, 0, 3, 100);
        assertEquals(reference.trace(false, query, mutation, 0), optimized.trace(true, query, mutation, 0));
        Map<String, Long> counters = optimized.traversal.snapshot();
        assertEquals(0L, counters.get("prefixKeys"));
        assertEquals(1L, counters.get("seeks"));
        assertEquals(1L, counters.get("mutationFallbacks"));
    }

    private static SectionQueryFixture seekFixture() {
        SectionQueryFixture fixture = new SectionQueryFixture();
        for (int z = 0; z < 100; z++) fixture.add(0,0,z);
        fixture.add(0,0,100); fixture.add(0,2,100); fixture.add(0,3,100); fixture.add(0,200,100);
        return fixture;
    }

    private static SectionQueryFixture mixedFixture() {
        SectionQueryFixture fixture = new SectionQueryFixture(true, false);
        for (int z = 100; z < 164; z++) fixture.add(0,0,z);
        fixture.add(1,0,0); fixture.add(1,2,0);
        return fixture;
    }
}
