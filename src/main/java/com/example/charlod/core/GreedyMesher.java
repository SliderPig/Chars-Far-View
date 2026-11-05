package com.example.charlod.core;

import java.util.Arrays;

// Turns a tile into quads: flat tops merged into big rectangles, side faces merged along each row of columns.
// One vertex is 8 bytes: x (0-64), z (0-64) as bytes, y as a short, colour r g b, and glow (255 = lights itself).
public final class GreedyMesher {
    public static final int VERTEX_BYTES = 8;
    public static final int MAX_VERTS = 65536;

    // The game's own face shading, baked into the colours.
    public static final class Shade {
        public final float up, northSouth, eastWest;

        public Shade(float up, float northSouth, float eastWest) {
            this.up = up;
            this.northSouth = northSouth;
            this.eastWest = eastWest;
        }

        public static final Shade NORMAL = new Shade(1f, 0.8f, 0.6f);
        public static final Shade CONSTANT = new Shade(0.9f, 0.8f, 0.6f);
    }

    public static final class Mesh {
        public byte[] bytes = new byte[64 * 1024];
        public int vertices;
        public int quads;
        public int minY, maxY;

        public int byteCount() {
            return vertices * VERTEX_BYTES;
        }

        void ensure(int moreVerts) {
            int need = (vertices + moreVerts) * VERTEX_BYTES;
            if (need > bytes.length) bytes = Arrays.copyOf(bytes, Math.max(need, bytes.length * 2));
        }

        public void reset() {
            vertices = 0;
            quads = 0;
            minY = Integer.MAX_VALUE;
            maxY = Integer.MIN_VALUE;
        }
    }

    private final short[] h = new short[LodMath.TILE_CELLS];
    private final boolean[] used = new boolean[LodMath.TILE_CELLS];

    // How far a tile's edges hang down, so there is never a crack beside a tile of another level.
    public static int skirtDepth(int level) {
        return 48 + 8 * LodMath.blocksPerColumn(level);
    }

    // Far away one block of height is less than a pixel: rounding it merges far more faces.
    public static int heightStep(int level) {
        return level < 3 ? 1 : Math.min(8, 1 << (level - 2));
    }

    public static int colourTolerance(int level) {
        return 3 + level;
    }

    public Mesh build(TileData t, Shade shade, Mesh out) {
        int level = LodMath.level(t.key);
        int n = LodMath.TILE_COLS;
        int step = heightStep(level);
        int tol = colourTolerance(level);
        out.reset();
        short[] src = t.height;
        for (int i = 0; i < h.length; i++) {
            short v = src[i];
            h[i] = step == 1 || v == LodMath.NO_DATA ? v : (short) (Math.floorDiv(v + step / 2, step) * step);
        }
        int[] top = t.top;
        // tops
        Arrays.fill(used, false);
        for (int z = 0; z < n; z++) {
            int row = z * n;
            for (int x = 0; x < n; x++) {
                int i = row + x;
                if (used[i] || h[i] == LodMath.NO_DATA) continue;
                short height = h[i];
                int colour = top[i];
                int w = 1;
                while (x + w < n && canMerge(i + w, height, colour, tol, top)) w++;
                int d = 1;
                grow:
                while (z + d < n) {
                    int r = (z + d) * n + x;
                    for (int k = 0; k < w; k++) if (!canMerge(r + k, height, colour, tol, top)) break grow;
                    d++;
                }
                for (int dz = 0; dz < d; dz++) Arrays.fill(used, (z + dz) * n + x, (z + dz) * n + x + w, true);
                top(out, x, z, x + w, z + d, height, colour, shade.up);
            }
        }
        // sides: a column shows a face wherever the one beside it is lower, or missing
        int skirt = skirtDepth(level);
        int[] sideC = t.side;
        for (int dir = 0; dir < 4; dir++) {
            boolean alongX = dir < 2; // faces looking along x run in z rows
            int sign = dir % 2 == 0 ? 1 : -1;
            float shadeOf = alongX ? shade.eastWest : shade.northSouth;
            for (int line = 0; line < n; line++) {
                int a = 0;
                while (a < n) {
                    int i = alongX ? LodMath.cell(line, a) : LodMath.cell(a, line);
                    short height = h[i];
                    if (height == LodMath.NO_DATA) {
                        a++;
                        continue;
                    }
                    int bottom = bottomOf(height, alongX ? line + sign : a, alongX ? a : line + sign, skirt);
                    if (bottom >= height) {
                        a++;
                        continue;
                    }
                    int colour = sideC[i];
                    int flags = top[i] & 0xFF000000;
                    int b = a + 1;
                    while (b < n) {
                        int j = alongX ? LodMath.cell(line, b) : LodMath.cell(b, line);
                        if (h[j] != height || !close(sideC[j], colour, tol) || (top[j] & 0xFF000000) != flags) break;
                        int bottomJ = bottomOf(h[j], alongX ? line + sign : b, alongX ? b : line + sign, skirt);
                        if (bottomJ != bottom) break;
                        b++;
                    }
                    side(out, dir, line, a, b, height, bottom, colour, flags, shadeOf);
                    a = b;
                }
            }
        }
        return out;
    }

    private int bottomOf(short height, int nx, int nz, int skirt) {
        if (nx < 0 || nz < 0 || nx >= LodMath.TILE_COLS || nz >= LodMath.TILE_COLS) return height - skirt;
        short o = h[LodMath.cell(nx, nz)];
        return o == LodMath.NO_DATA ? height - skirt : o;
    }

    private boolean canMerge(int i, short height, int colour, int tol, int[] top) {
        return !used[i] && h[i] == height && (top[i] & 0xFF000000) == (colour & 0xFF000000) && close(top[i], colour, tol);
    }

    private static boolean close(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= tol
                && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= tol
                && Math.abs((a & 255) - (b & 255)) <= tol;
    }

    private static void track(Mesh m, int y) {
        if (y < m.minY) m.minY = y;
        if (y > m.maxY) m.maxY = y;
    }

    private static void top(Mesh m, int x0, int z0, int x1, int z1, int y, int colour, float shade) {
        m.ensure(4);
        int c = packed(colour, shade);
        vertex(m, x0, z0, y, c);
        vertex(m, x0, z1, y, c);
        vertex(m, x1, z1, y, c);
        vertex(m, x1, z0, y, c);
        m.quads++;
        track(m, y);
    }

    private static void side(Mesh m, int dir, int line, int a, int b, int top, int bottom, int colour, int flags, float shade) {
        m.ensure(4);
        int high = packed(colour | flags, shade);
        // Darker towards the foot: a cheap shadow that makes cliffs and clumps of trees stand out from the ground.
        int drop = top - bottom;
        float foot = (flags & TileData.F_FOLIAGE) != 0 ? Math.max(0.62f, 1f - drop * 0.045f) : Math.max(0.72f, 1f - drop * 0.02f);
        int low = packed(colour | flags, shade * foot);
        switch (dir) {
            case 0 -> { // +x
                int x = line + 1;
                vertex(m, x, b, top, high);
                vertex(m, x, b, bottom, low);
                vertex(m, x, a, bottom, low);
                vertex(m, x, a, top, high);
            }
            case 1 -> { // -x
                vertex(m, line, a, top, high);
                vertex(m, line, a, bottom, low);
                vertex(m, line, b, bottom, low);
                vertex(m, line, b, top, high);
            }
            case 2 -> { // +z
                int z = line + 1;
                vertex(m, a, z, top, high);
                vertex(m, a, z, bottom, low);
                vertex(m, b, z, bottom, low);
                vertex(m, b, z, top, high);
            }
            default -> { // -z
                vertex(m, b, line, top, high);
                vertex(m, b, line, bottom, low);
                vertex(m, a, line, bottom, low);
                vertex(m, a, line, top, high);
            }
        }
        m.quads++;
        track(m, top);
        track(m, bottom);
    }

    private static int packed(int colour, float shade) {
        boolean glow = (colour & TileData.F_GLOW) != 0;
        float s = glow ? 1f : shade;
        int r = (int) (((colour >> 16) & 255) * s), g = (int) (((colour >> 8) & 255) * s), b = (int) ((colour & 255) * s);
        return r | (g << 8) | (b << 16) | ((glow ? 255 : 0) << 24);
    }

    private static void vertex(Mesh m, int x, int z, int y, int rgba) {
        byte[] o = m.bytes;
        int p = m.vertices * VERTEX_BYTES;
        o[p] = (byte) x;
        o[p + 1] = (byte) z;
        o[p + 2] = (byte) y;
        o[p + 3] = (byte) (y >> 8);
        o[p + 4] = (byte) rgba;
        o[p + 5] = (byte) (rgba >> 8);
        o[p + 6] = (byte) (rgba >> 16);
        o[p + 7] = (byte) (rgba >> 24);
        m.vertices++;
    }
}
