package com.example.charlod.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MesherTests {
    private record V(int x, int z, int y, int rgba) {}

    private static TileData flat(int level, int h, int colour) {
        TileData t = new TileData(LodMath.key(level, 0, 0));
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            t.height[i] = (short) h;
            t.top[i] = colour;
            t.side[i] = colour;
        }
        return t;
    }

    private static GreedyMesher.Mesh mesh(TileData t) {
        return new GreedyMesher().build(t, GreedyMesher.Shade.NORMAL, new GreedyMesher.Mesh());
    }

    private static List<V[]> quads(GreedyMesher.Mesh m) {
        List<V[]> out = new ArrayList<>();
        byte[] b = m.bytes;
        for (int q = 0; q < m.quads; q++) {
            V[] v = new V[4];
            for (int i = 0; i < 4; i++) {
                int p = (q * 4 + i) * GreedyMesher.VERTEX_BYTES;
                int y = (short) ((b[p + 2] & 255) | (b[p + 3] << 8));
                v[i] = new V(b[p] & 255, b[p + 1] & 255, y, (b[p + 4] & 255) | ((b[p + 5] & 255) << 8) | ((b[p + 6] & 255) << 16) | ((b[p + 7] & 255) << 24));
            }
            out.add(v);
        }
        return out;
    }

    // Normal of the quad by the right hand rule, the way the graphics card sees it when it culls back faces.
    private static int[] normal(V[] q) {
        int ax = q[1].x - q[0].x, ay = q[1].y - q[0].y, az = q[1].z - q[0].z;
        int bx = q[2].x - q[1].x, by = q[2].y - q[1].y, bz = q[2].z - q[1].z;
        return new int[]{Integer.signum(ay * bz - az * by), Integer.signum(az * bx - ax * bz), Integer.signum(ax * by - ay * bx)};
    }

    @Test
    void aFlatTileIsOneTopQuadAndFourSkirts() {
        GreedyMesher.Mesh m = mesh(flat(0, 63, 0x204080));
        assertEquals(5, m.quads);
        assertEquals(20, m.vertices);
        assertEquals(20 * GreedyMesher.VERTEX_BYTES, m.byteCount());
        assertEquals(63, m.maxY);
        assertEquals(63 - GreedyMesher.skirtDepth(0), m.minY);
        V[] top = quads(m).get(0);
        assertArrayEquals(new int[]{0, 1, 0}, normal(top));
        assertEquals(0x204080 >> 16, top[0].rgba & 255);
    }

    @Test
    void topsCoverEveryColumnExactlyOnce() {
        TileData t = flat(0, 64, 0x305030);
        java.util.Random r = new java.util.Random(11);
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            t.height[i] = (short) (60 + r.nextInt(4));
            t.top[i] = r.nextBoolean() ? 0x305030 : 0x803030;
        }
        for (int i = 0; i < 300; i++) t.height[r.nextInt(LodMath.TILE_CELLS)] = LodMath.NO_DATA;
        int[] hits = new int[LodMath.TILE_CELLS];
        for (V[] q : quads(mesh(t))) {
            if (normal(q)[1] != 1) continue;
            int x0 = q[0].x, z0 = q[0].z, x1 = q[2].x, z1 = q[2].z;
            for (int z = z0; z < z1; z++) {
                for (int x = x0; x < x1; x++) {
                    hits[LodMath.cell(x, z)]++;
                    assertEquals(t.height[LodMath.cell(x, z)], q[0].y);
                }
            }
        }
        for (int i = 0; i < LodMath.TILE_CELLS; i++) assertEquals(t.height[i] == LodMath.NO_DATA ? 0 : 1, hits[i], "cell " + i);
    }

    @Test
    void sidesFaceOutwardsAndOnlyShowWhereTheNeighbourIsLower() {
        TileData t = flat(0, 60, 0x404040);
        t.height[LodMath.cell(10, 10)] = 65; // one pillar
        int pillarSides = 0;
        for (V[] q : quads(mesh(t))) {
            int[] n = normal(q);
            if (n[1] != 0) {
                assertEquals(1, n[1], "no face points down");
                continue;
            }
            int top = Math.max(Math.max(q[0].y, q[1].y), q[2].y), bottom = Math.min(Math.min(q[0].y, q[1].y), q[2].y);
            if (top != 65) continue;
            assertEquals(60, bottom);
            pillarSides++;
            int xs = Math.min(Math.min(q[0].x, q[1].x), q[2].x), zs = Math.min(Math.min(q[0].z, q[1].z), q[2].z);
            if (n[0] == 1) assertEquals(11, q[0].x);
            if (n[0] == -1) assertEquals(10, xs);
            if (n[2] == 1) assertEquals(11, q[0].z);
            if (n[2] == -1) assertEquals(10, zs);
        }
        assertEquals(4, pillarSides);
    }

    @Test
    void sideFacesGetDarkerAtTheFoot() {
        TileData t = flat(0, 60, 0xC0C0C0);
        t.height[LodMath.cell(10, 10)] = 80;
        for (V[] q : quads(mesh(t))) {
            if (normal(q)[1] != 0 || Math.max(q[0].y, q[1].y) != 80) continue;
            V high = q[0].y == 80 ? q[0] : q[1], low = q[0].y == 60 ? q[0] : q[1];
            assertTrue((low.rgba & 255) < (high.rgba & 255));
        }
    }

    @Test
    void waterAndGroundNeverMergeEvenAtTheSameHeightAndColour() {
        TileData t = flat(0, 63, 0x204080);
        for (int z = 0; z < 64; z++) for (int x = 32; x < 64; x++) t.top[LodMath.cell(x, z)] = 0x204080 | TileData.F_WATER;
        long tops = quads(mesh(t)).stream().filter(q -> normal(q)[1] == 1).count();
        assertEquals(2, tops);
    }

    @Test
    void glowingColumnsKeepTheirFullColourAndFlag() {
        TileData t = flat(0, 40, 0xFF8000 | TileData.F_GLOW);
        V[] top = quads(mesh(t)).get(0);
        assertEquals(255, top[0].rgba >>> 24);
        assertEquals(0xFF, top[0].rgba & 255);
    }

    @Test
    void farLevelsRoundHeightsSoFacesMerge() {
        TileData t = flat(5, 64, 0x808080);
        for (int i = 0; i < LodMath.TILE_CELLS; i++) t.height[i] = (short) (64 + (i % 3)); // bumps smaller than the rounding step
        assertEquals(8, GreedyMesher.heightStep(5));
        GreedyMesher.Mesh far = mesh(t);
        assertEquals(5, far.quads);
    }

    @Test
    void worstCaseFitsTheSharedIndexBuffer() {
        TileData t = flat(0, 64, 0);
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                t.height[LodMath.cell(x, z)] = (short) (((x + z) & 1) == 0 ? 100 : 60);
                t.top[LodMath.cell(x, z)] = (x * 3) << 16 | (z * 3);
            }
        }
        GreedyMesher.Mesh m = mesh(t);
        assertTrue(m.vertices <= GreedyMesher.MAX_VERTS, "vertices " + m.vertices);
    }

    @Test
    void missingColumnsMakeNoGeometry() {
        assertEquals(0, mesh(new TileData(LodMath.key(0, 0, 0))).quads);
    }
}
