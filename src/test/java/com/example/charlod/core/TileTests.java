package com.example.charlod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TileTests {
    @Test
    void keysSurviveNegativeCoordinates() {
        for (int level : new int[]{0, 3, 6}) {
            for (int tx : new int[]{0, 1, -1, 12345, -98765, 1 << 26, -(1 << 26)}) {
                for (int tz : new int[]{0, 7, -7, -1 << 25}) {
                    long k = LodMath.key(level, tx, tz);
                    assertEquals(level, LodMath.level(k));
                    assertEquals(tx, LodMath.tx(k));
                    assertEquals(tz, LodMath.tz(k));
                }
            }
        }
    }

    @Test
    void parentsAndChildrenAgreeOnNegativeTiles() {
        long k = LodMath.key(2, -5, 3);
        for (int i = 0; i < 4; i++) assertEquals(k, LodMath.parent(LodMath.child(k, i)));
        assertEquals(-1, LodMath.tileOfBlock(-1, 0));
        assertEquals(0, LodMath.tileOfBlock(127, 0));
        assertEquals(1, LodMath.tileOfBlock(128, 0));
        assertEquals(128, LodMath.tileBlocks(0));
        assertEquals(8192, LodMath.tileBlocks(6));
    }

    private static boolean merge(short[] h, int[] t, int[] s, short[] dh, int[] dt, int[] ds) {
        return ColumnMerge.merge(h, t, s, 0, 1, 2, 3, dh, dt, ds, 0);
    }

    @Test
    void mergeUsesMeanWithAPullTowardsThePeak() {
        short[] h = {60, 60, 60, 100};
        int[] t = {0x102030, 0x102030, 0x102030, 0x102030};
        int[] s = new int[4];
        short[] dh = {LodMath.NO_DATA};
        int[] dt = new int[1], ds = new int[1];
        assertTrue(merge(h, t, s, dh, dt, ds));
        assertEquals(78, dh[0]); // mean 70, max 100
        assertEquals(0x102030, dt[0]);
        assertFalse(merge(h, t, s, dh, dt, ds), "the same data changes nothing");
    }

    @Test
    void mergeCountsWaterOnlyWhenAtLeastHalfIsWater() {
        int w = TileData.F_WATER | 0x0000FF, g = 0x00FF00;
        short[] dh = {LodMath.NO_DATA};
        int[] dt = new int[1], ds = new int[1];
        merge(new short[]{63, 63, 90, 90}, new int[]{w, w, g, g}, new int[4], dh, dt, ds);
        assertEquals(63, dh[0], "half water stays water at sea level");
        assertEquals(w, dt[0]);
        merge(new short[]{63, 90, 90, 90}, new int[]{w, g, g, g}, new int[4], dh, dt, ds);
        assertEquals(90, dh[0]);
        assertEquals(g, dt[0]);
    }

    @Test
    void mergeIgnoresMissingColumns() {
        short n = LodMath.NO_DATA;
        short[] dh = {n};
        int[] dt = new int[1], ds = new int[1];
        merge(new short[]{n, n, n, 70}, new int[]{0, 0, 0, 0x808080}, new int[4], dh, dt, ds);
        assertEquals(70, dh[0]);
        assertEquals(0x808080, dt[0]);
        assertTrue(merge(new short[]{n, n, n, n}, new int[4], new int[4], dh, dt, ds));
        assertEquals(n, dh[0]);
    }

    @Test
    void parentQuarterComesFromChild() {
        TileData child = new TileData(LodMath.key(0, 1, 0));
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            child.height[i] = 70;
            child.top[i] = 0x336699;
            child.side[i] = 0x112233;
        }
        TileData parent = new TileData(LodMath.parent(child.key));
        assertTrue(parent.fillQuarterFrom(child, 1, 0));
        assertEquals(LodMath.NO_DATA, parent.height[LodMath.cell(31, 0)]);
        assertEquals(70, parent.height[LodMath.cell(32, 0)]);
        assertEquals(70, parent.height[LodMath.cell(63, 31)]);
        assertEquals(LodMath.NO_DATA, parent.height[LodMath.cell(32, 32)]);
        assertEquals(0x336699, parent.top[LodMath.cell(40, 10)]);
        assertFalse(parent.fillQuarterFrom(child, 1, 0));
    }

    @Test
    void codecRoundTrips() throws IOException {
        Random r = new Random(5);
        TileData t = new TileData(LodMath.key(1, -3, 9));
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            if (i % 37 == 0) continue;
            t.height[i] = (short) (r.nextInt(400) - 64);
            t.top[i] = (r.nextInt(8) << 24) | r.nextInt(1 << 24);
            t.side[i] = r.nextInt(1 << 24);
        }
        TileCodec codec = new TileCodec();
        byte[] packed = codec.encode(t);
        TileData back = codec.decode(t.key, packed, 0, packed.length);
        assertArrayEquals(t.height, back.height);
        assertArrayEquals(t.top, back.top);
        assertArrayEquals(t.side, back.side);
        packed[packed.length / 2] ^= 0x55;
        packed[3] ^= 0x7F;
        assertThrows(IOException.class, () -> codec.decode(t.key, packed, 0, packed.length));
        codec.close();
    }

    @Test
    void flatTilesCompressToAlmostNothing() {
        TileData t = new TileData(LodMath.key(0, 0, 0));
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            t.height[i] = 63;
            t.top[i] = TileData.F_WATER | 0x2040A0;
            t.side[i] = 0x2040A0;
        }
        TileCodec codec = new TileCodec();
        assertTrue(codec.encode(t).length < 300);
        codec.close();
    }

    @Test
    void packsRoundTripAndGrow(@TempDir Path dir) throws IOException {
        PackStore p = new PackStore(dir);
        long a = LodMath.key(0, 3, 4), b = LodMath.key(0, -1, -1), c = LodMath.key(0, 4, 4);
        assertNull(p.read(a));
        byte[] small = new byte[100], big = new byte[5000];
        new Random(1).nextBytes(small);
        new Random(2).nextBytes(big);
        p.write(a, small);
        p.write(b, big);
        p.write(c, small);
        assertArrayEquals(small, p.read(a));
        assertArrayEquals(big, p.read(b));
        p.write(a, big); // too big for its old place: moves to the end, nothing else is hurt
        assertArrayEquals(big, p.read(a));
        assertArrayEquals(small, p.read(c));
        assertArrayEquals(big, p.read(b));
        p.close();
        PackStore again = new PackStore(dir);
        assertArrayEquals(big, again.read(a));
        assertArrayEquals(small, again.read(c));
        assertNull(again.read(LodMath.key(0, 5, 5)));
        assertTrue(again.bytesOnDisk() > 5000);
        again.close();
    }

    private static ChunkColumns flatChunk(int h, int colour) {
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
    void chunksBubbleUpToEveryLevelAndSurviveARestart(@TempDir Path dir) throws IOException {
        List<Long> changed = new ArrayList<>();
        TileStore s = new TileStore(dir, 16, changed::add);
        assertTrue(s.putChunk(-1, 2, flatChunk(80, 0x224466)));
        assertFalse(s.putChunk(-1, 2, flatChunk(80, 0x224466)), "same chunk again is not a change");
        s.flush();
        assertFalse(s.hasDirty());
        // block x -16..-1, z 32..47
        for (int level = 0; level <= LodMath.TOP_LEVEL; level++) {
            int tx = LodMath.tileOfBlock(-8, level), tz = LodMath.tileOfBlock(40, level);
            long k = LodMath.key(level, tx, tz);
            assertTrue(changed.contains(k), "level " + level);
            TileData t = s.read(k);
            int col = LodMath.blocksPerColumn(level);
            int cx = Math.floorMod(-8, LodMath.tileBlocks(level)) / col, cz = Math.floorMod(40, LodMath.tileBlocks(level)) / col;
            assertEquals(80, t.height[LodMath.cell(cx, cz)], "level " + level);
            assertEquals(0x224466, t.top[LodMath.cell(cx, cz)] & TileData.RGB);
        }
        s.close();
        TileStore again = new TileStore(dir, 16, k -> {});
        long k0 = LodMath.key(0, LodMath.tileOfBlock(-8, 0), LodMath.tileOfBlock(40, 0));
        assertEquals(80, again.read(k0).height[LodMath.cell((Math.floorMod(-8, 128)) / 2, 40 / 2)]);
        assertNull(again.read(LodMath.key(0, 500, 500)));
        again.close();
    }

    @Test
    void storeKeepsChangesWhenItsCacheIsSmall(@TempDir Path dir) throws IOException {
        TileStore s = new TileStore(dir, 4, k -> {});
        for (int i = 0; i < 40; i++) s.putChunk(i * 8, 0, flatChunk(70 + i, 0x404040));
        s.flush();
        for (int i = 0; i < 40; i++) {
            TileData t = s.read(LodMath.key(0, i, 0));
            assertNotNull(t);
            assertEquals(70 + i, t.height[0]);
        }
        assertTrue(s.cachedTiles() <= 12);
        s.close();
    }
}
