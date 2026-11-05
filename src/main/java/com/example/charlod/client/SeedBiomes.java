package com.example.charlod.client;

import com.example.charlod.core.ColumnSampler;
import com.example.charlod.core.BlockTable;
import com.example.charlod.core.TileData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraftforge.common.Tags;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// What a biome usually looks like from far away, worked out once per biome. Trees come from the biome's own feature list (so modded biomes get their own),
// the ground from the biome's name and tags.
final class SeedBiomes {
    private SeedBiomes() {}

    private static int id(Block b) {
        return Block.getId(b.defaultBlockState());
    }

    static final class Info {
        int id;
        int surface = id(Blocks.GRASS_BLOCK);
        int steep = -1;
        int high = -1, highFrom = Integer.MAX_VALUE;
        int seaBed = id(Blocks.GRAVEL);
        boolean frozenSea;
        boolean bands; // badlands layers
        float baseTemperature;
        boolean alwaysSnow, neverSnow;
        float trees;
        int leaf = id(Blocks.OAK_LEAVES);
        int treeHeight = 7;
        float treeRadius = 2.6f;

        private static final int[] BAND_BLOCKS = {id(Blocks.TERRACOTTA), id(Blocks.ORANGE_TERRACOTTA), id(Blocks.TERRACOTTA), id(Blocks.YELLOW_TERRACOTTA), id(Blocks.BROWN_TERRACOTTA), id(Blocks.RED_TERRACOTTA), id(Blocks.WHITE_TERRACOTTA), id(Blocks.TERRACOTTA), id(Blocks.LIGHT_GRAY_TERRACOTTA)};
        private static final int STONE_ID = id(Blocks.STONE);

        int surfaceAt(int y, float slope) {
            if (y >= highFrom && high >= 0) return high;
            if (steep >= 0 && slope > 1.2f) return steep;
            if (bands && y > 72) return BAND_BLOCKS[Math.floorMod((y - 72) / 3, BAND_BLOCKS.length)];
            return surface;
        }

        boolean snowAt(int y) {
            if (neverSnow) return false;
            if (alwaysSnow) return true;
            float t = baseTemperature;
            if (y > 80) t -= (y - 80) * 0.05f / 30f;
            return t < 0.15f;
        }

        // The steeper it is, the more of a cliff's side is bare stone rather than dirt.
        int cliffSide(int side, float slope, BlockTable table) {
            if (slope <= 0.6f) return side;
            float t = Math.min(1f, (slope - 0.6f) / 1.2f);
            return ColumnSampler.mix(side & TileData.RGB, table.top(STONE_ID, id) & TileData.RGB, t * 0.85f);
        }
    }

    static final class Table {
        private final ColorTable colors;
        private final long seed;
        private final Map<Holder<Biome>, Info> known = new ConcurrentHashMap<>();

        Table(ColorTable colors, long seed) {
            this.colors = colors;
            this.seed = seed;
        }

        Info of(Holder<Biome> biome) {
            Info got = known.get(biome);
            if (got != null) return got;
            Info made = make(biome);
            known.put(biome, made);
            return made;
        }

        private Info make(Holder<Biome> biome) {
            Info info = new Info();
            String name = biome.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
            String path = name.substring(name.indexOf(':') + 1);
            info.id = colors.biome(name);
            Biome b = biome.value();
            info.baseTemperature = b.getBaseTemperature();
            ground(biome, path, info);
            try {
                trees(b, info);
            } catch (Throwable ignored) {
                // A strange modded feature: no trees here then.
            }
            return info;
        }

        private static void ground(Holder<Biome> b, String path, Info i) {
            if (b.is(BiomeTags.IS_NETHER)) {
                i.neverSnow = true;
                i.surface = path.contains("soul_sand") ? id(Blocks.SOUL_SAND)
                        : path.contains("crimson") ? id(Blocks.CRIMSON_NYLIUM)
                        : path.contains("warped") ? id(Blocks.WARPED_NYLIUM)
                        : path.contains("basalt") ? id(Blocks.BASALT) : id(Blocks.NETHERRACK);
                return;
            }
            if (b.is(BiomeTags.IS_END)) {
                i.neverSnow = true;
                i.surface = id(Blocks.END_STONE);
                return;
            }
            // what lies under water
            if (path.contains("warm_ocean") || path.contains("lukewarm")) i.seaBed = id(Blocks.SAND);
            else if (b.is(BiomeTags.IS_RIVER)) i.seaBed = id(Blocks.DIRT);
            if (path.contains("frozen")) i.frozenSea = b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER);

            if (path.contains("stony_peaks") || path.contains("stony_shore")) {
                i.surface = id(Blocks.STONE);
                i.neverSnow = path.contains("stony_shore");
            } else if (path.contains("jagged_peaks") || path.contains("frozen_peaks") || path.contains("snowy_slopes") || path.contains("ice_spikes")) {
                i.surface = id(Blocks.SNOW_BLOCK);
                i.steep = path.contains("frozen_peaks") ? id(Blocks.PACKED_ICE) : id(Blocks.STONE);
            } else if (path.contains("gravelly")) {
                i.surface = id(Blocks.GRAVEL);
                i.steep = id(Blocks.STONE);
            } else if (path.contains("windswept")) {
                i.steep = id(Blocks.STONE);
            } else if (path.contains("mushroom")) {
                i.surface = id(Blocks.MYCELIUM);
            } else if (path.contains("mangrove")) {
                i.surface = id(Blocks.MUD);
            } else if (b.is(BiomeTags.IS_BADLANDS)) {
                i.surface = id(Blocks.RED_SAND);
                i.bands = true;
                i.neverSnow = true;
            } else if (b.is(BiomeTags.IS_BEACH) || path.contains("desert") || b.is(Tags.Biomes.IS_SANDY)) {
                i.surface = id(Blocks.SAND);
            } else if (path.contains("old_growth") && path.contains("taiga")) {
                i.surface = id(Blocks.PODZOL);
            } else if (b.is(Tags.Biomes.IS_PEAK)) {
                i.surface = id(Blocks.STONE);
            }
            if (b.is(Tags.Biomes.IS_SLOPE) || b.is(Tags.Biomes.IS_MOUNTAIN)) {
                if (i.steep < 0) i.steep = id(Blocks.STONE);
            }
            if (b.is(Tags.Biomes.IS_SNOWY) && !path.contains("beach")) i.alwaysSnow = true;
        }

        // How many trees a chunk gets, which leaves and how tall, from the biome's own list of features.
        private void trees(Biome b, Info info) throws IllegalAccessException {
            RandomSource random = RandomSource.create(seed ^ info.id);
            float total = 0;
            float weightOfBest = 0;
            for (HolderSet<PlacedFeature> step : b.getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : step) {
                    PlacedFeature placed = holder.value();
                    TreeConfiguration tree = null;
                    int kinds = 0;
                    for (ConfiguredFeature<?, ?> f : (Iterable<ConfiguredFeature<?, ?>>) placed.getFeatures()::iterator) {
                        if (f.config() instanceof TreeConfiguration t) {
                            if (tree == null) tree = t;
                            kinds++;
                        }
                    }
                    if (tree == null) continue;
                    float count = meanCount(placed, random);
                    total += count;
                    if (count > weightOfBest) {
                        weightOfBest = count;
                        BlockState leaf = tree.foliageProvider.getState(random, BlockPos.ZERO);
                        info.leaf = Block.getId(leaf);
                        int sum = 0;
                        for (int k = 0; k < 8; k++) sum += tree.trunkPlacer.getTreeHeight(random);
                        info.treeHeight = Math.max(4, sum / 8 + 1);
                    }
                }
            }
            info.trees = total;
        }

        private static float meanCount(PlacedFeature placed, RandomSource random) throws IllegalAccessException {
            float mean = 1f;
            for (PlacementModifier m : placed.placement()) {
                for (Field f : m.getClass().getDeclaredFields()) {
                    if (IntProvider.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        IntProvider p = (IntProvider) f.get(m);
                        if (p == null) continue;
                        int sum = 0;
                        for (int k = 0; k < 64; k++) sum += p.sample(random);
                        mean *= sum / 64f;
                    } else if (m.getClass().getSimpleName().toLowerCase().contains("rarity") && f.getType() == int.class) {
                        f.setAccessible(true);
                        int chance = f.getInt(m);
                        if (chance > 0) mean /= chance;
                    }
                }
            }
            return mean;
        }
    }
}
