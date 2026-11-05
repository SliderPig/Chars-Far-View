package com.example.charlod.client;

import com.example.charlod.core.ColumnSource;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

// Reads a chunk the client has in memory. Runs on a worker: block lookups don't change anything, so a block changing at the same moment does no harm.
final class LevelChunkSource implements ColumnSource {
    private static final int AIR = Block.getId(Blocks.AIR.defaultBlockState());
    private final Registry<Biome> biomes;
    private LevelChunkSection[] sections;
    private int min;

    LevelChunkSource(Registry<Biome> biomes) {
        this.biomes = biomes;
    }

    void set(LevelChunk chunk) {
        sections = chunk.getSections();
        min = chunk.getMinSection();
    }

    @Override
    public int minSection() {
        return min;
    }

    @Override
    public int maxSection() {
        return min + sections.length - 1;
    }

    @Override
    public boolean sectionEmpty(int sectionY) {
        int i = sectionY - min;
        return i < 0 || i >= sections.length || sections[i] == null || sections[i].hasOnlyAir();
    }

    @Override
    public int block(int x, int y, int z) {
        int i = (y >> 4) - min;
        if (i < 0 || i >= sections.length || sections[i] == null) return AIR;
        return Block.getId(sections[i].getBlockState(x, y & 15, z));
    }

    @Override
    public int biome(int x, int y, int z) {
        int i = (y >> 4) - min;
        if (i < 0 || i >= sections.length || sections[i] == null) return 0;
        return biomes.getId(sections[i].getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2).value());
    }
}
