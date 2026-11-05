package com.example.charlod.core;

// For every column of a chunk: find the surface (find), work out its colour (colour). A server only does the first half and sends what it found:
// colours come from textures, which only your PC has. Water is baked in: its colour already holds the sea bed showing through.
public final class ColumnSampler {
    private static final float WATER_ALPHA = 0.72f;
    private static final float PLANT_MIX = 0.3f;
    // Below a roof (the Nether) a surface only counts under this many blocks of open air, so small pockets in the roof don't show.
    private static final int ROOF_AIR_RUN = 4;
    private static final int MAX_WATER_LOOK = 24;

    // What one column has at its surface.
    public static final class Hit {
        public boolean found;
        public int y, block, over = -1, depth, bed = -1, biome, bedBiome;

        public void clear() {
            found = false;
            over = -1;
            depth = 0;
            bed = -1;
        }
    }

    private ColumnSampler() {}

    public static int startY(ColumnSource src, int ceilingTop) {
        int topSection = src.maxSection();
        int low = src.minSection();
        while (topSection >= low && src.sectionEmpty(topSection)) topSection--;
        if (topSection < low) return Integer.MIN_VALUE;
        int y = (topSection << 4) + 15;
        return ceilingTop != Integer.MAX_VALUE ? Math.min(y, ceilingTop) : y;
    }

    public static void find(ColumnSource src, BlockFlags table, int x, int z, int startY, int ceilingTop, Hit h) {
        h.clear();
        int minY = src.minSection() << 4;
        int y = startY;
        if (ceilingTop != Integer.MAX_VALUE) {
            int run = 0;
            boolean under = false;
            for (; y >= minY; y--) {
                if ((table.flags(src.block(x, y, z)) & BlockTable.AIR) != 0) {
                    if (++run >= ROOF_AIR_RUN) {
                        under = true;
                        break;
                    }
                } else {
                    run = 0;
                }
            }
            if (!under) return;
        }
        int plant = -1, snow = -1;
        for (; y >= minY; y--) {
            int block = src.block(x, y, z);
            int flags = table.flags(block);
            if ((flags & BlockTable.AIR) != 0) continue;
            if ((flags & BlockTable.SNOW_LAYER) != 0) {
                if (snow < 0) snow = block;
                continue;
            }
            if ((flags & BlockTable.THIN) != 0) {
                if (plant < 0) plant = block;
                continue;
            }
            h.found = true;
            h.y = y;
            h.block = block;
            h.over = snow >= 0 ? snow : plant;
            h.biome = src.biome(x, y, z);
            if ((flags & BlockTable.WATER) != 0) {
                int below = y - 1;
                for (; below >= minY && y - below < MAX_WATER_LOOK; below--) {
                    int b = src.block(x, below, z);
                    int f = table.flags(b);
                    if ((f & (BlockTable.WATER | BlockTable.AIR)) != 0) continue;
                    h.bed = b;
                    h.bedBiome = src.biome(x, below, z);
                    break;
                }
                h.depth = y - below;
            }
            return;
        }
    }

    public static void colour(Hit h, BlockTable table, int i, ChunkColumns out) {
        // (a plain copy of the rule for a single column is all a seed-made tile needs too)
        if (!h.found) {
            out.height[i] = LodMath.NO_DATA;
            return;
        }
        out.height[i] = (short) (h.y + 1);
        int flags = table.flags(h.block);
        if ((flags & BlockTable.WATER) != 0) {
            int water = table.top(h.block, h.biome) & TileData.RGB;
            int bed = h.bed >= 0 ? table.top(h.bed, h.bedBiome) & TileData.RGB : 0x303030;
            out.top[i] = waterTop(water, bed, h.depth) | TileData.F_WATER;
            out.side[i] = water;
            return;
        }
        int top = table.top(h.block, h.biome) & TileData.RGB;
        int side = table.side(h.block, h.biome) & TileData.RGB;
        int tag = 0;
        if ((flags & BlockTable.FOLIAGE) != 0) tag |= TileData.F_FOLIAGE;
        if ((flags & BlockTable.GLOW) != 0) tag |= TileData.F_GLOW;
        if (h.over >= 0) {
            int overFlags = table.flags(h.over);
            int overTop = table.top(h.over, h.biome) & TileData.RGB;
            top = (overFlags & BlockTable.SNOW_LAYER) != 0 ? overTop : mix(top, overTop, PLANT_MIX);
        }
        out.top[i] = top | tag;
        out.side[i] = side;
    }

    // ceilingTop = the highest block y under the roof in a dimension with one, or Integer.MAX_VALUE for open sky.
    public static void sample(ColumnSource src, BlockTable table, int ceilingTop, ChunkColumns out, Hit hit) {
        out.clear();
        int start = startY(src, ceilingTop);
        if (start == Integer.MIN_VALUE) return;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                find(src, table, x, z, start, ceilingTop, hit);
                colour(hit, table, z * 16 + x, out);
            }
        }
    }

    public static void sample(ColumnSource src, BlockTable table, int ceilingTop, ChunkColumns out) {
        sample(src, table, ceilingTop, out, new Hit());
    }

    // The sea bed lit by sky light that fades one level per block of water, seen through the water's own colour.
    public static int waterTop(int water, int bed, int depth) {
        int light = Math.max(0, 15 - depth);
        float l = light / 15f;
        float lit = 0.06f + 0.94f * (l / (4f - 3f * l));
        float bedPart = lit * (1f - WATER_ALPHA);
        int r = (int) (((bed >> 16) & 255) * bedPart + ((water >> 16) & 255) * WATER_ALPHA);
        int g = (int) (((bed >> 8) & 255) * bedPart + ((water >> 8) & 255) * WATER_ALPHA);
        int b = (int) ((bed & 255) * bedPart + (water & 255) * WATER_ALPHA);
        return (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255);
    }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
