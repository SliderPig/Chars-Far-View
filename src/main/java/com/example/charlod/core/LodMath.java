package com.example.charlod.core;

// Where tiles sit. Every tile is 64 x 64 columns whatever its level; level 0 uses 2 blocks per column, each level up doubles that.
public final class LodMath {
    public static final int TILE_COLS = 64;
    public static final int TILE_CELLS = TILE_COLS * TILE_COLS;
    public static final int TOP_LEVEL = 6;
    public static final short NO_DATA = Short.MIN_VALUE;

    private LodMath() {}

    public static int blocksPerColumn(int level) {
        return 2 << level;
    }

    public static int tileBlocks(int level) {
        return TILE_COLS * blocksPerColumn(level);
    }

    public static int tileOfBlock(int block, int level) {
        return block >> (level + 7);
    }

    public static int tileMinBlock(int tile, int level) {
        return tile << (level + 7);
    }

    public static long key(int level, int tx, int tz) {
        return ((long) level << 56) | ((long) (tx & 0xFFFFFFF) << 28) | (tz & 0xFFFFFFFL);
    }

    public static int level(long key) {
        return (int) (key >>> 56);
    }

    public static int tx(long key) {
        return ((int) (key >>> 28)) << 4 >> 4;
    }

    public static int tz(long key) {
        return ((int) key) << 4 >> 4;
    }

    public static long parent(long key) {
        return key(level(key) + 1, tx(key) >> 1, tz(key) >> 1);
    }

    public static long child(long key, int which) {
        return key(level(key) - 1, tx(key) * 2 + (which & 1), tz(key) * 2 + (which >> 1));
    }

    // Column index inside a tile.
    public static int cell(int x, int z) {
        return z * TILE_COLS + x;
    }
}
