package com.example.charlod.core;

// What the column sampler needs to know about a block. Blocks and biomes are plain numbers here, so all of this can be tested without the game.
public interface BlockTable {
    int AIR = 1;        // nothing to see: air, barriers, structure voids
    int THIN = 2;       // flowers, tall grass, torches: not a surface of their own, they only tint what they stand on
    int WATER = 4;      // water, seagrass, kelp, anything waterlogged
    int SNOW_LAYER = 8; // a skin of snow on top of the real surface
    int FOLIAGE = 16;   // leaves
    int GLOW = 32;      // lights itself up (lava, glowstone, ...): stays bright at night

    int flags(int block);

    int top(int block, int biome);

    int side(int block, int biome);
}
