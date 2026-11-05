package com.example.charlod.core;

import java.util.Arrays;

// The columns of one tile. top = RGB with flags in the top byte, side = colour of its side faces.
public final class TileData {
    public static final int F_WATER = 1 << 24, F_FOLIAGE = 2 << 24, F_GLOW = 4 << 24;
    public static final int RGB = 0xFFFFFF;

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
}
