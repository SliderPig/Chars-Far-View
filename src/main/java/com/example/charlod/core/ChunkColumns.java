package com.example.charlod.core;

import java.util.Arrays;

// The 16 x 16 columns read from one chunk, before they go into a tile.
public final class ChunkColumns {
    public final short[] height = new short[256];
    public final int[] top = new int[256];
    public final int[] side = new int[256];

    public void clear() {
        Arrays.fill(height, LodMath.NO_DATA);
    }
}
