package dev.overhaul.core;

import dev.overhaul.core.spatial.SpatialHash;
import dev.overhaul.core.world.TickWorkQueue;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialAndTickTest {
    @Test void queriesMatchBruteForceAcrossMovementRemovalAndNegativeCells() {
        SpatialHash index = new SpatialHash(16);
        Random random = new Random(1234);
        double[][] positions = new double[1000][3];
        for (int i = 0; i < positions.length; i++) {
            for (int axis = 0; axis < 3; axis++) positions[i][axis] = random.nextDouble() * 400 - 200;
            index.update(i, positions[i][0], positions[i][1], positions[i][2]);
        }
        for (int i = 0; i < 1000; i += 3) { index.update(i, 0,0,0); positions[i] = new double[3]; }
        for (int i = 0; i < 1000; i += 5) index.remove(i);
        for (int query = 0; query < 100; query++) {
            double x = random.nextDouble()*100-50, y = random.nextDouble()*100-50, z = random.nextDouble()*100-50;
            double radius = random.nextDouble()*70;
            Set<Long> expected = new HashSet<Long>(), actual = new HashSet<Long>();
            for (int i = 0; i < 1000; i++) {
                double dx=positions[i][0]-x, dy=positions[i][1]-y, dz=positions[i][2]-z;
                if (i % 5 != 0 && dx*dx+dy*dy+dz*dz <= radius*radius) expected.add((long)i);
            }
            assertEquals(expected.size(), index.querySphere(x,y,z,radius,actual::add));
            assertEquals(expected, actual);
        }
        assertEquals(800, index.size());
    }

    @Test void tickBudgetCarriesFifoWorkWithoutReorderingOrUnboundedRequeue() {
        AtomicLong clock = new AtomicLong();
        TickWorkQueue queue = new TickWorkQueue(3, clock::get);
        StringBuilder order = new StringBuilder();
        assertTrue(queue.offer(() -> { order.append('a'); clock.addAndGet(6); }));
        assertTrue(queue.offer(() -> { order.append('b'); clock.addAndGet(6); }));
        assertTrue(queue.offer(() -> order.append('c')));
        assertFalse(queue.offer(() -> order.append('d')));
        assertEquals(2, queue.run(10));
        assertEquals("ab", order.toString());
        assertEquals(1, queue.pendingTasks());
        assertEquals(0, queue.run(0));
        assertEquals(1, queue.run(10));
        assertEquals("abc", order.toString());
    }
}
