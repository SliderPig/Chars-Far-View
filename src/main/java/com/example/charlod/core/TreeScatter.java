package com.example.charlod.core;

// Forests without trees: from a biome's trees per chunk, a seeded pattern says where a canopy would be. Not the real trees, just enough that a forest reads as a forest.
public final class TreeScatter {
    private TreeScatter() {}

    private static long mix(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFFFF) / 65536f;
    }

    // Fine levels: how far above the ground the canopy is at this block, 0 for none. Trees sit on a jittered grid sized to the density.
    public static int canopyAt(long seed, int x, int z, float treesPerChunk, int treeHeight, float radius) {
        if (treesPerChunk <= 0.001f) return 0;
        int cell = Math.max(4, (int) (16f / Math.sqrt(Math.max(treesPerChunk, 1f))));
        float chance = Math.min(1f, treesPerChunk * cell * cell / 256f);
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        int best = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long h = mix(seed, cx + dx, cz + dz);
                if (unit(h, 0) >= chance) continue;
                float tx = (cx + dx + unit(h, 16)) * cell, tz = (cz + dz + unit(h, 32)) * cell;
                float r = radius * (0.85f + 0.3f * unit(h, 48));
                float ox = x + 0.5f - tx, oz = z + 0.5f - tz;
                float d2 = ox * ox + oz * oz;
                if (d2 > r * r) continue;
                int top = Math.round(treeHeight * (0.8f + 0.35f * unit(h, 8)) - 2f * (float) Math.sqrt(d2) / r);
                if (top > best) best = top;
            }
        }
        return best;
    }

    // Coarse levels: what share of a column of this width is canopy.
    public static float cover(float treesPerChunk, float radius) {
        if (treesPerChunk <= 0f) return 0f;
        double area = Math.PI * radius * radius;
        return (float) (1.0 - Math.exp(-treesPerChunk * area / 256.0));
    }

    // Coarse levels: how much a column of trees lifts it, with some seeded unevenness so a forest has a rough top.
    public static int coarseLift(long seed, int x, int z, int blocksPerColumn, float cover, int treeHeight) {
        if (cover <= 0.01f) return 0;
        long h = mix(seed, Math.floorDiv(x, blocksPerColumn), Math.floorDiv(z, blocksPerColumn));
        float rough = unit(h, 0) * 0.7f + 0.3f;
        // The chance of at least one tree in a wide column is high even in thin woods: what the mean-plus-a-bit rule makes of real ones.
        float reach = (float) Math.sqrt(cover);
        return Math.round(treeHeight * reach * rough * 0.75f);
    }

    // Coarse levels: a small seeded change in how much of the canopy colour shows.
    public static float coverJitter(long seed, int x, int z, int blocksPerColumn, float cover) {
        long h = mix(seed ^ 0x55AA55AAL, Math.floorDiv(x, blocksPerColumn), Math.floorDiv(z, blocksPerColumn));
        return Math.max(0f, Math.min(1f, cover * (0.75f + 0.5f * unit(h, 16))));
    }
}
