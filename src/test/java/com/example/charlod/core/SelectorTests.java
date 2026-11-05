package com.example.charlod.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SelectorTests {
    private static final class Fake implements Selector.Tiles {
        final Map<Long, Integer> states = new HashMap<>();
        int fallback = Selector.READY;
        final List<Long> drawn = new ArrayList<>();
        final Set<Long> wanted = new HashSet<>();

        @Override
        public int state(long key) {
            return states.getOrDefault(key, fallback);
        }

        @Override
        public void want(long key, double priority) {
            wanted.add(key);
        }

        @Override
        public void draw(long key, double distance) {
            drawn.add(key);
        }
    }

    private static int[] box(long k) {
        int size = LodMath.tileBlocks(LodMath.level(k));
        int x = LodMath.tx(k) * size, z = LodMath.tz(k) * size;
        return new int[]{x, z, x + size, z + size};
    }

    @Test
    void withEverythingReadyNothingOverlapsAndNearIsFinerThanFar() {
        Fake f = new Fake();
        new Selector(f).select(100, 70, 100, 4096, 96);
        assertFalse(f.drawn.isEmpty());
        assertTrue(f.wanted.isEmpty());
        for (int a = 0; a < f.drawn.size(); a++) {
            int[] p = box(f.drawn.get(a));
            for (int b = a + 1; b < f.drawn.size(); b++) {
                int[] q = box(f.drawn.get(b));
                boolean apart = p[2] <= q[0] || q[2] <= p[0] || p[3] <= q[1] || q[3] <= p[1];
                assertTrue(apart, "tiles overlap");
            }
        }
        long around = f.drawn.stream().filter(k -> {
            int[] b = box(k);
            return b[0] <= 100 && 100 < b[2] && b[1] <= 100 && 100 < b[3];
        }).findFirst().orElseThrow();
        assertEquals(0, LodMath.level(around), "right where you stand it is the finest level");
        int coarsest = f.drawn.stream().mapToInt(LodMath::level).max().orElse(0);
        assertTrue(coarsest >= 3, "far away it is coarse, got " + coarsest);
        assertTrue(f.drawn.size() < 450, "keeps the number of tiles small, got " + f.drawn.size());
    }

    @Test
    void everyPointInRangeIsCovered() {
        Fake f = new Fake();
        new Selector(f).select(-5000, 70, 900, 2048, 96);
        for (int a = 0; a < 360; a += 7) {
            for (double r = 10; r < 2000; r += 97) {
                int x = (int) (-5000 + r * Math.cos(Math.toRadians(a))), z = (int) (900 + r * Math.sin(Math.toRadians(a)));
                boolean found = false;
                for (long k : f.drawn) {
                    int[] b = box(k);
                    if (b[0] <= x && x < b[2] && b[1] <= z && z < b[3]) found = true;
                }
                assertTrue(found, x + "," + z);
            }
        }
    }

    @Test
    void whileChildrenLoadTheParentIsDrawnAndChildrenAreAskedFor() {
        Fake f = new Fake();
        long root = LodMath.key(LodMath.TOP_LEVEL, 0, 0);
        long child = LodMath.child(root, 0);
        f.states.put(child, Selector.UNKNOWN);
        new Selector(f).select(10, 70, 10, 1000, 96);
        assertTrue(f.drawn.contains(root));
        assertTrue(f.wanted.contains(child));
    }

    @Test
    void anEmptyTileAsksForNothingBelowIt() {
        Fake f = new Fake();
        f.fallback = Selector.EMPTY;
        new Selector(f).select(10, 70, 10, 1000, 96);
        assertTrue(f.drawn.isEmpty());
        assertTrue(f.wanted.isEmpty());
    }

    @Test
    void anEmptyChildDoesNotHoldUpItsBrothers() {
        Fake f = new Fake();
        long root = LodMath.key(LodMath.TOP_LEVEL, 0, 0);
        f.states.put(LodMath.child(root, 3), Selector.EMPTY);
        new Selector(f).select(10, 70, 10, 1000, 96);
        assertFalse(f.drawn.contains(root));
    }

    @Test
    void nothingKnownStartsFromTheTop() {
        Fake f = new Fake();
        f.fallback = Selector.UNKNOWN;
        new Selector(f).select(10, 70, 10, 1000, 96);
        assertTrue(f.drawn.isEmpty());
        assertFalse(f.wanted.isEmpty());
        for (long k : f.wanted) assertEquals(LodMath.TOP_LEVEL, LodMath.level(k));
    }

    @Test
    void flyingHighGetsCoarser() {
        Fake low = new Fake(), high = new Fake();
        new Selector(low).select(0, 70, 0, 4096, 96);
        new Selector(high).select(0, 1200, 0, 4096, 96);
        assertTrue(high.drawn.size() < low.drawn.size());
    }

    @Test
    void frustumKeepsWhatIsInFrontOfTheCamera() {
        // Looking down -z with a plain perspective matrix (fov 90, square, near 1, far 100), column major.
        float n = 1, f = 100;
        float[] m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, (f + n) / (n - f), -1, 0, 0, 2 * f * n / (n - f), 0};
        Frustum fr = new Frustum();
        fr.set(m);
        assertTrue(fr.visible(-1, -1, -10, 1, 1, -5));
        assertFalse(fr.visible(-1, -1, 5, 1, 1, 10), "behind");
        assertFalse(fr.visible(50, -1, -10, 60, 1, -5), "off to the side");
        assertFalse(fr.visible(-1, -1, -300, 1, 1, -200), "too far");
        assertTrue(fr.visible(-500, -500, -50, 500, 500, -40), "big box around the view");
    }
}
