package com.example.charlod.core;

import java.util.Arrays;

// The columns of one tile. top = RGB with flags in the top byte, side = colour of its side faces.
public final class TileData {
    public static final int F_WATER = 1 << 24, F_FOLIAGE = 2 << 24, F_GLOW = 4 << 24;
    // Worked out from the seed, not read from real chunks: anything real replaces it. Coarse = only copied down from a rougher tile so far.
    public static final int F_ESTIMATED = 8 << 24, F_COARSE = 16 << 24;
    public static final int RGB = 0xFFFFFF;
    public static final int LOOK_FLAGS = F_WATER | F_FOLIAGE | F_GLOW;

    public final long key;
    public final short[] height = new short[LodMath.TILE_CELLS];
    public final int[] top = new int[LodMath.TILE_CELLS];
    public final int[] side = new int[LodMath.TILE_CELLS];

    public TileData(long key) {
        this.key = key;
        Arrays.fill(height, LodMath.NO_DATA);
    }

    public boolean isEmpty() {
        for (short h : height) if (h != LodMath.NO_DATA) return false;
        return true;
    }

    public boolean hasCoarseCells() {
        for (int i = 0; i < top.length; i++) if ((top[i] & F_COARSE) != 0 && height[i] != LodMath.NO_DATA) return true;
        return false;
    }

    // Fills one quarter of this (parent) tile from a child tile. Returns true if anything changed.
    public boolean fillQuarterFrom(TileData child, int quarterX, int quarterZ) {
        int half = LodMath.TILE_COLS / 2;
        boolean changed = false;
        short[] ch = child.height;
        int[] ct = child.top, cs = child.side;
        for (int z = 0; z < half; z++) {
            for (int x = 0; x < half; x++) {
                int cx = x * 2, cz = z * 2;
                int d = LodMath.cell(quarterX * half + x, quarterZ * half + z);
                changed |= ColumnMerge.merge(ch, ct, cs, LodMath.cell(cx, cz), LodMath.cell(cx + 1, cz), LodMath.cell(cx, cz + 1), LodMath.cell(cx + 1, cz + 1), height, top, side, d);
            }
        }
        return changed;
    }

    // A brand new tile under a rougher one that has seed-made terrain: starts as a blocky copy of it, so it never shows holes while the rest comes in.
    public void inheritEstimates(TileData ancestor) {
        int up = LodMath.level(ancestor.key) - LodMath.level(key);
        if (up <= 0) return;
        int ownX = LodMath.tx(key) - (LodMath.tx(ancestor.key) << up), ownZ = LodMath.tz(key) - (LodMath.tz(ancestor.key) << up);
        for (int z = 0; z < LodMath.TILE_COLS; z++) {
            int az = ((ownZ * LodMath.TILE_COLS) + z) >> up;
            for (int x = 0; x < LodMath.TILE_COLS; x++) {
                int ax = ((ownX * LodMath.TILE_COLS) + x) >> up;
                int from = LodMath.cell(ax, az);
                if (ancestor.height[from] == LodMath.NO_DATA || (ancestor.top[from] & F_ESTIMATED) == 0) continue;
                int to = LodMath.cell(x, z);
                height[to] = ancestor.height[from];
                top[to] = ancestor.top[from] | F_COARSE;
                side[to] = ancestor.side[from];
            }
        }
    }
}
