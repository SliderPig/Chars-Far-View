package com.example.charlod.core;

// For every column of a chunk: find the surface, work out its colour. Water is baked in: its colour already holds the sea bed showing through.
public final class ColumnSampler {
    private static final float WATER_ALPHA = 0.72f;
    private static final float PLANT_MIX = 0.3f;
    // Below a roof (the Nether) a surface only counts under this many blocks of open air, so small pockets in the roof don't show.
    private static final int ROOF_AIR_RUN = 4;

    private ColumnSampler() {}

    // ceilingTop = the highest block y under the roof in a dimension with one, or Integer.MAX_VALUE for open sky.
    public static void sample(ColumnSource src, BlockTable table, int ceilingTop, ChunkColumns out) {
        int topSection = src.maxSection();
        int low = src.minSection();
        while (topSection >= low && src.sectionEmpty(topSection)) topSection--;
        out.clear();
        if (topSection < low) return;
        int minY = low << 4;
        int startY = (topSection << 4) + 15;
        if (ceilingTop != Integer.MAX_VALUE) startY = Math.min(startY, ceilingTop);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = startY;
                if (ceilingTop != Integer.MAX_VALUE) {
                    y = belowRoof(src, table, x, z, y, minY);
                    if (y < minY) continue;
                }
                sampleColumn(src, table, x, z, y, minY, out);
            }
        }
    }

    private static int belowRoof(ColumnSource src, BlockTable table, int x, int z, int y, int minY) {
        int run = 0;
        for (; y >= minY; y--) {
            if ((table.flags(src.block(x, y, z)) & BlockTable.AIR) != 0) {
                if (++run >= ROOF_AIR_RUN) return y;
            } else {
                run = 0;
            }
        }
        return y;
    }

    private static void sampleColumn(ColumnSource src, BlockTable table, int x, int z, int y, int minY, ChunkColumns out) {
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
            int biome = src.biome(x, y, z);
            int i = z * 16 + x;
            out.height[i] = (short) (y + 1);
            if ((flags & BlockTable.WATER) != 0) {
                water(src, table, x, z, y, minY, block, biome, out, i);
                return;
            }
            int top = table.top(block, biome) & TileData.RGB;
            int side = table.side(block, biome) & TileData.RGB;
            int tag = 0;
            if ((flags & BlockTable.FOLIAGE) != 0) tag |= TileData.F_FOLIAGE;
            if ((flags & BlockTable.GLOW) != 0) tag |= TileData.F_GLOW;
            if (snow >= 0) top = table.top(snow, biome) & TileData.RGB;
            else if (plant >= 0) top = mix(top, table.top(plant, biome) & TileData.RGB, PLANT_MIX);
            out.top[i] = top | tag;
            out.side[i] = side;
            return;
        }
    }

    private static void water(ColumnSource src, BlockTable table, int x, int z, int surface, int minY, int block, int biome, ChunkColumns out, int i) {
        int y = surface - 1;
        int bedColour = 0x303030;
        for (; y >= minY && surface - y < 24; y--) {
            int b = src.block(x, y, z);
            int f = table.flags(b);
            if ((f & BlockTable.WATER) != 0 || (f & BlockTable.AIR) != 0) continue;
            bedColour = table.top(b, src.biome(x, y, z)) & TileData.RGB;
            break;
        }
        int waterColour = table.top(block, biome) & TileData.RGB;
        int depth = surface - y;
        int top = waterTop(waterColour, bedColour, depth);
        out.top[i] = top | TileData.F_WATER;
        out.side[i] = waterColour;
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
