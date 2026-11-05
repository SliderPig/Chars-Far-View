package com.example.charlod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeedTests {
    private static TileData estimated(long key, int h, int colour) {
        TileData t = new TileData(key);
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            t.height[i] = (short) h;
            t.top[i] = colour | TileData.F_ESTIMATED;
            t.side[i] = colour;
        }
        return t;
    }

    private static ChunkColumns realChunk(int h, int colour) {
        ChunkColumns c = new ChunkColumns();
        c.clear();
        for (int i = 0; i < 256; i++) {
            c.height[i] = (short) h;
            c.top[i] = colour;
            c.side[i] = colour;
        }
        return c;
    }

    @Test
    void realColumnsBeatSeedMadeOnesInAMerge() {
        short[] dh = {LodMath.NO_DATA};
        int[] dt = new int[1], ds = new int[1];
        int est = TileData.F_ESTIMATED | 0x101010;
        ColumnMerge.merge(new short[]{100, 100, 100, 60}, new int[]{est, est, est, 0x505050}, new int[4], 0, 1, 2, 3, dh, dt, ds, 0);
        assertEquals(60, dh[0], "the one real column wins over three guesses");
        assertEquals(0, dt[0] & TileData.F_ESTIMATED);
        assertEquals(0x505050, dt[0] & TileData.RGB);
    }

    @Test
    void onlyGuessesStayFlaggedAsGuesses() {
        short[] dh = {LodMath.NO_DATA};
        int[] dt = new int[1], ds = new int[1];
        int est = TileData.F_ESTIMATED | 0x101010;
        ColumnMerge.merge(new short[]{70, 70, 70, 70}, new int[]{est, est, est, est}, new int[4], 0, 1, 2, 3, dh, dt, ds, 0);
        assertEquals(70, dh[0]);
        assertNotEquals(0, dt[0] & TileData.F_ESTIMATED);
    }

    @Test
    void anEmptyChildNeverWipesWhatTheSeedSaid() {
        short n = LodMath.NO_DATA;
        short[] dh = {70};
        int[] dt = {TileData.F_ESTIMATED | 0x101010}, ds = new int[1];
        assertFalse(ColumnMerge.merge(new short[]{n, n, n, n}, new int[4], new int[4], 0, 1, 2, 3, dh, dt, ds, 0));
        assertEquals(70, dh[0]);
    }

    @Test
    void newTilesStartAsABlockyCopyOfSeedTerrainAbove(@TempDir Path dir) throws IOException {
        TileStore s = new TileStore(dir, 64, k -> {});
        long top = LodMath.key(3, -1, 0);
        s.putEstimate(estimated(top, 90, 0x206020));
        s.flush();
        // A real chunk arrives inside it, at level 0: the rest of that level 0 tile must not be a hole.
        int chunkX = -16, chunkZ = 8; // block x -256, z 128
        assertTrue(s.putChunk(chunkX, chunkZ, realChunk(64, 0x808080)));
        s.flush();
        long zero = LodMath.key(0, LodMath.tileOfBlock(chunkX * 16, 0), LodMath.tileOfBlock(chunkZ * 16, 0));
        TileData t = s.read(zero);
        int cx = (Math.floorMod(chunkX * 16, 128)) / 2, cz = (Math.floorMod(chunkZ * 16, 128)) / 2;
        assertEquals(64, t.height[LodMath.cell(cx, cz)], "real where the chunk is");
        assertEquals(0, t.top[LodMath.cell(cx, cz)] & TileData.F_ESTIMATED);
        int other = LodMath.cell((cx + 20) % 64, (cz + 20) % 64);
        assertEquals(90, t.height[other], "copied down elsewhere");
        assertNotEquals(0, t.top[other] & TileData.F_COARSE);
        assertTrue(t.hasCoarseCells());
        // and the real chunk reached the top tile
        TileData up = s.read(top);
        int size = LodMath.tileBlocks(3);
        int ux = Math.floorMod(chunkX * 16, size) / 16, uz = Math.floorMod(chunkZ * 16, size) / 16;
        assertEquals(64, up.height[LodMath.cell(ux, uz)]);
        assertEquals(0, up.top[LodMath.cell(ux, uz)] & TileData.F_ESTIMATED);
        s.close();
    }

    @Test
    void estimatesNeverOverwriteRealColumnsAndNeverPassUp(@TempDir Path dir) throws IOException {
        List<Long> changed = new ArrayList<>();
        TileStore s = new TileStore(dir, 64, changed::add);
        assertTrue(s.putChunk(0, 0, realChunk(64, 0x808080)));
        s.flush();
        long key = LodMath.key(0, 0, 0);
        changed.clear();
        assertTrue(s.putEstimate(estimated(key, 90, 0x206020)));
        s.flush();
        assertEquals(List.of(key), changed, "the parent is not touched");
        TileData t = s.read(key);
        assertEquals(64, t.height[LodMath.cell(0, 0)]);
        assertEquals(90, t.height[LodMath.cell(40, 40)]);
        s.close();
        TileStore again = new TileStore(dir, 64, k -> {});
        TileData back = again.read(key);
        assertEquals(90, back.height[LodMath.cell(40, 40)], "saved, so reopening is instant");
        assertNotEquals(0, back.top[LodMath.cell(40, 40)] & TileData.F_ESTIMATED);
        again.close();
    }

    @Test
    void anEmptyEstimateIsStillRememberedSoItIsNotMadeAgain(@TempDir Path dir) throws IOException {
        TileStore s = new TileStore(dir, 64, k -> {});
        long key = LodMath.key(4, 2, 2);
        assertFalse(s.exists(key));
        s.putEstimate(new TileData(key));
        s.flush();
        assertTrue(s.exists(key));
        assertTrue(s.read(key).isEmpty());
        s.close();
    }

    @Test
    void theTruthCheckStaysQuietOnGoodTerrain() {
        TruthCheck t = new TruthCheck();
        for (int i = 0; i < 400; i++) assertFalse(t.add(i % 10 == 0 ? 20f : 2f), "a house now and then is fine");
        assertFalse(t.wrong());
        assertEquals(400, t.chunks());
        assertTrue(t.badShare() <= 0.11f);
    }

    @Test
    void theTruthCheckCatchesTerrainThatIsNotWhatTheSeedSays() {
        TruthCheck t = new TruthCheck();
        boolean caught = false;
        for (int i = 0; i < 60 && !caught; i++) caught = t.add(40f);
        assertTrue(caught);
        assertTrue(t.wrong());
    }

    @Test
    void theTruthCheckWaitsForAGoodSample() {
        TruthCheck t = new TruthCheck();
        for (int i = 0; i < TruthCheck.MIN_CHUNKS - 1; i++) assertFalse(t.add(40f));
    }

    @Test
    void aPluginsTerrainFoundAfterGoodTerrainIsStillCaught() {
        TruthCheck t = new TruthCheck();
        for (int i = 0; i < 1000; i++) assertFalse(t.add(1f));
        boolean caught = false;
        for (int i = 0; i < TruthCheck.WINDOW && !caught; i++) caught = t.add(100f);
        assertTrue(caught, "a rolling count, not an all time one");
    }

    @Test
    void medianIgnoresOneOddSample() {
        assertEquals(2f, TruthCheck.median(new float[]{1f, 100f, 2f}, 3));
        assertEquals(-1f, TruthCheck.median(new float[0], 0));
    }

    private static final class Ground implements SurfaceSearch.Solid {
        final int top;
        int asks;

        Ground(int top) {
            this.top = top;
        }

        @Override
        public boolean at(int x, int y, int z) {
            asks++;
            return y <= top;
        }
    }

    @Test
    void searchFindsTheGroundFromAnyHintInFewAsks() {
        for (int top : new int[]{-50, 0, 63, 64, 90, 240, 318}) {
            for (int hint : new int[]{-60, 64, top, top + 3, 200, 319}) {
                Ground g = new Ground(top);
                int y = SurfaceSearch.fromHint(g, 0, 0, -64, 319, hint, 1);
                assertEquals(top, y, "top " + top + " hint " + hint);
                assertTrue(g.asks < 22, "asks " + g.asks);
            }
        }
        Ground near = new Ground(70);
        SurfaceSearch.fromHint(near, 0, 0, -64, 319, 68, 1);
        assertTrue(near.asks <= 6, "a good hint needs hardly any, got " + near.asks);
    }

    @Test
    void searchReportsAColumnOfAir() {
        assertEquals(Integer.MIN_VALUE, SurfaceSearch.fromHint((x, y, z) -> false, 0, 0, 0, 255, 64, 1));
        assertEquals(Integer.MIN_VALUE, SurfaceSearch.fromAbove((x, y, z) -> false, 0, 0, 0, 96, 8, 1));
    }

    @Test
    void searchComesDownOnIslandsAndFloorsUnderRoofs() {
        SurfaceSearch.Solid island = (x, y, z) -> y >= 40 && y <= 70;
        assertEquals(70, SurfaceSearch.fromAbove(island, 0, 0, 0, 96, 6, 1));
        SurfaceSearch.Solid nether = (x, y, z) -> y >= 100 || y <= 45;
        assertEquals(45, SurfaceSearch.belowRoof(nether, 0, 0, 0, 120, 4, 1));
        assertEquals(Integer.MIN_VALUE, SurfaceSearch.belowRoof((x, y, z) -> true, 0, 0, 0, 120, 4, 1));
    }

    @Test
    void forestsHaveTreesAndPlainsAlmostNone() {
        long seed = 12345L;
        int forest = 0, plains = 0;
        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 256; z++) {
                if (TreeScatter.canopyAt(seed, x, z, 10f, 7, 2.6f) > 0) forest++;
                if (TreeScatter.canopyAt(seed, x, z, 0.05f, 7, 2.6f) > 0) plains++;
            }
        }
        float forestShare = forest / 65536f, plainShare = plains / 65536f;
        assertTrue(forestShare > 0.4f && forestShare < 0.95f, "forest " + forestShare);
        assertTrue(plainShare < 0.05f, "plains " + plainShare);
        assertTrue(TreeScatter.cover(10f, 2.6f) > 0.5f);
        assertTrue(TreeScatter.cover(0.05f, 2.6f) < 0.05f);
        assertEquals(0f, TreeScatter.cover(0f, 2.6f));
        assertEquals(0, TreeScatter.canopyAt(seed, 5, 5, 0f, 7, 2.6f));
    }

    @Test
    void treesStayPutForTheSameSeedAndMoveForAnother() {
        Set<Integer> a = new HashSet<>(), b = new HashSet<>(), c = new HashSet<>();
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                if (TreeScatter.canopyAt(1L, x, z, 4f, 7, 2.6f) > 0) a.add(x * 1000 + z);
                if (TreeScatter.canopyAt(1L, x, z, 4f, 7, 2.6f) > 0) b.add(x * 1000 + z);
                if (TreeScatter.canopyAt(2L, x, z, 4f, 7, 2.6f) > 0) c.add(x * 1000 + z);
            }
        }
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void selectorKeepsTheParentWhileTheSeedFillsAnEmptyChild() {
        java.util.Map<Long, Integer> states = new java.util.HashMap<>();
        long root = LodMath.key(LodMath.TOP_LEVEL, 0, 0);
        states.put(root, Selector.READY);
        List<Long> drawn = new ArrayList<>(), asked = new ArrayList<>();
        Selector.Tiles tiles = new Selector.Tiles() {
            @Override
            public int state(long key) {
                return states.getOrDefault(key, Selector.EMPTY);
            }

            @Override
            public void want(long key, double priority) {
            }

            @Override
            public boolean missing(long key, double priority) {
                asked.add(key);
                return true;
            }

            @Override
            public void draw(long key, double distance) {
                drawn.add(key);
            }
        };
        new Selector(tiles).select(100, 70, 100, 1000, 96);
        assertTrue(drawn.contains(root));
        assertTrue(asked.contains(LodMath.child(root, 0)));
    }
}
