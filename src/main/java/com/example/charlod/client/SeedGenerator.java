package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.example.charlod.core.BlockTable;
import com.example.charlod.core.ChunkColumns;
import com.example.charlod.core.ColumnSampler;
import com.example.charlod.core.LodMath;
import com.example.charlod.core.SurfaceSearch;
import com.example.charlod.core.TileData;
import com.example.charlod.core.TreeScatter;
import com.example.charlod.core.TruthCheck;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// A brand new world can't wait for real chunks. This asks the world's own terrain maths what the ground would be, one point at a time,
// without making a single chunk or touching the world: heights by search, biomes from the climate, colours from the surface a biome usually has.
// Everything real replaces it, and a world where real chunks keep disagreeing (terrain plugins, odd generators) drops it for good.
public final class SeedGenerator {
    // Same threshold the game uses for its own quick idea of the surface.
    private static final double COARSE_SOLID = 0.390625;
    private static final int FINE_BELOW_LEVEL = 3;
    private static final int MOUNTAIN_Y = 100;
    private static final int WANT_EXPIRES_MILLIS = 4000;

    private final LodWorld world;
    private final BiomeSource biomes;
    private final Climate.Sampler sampler;
    private final DensityFunction fine, coarse;
    private final int minY, maxY, seaLevel;
    private final long seed;
    private final boolean roofed, islands;
    private final int roofY;
    private final int waterId, iceId, lavaId, snowId;
    private final SeedBiomes.Table biomeTable;
    private final Map<Long, Want> wants = new ConcurrentHashMap<>();
    private final Set<Long> finished = ConcurrentHashMap.newKeySet();
    private final Set<Long> making = ConcurrentHashMap.newKeySet();
    public final TruthCheck truth = new TruthCheck();
    private final AtomicLong truthAsks = new AtomicLong();

    public final AtomicLong tilesMade = new AtomicLong(), nanosMaking = new AtomicLong();
    private volatile boolean stopped;

    private record Want(double priority, long when, boolean refine) {}

    private SeedGenerator(LodWorld world, NoiseBasedChunkGenerator gen, RandomState state, long seed, boolean roofed, boolean islands, int roofY) {
        this.world = world;
        this.biomes = gen.getBiomeSource();
        this.sampler = state.sampler();
        this.fine = state.router().finalDensity();
        this.coarse = state.router().initialDensityWithoutJaggedness();
        NoiseSettings noise = gen.generatorSettings().value().noiseSettings();
        this.minY = noise.minY();
        this.maxY = noise.minY() + noise.height() - 1;
        this.seaLevel = gen.getSeaLevel();
        this.seed = seed;
        this.roofed = roofed;
        this.islands = islands;
        this.roofY = roofY;
        this.waterId = Block.getId(Blocks.WATER.defaultBlockState());
        this.iceId = Block.getId(Blocks.ICE.defaultBlockState());
        this.lavaId = Block.getId(Blocks.LAVA.defaultBlockState());
        this.snowId = Block.getId(Blocks.SNOW.defaultBlockState());
        this.biomeTable = new SeedBiomes.Table(world.colors, seed);
    }

    // Null (with the reason noted) when this world isn't one where the seed can be trusted. Never throws.
    public static SeedGenerator create(LodWorld world, MinecraftServer server, ResourceKey<Level> dimension, String[] why) {
        try {
            try {
                Class.forName("org.bukkit.Bukkit", false, SeedGenerator.class.getClassLoader());
                why[0] = "plugins can change terrain here";
                return null;
            } catch (ClassNotFoundException ignored) {
            }
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                why[0] = "no such level";
                return null;
            }
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            if (gen.getClass() != NoiseBasedChunkGenerator.class) {
                why[0] = "this world uses another kind of generator (" + gen.getClass().getSimpleName() + ")";
                return null;
            }
            NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) gen;
            RandomState state = level.getChunkSource().randomState();
            boolean roofed = level.dimensionType().hasCeiling();
            boolean islands = level.dimension() == Level.END;
            int roofY = level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1;
            SeedGenerator made = new SeedGenerator(world, noise, state, level.getSeed(), roofed, islands, roofY);
            // Ask it something once, so anything unusual fails here and not on a worker.
            made.estimateTop(0, 0);
            made.biomeAt(0, 64, 0);
            return made;
        } catch (Throwable t) {
            why[0] = "the terrain maths didn't answer (" + t.getClass().getSimpleName() + ")";
            CharLod.LOG.info("[Far View] fast generate from seed is off for this world: {}", why[0]);
            return null;
        }
    }

    public boolean stopped() {
        return stopped;
    }

    public void stop() {
        stopped = true;
        wants.clear();
    }

    public int queued() {
        return wants.size() + making.size();
    }

    public boolean idle() {
        return wants.isEmpty() && making.isEmpty();
    }

    // ---- what the render thread and tile maker ask for

    // True when this tile is on its way.
    boolean want(long key, double priority) {
        if (stopped || finished.contains(key)) return false;
        wants.put(key, new Want(priority, System.currentTimeMillis(), false));
        return true;
    }

    // This tile has gaps or only a blocky copy of a rougher tile: fill those in, once.
    void refine(long key, double priority) {
        if (stopped || finished.contains(key)) return;
        wants.putIfAbsent(key, new Want(priority, Long.MAX_VALUE, true));
    }

    // ---- the workers

    // Makes one tile if there is one worth making. coarseOnly: only the rough levels, which give the whole range quickly.
    boolean step(ColorTable.View table, boolean coarseOnly) throws IOException {
        if (stopped) return false;
        long now = System.currentTimeMillis();
        long bestKey = 0;
        double best = Double.MAX_VALUE;
        boolean found = false;
        for (Map.Entry<Long, Want> e : wants.entrySet()) {
            Want w = e.getValue();
            if (w.when != Long.MAX_VALUE && now - w.when > WANT_EXPIRES_MILLIS) {
                wants.remove(e.getKey());
                continue;
            }
            long key = e.getKey();
            if (coarseOnly && LodMath.level(key) < FINE_BELOW_LEVEL) continue;
            if (making.contains(key)) continue;
            if (w.priority < best) {
                best = w.priority;
                bestKey = key;
                found = true;
            }
        }
        if (!found) return false;
        Want w = wants.remove(bestKey);
        if (w == null || !making.add(bestKey)) return true;
        try {
            long start = System.nanoTime();
            TileData existing = world.store().read(bestKey);
            if (existing != null && !w.refine && !existing.hasCoarseCells() && !existing.isEmpty()) {
                finished.add(bestKey);
                return true;
            }
            if (existing != null && existing.isEmpty()) {
                finished.add(bestKey); // nothing but void there, already known
                return true;
            }
            TileData made = make(bestKey, existing, table);
            if (stopped) return true;
            world.store().putEstimate(made);
            finished.add(bestKey);
            tilesMade.incrementAndGet();
            nanosMaking.addAndGet(System.nanoTime() - start);
            return true;
        } finally {
            making.remove(bestKey);
        }
    }

    private final class Density implements SurfaceSearch.Solid {
        final DensityFunction f;
        final double above;

        Density(DensityFunction f, double above) {
            this.f = f;
            this.above = above;
        }

        @Override
        public boolean at(int x, int y, int z) {
            return f.compute(new DensityFunction.SinglePointContext(x, y, z)) > above;
        }
    }

    private final Density fineSolid(int unused) {
        return new Density(fine, 0.0);
    }

    // Top solid block of the ground, Integer.MIN_VALUE for none. precise = ask the full terrain maths; otherwise the cheap one that knows hills but not their sharp edges.
    private int ground(int x, int z, int hint, boolean precise, Density fineD, Density coarseD) {
        if (roofed) return SurfaceSearch.belowRoof(fineD, x, z, minY, roofY - 4, 4, 2);
        if (islands) return SurfaceSearch.fromAbove(fineD, x, z, Math.max(minY, 0), Math.min(maxY, 120), 6, 2);
        int first = SurfaceSearch.fromHint(coarseD, x, z, minY, maxY, hint, precise ? 4 : 2);
        if (first == Integer.MIN_VALUE) return first;
        if (!precise && first < MOUNTAIN_Y) return first + COARSE_LIFT;
        return SurfaceSearch.fromHint(fineD, x, z, minY, maxY, first, precise ? 1 : 3);
    }

    private static final int COARSE_LIFT = 0;

    // What the seed says this column's top face should be at (trees left out). Used for the truth check too, always precise.
    public int estimateTop(int x, int z) {
        Density f = new Density(fine, 0.0), c = new Density(coarse, COARSE_SOLID);
        int g = ground(x, z, seaLevel, true, f, c);
        if (g == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (!roofed && !islands && g < seaLevel) return seaLevel;
        return g + 1;
    }

    private Holder<Biome> biomeAt(int x, int y, int z) {
        return biomes.getNoiseBiome(x >> 2, y >> 2, z >> 2, sampler);
    }

    private TileData make(long key, TileData existing, ColorTable.View table) {
        int level = LodMath.level(key);
        int s = LodMath.blocksPerColumn(level), size = LodMath.tileBlocks(level);
        int x0 = LodMath.tx(key) * size, z0 = LodMath.tz(key) * size;
        int n = LodMath.TILE_COLS;
        TileData made = new TileData(key);
        int[] ground = new int[LodMath.TILE_CELLS];
        boolean precise = level < FINE_BELOW_LEVEL;
        Density f = new Density(fine, 0.0), c = new Density(coarse, COARSE_SOLID);
        int rowHint = seaLevel;
        for (int z = 0; z < n; z++) {
            int hint = rowHint;
            for (int x = 0; x < n; x++) {
                int g = ground(x0 + x * s + s / 2, z0 + z * s + s / 2, hint, precise, f, c);
                ground[z * n + x] = g;
                if (g != Integer.MIN_VALUE) {
                    hint = g;
                    if (x == 0) rowHint = g;
                }
            }
            if (stopped) return made;
        }
        ColumnSampler.Hit hit = new ColumnSampler.Hit();
        ColumnSampler.Hit leaf = new ColumnSampler.Hit();
        ChunkColumns out = new ChunkColumns();
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                if (existing != null && existing.height[i] != LodMath.NO_DATA && (existing.top[i] & TileData.F_ESTIMATED) == 0) continue; // real
                int g = ground[i];
                if (g == Integer.MIN_VALUE) continue;
                int wx = x0 + x * s + s / 2, wz = z0 + z * s + s / 2;
                Holder<Biome> biome = biomeAt(wx, g, wz);
                SeedBiomes.Info info = biomeTable.of(biome);
                float slope = slope(ground, x, z, n, g) / s;
                hit.found = true;
                hit.over = -1;
                hit.depth = 0;
                hit.bed = -1;
                hit.biome = info.id;
                hit.bedBiome = info.id;
                boolean underWater = !roofed && !islands && g < seaLevel;
                boolean lava = roofed && g < 31;
                if (underWater) {
                    hit.y = seaLevel - 1;
                    hit.block = info.frozenSea ? iceId : waterId;
                    hit.depth = seaLevel - 1 - g;
                    hit.bed = info.seaBed;
                } else if (lava) {
                    hit.y = 31;
                    hit.block = lavaId;
                } else {
                    hit.y = g;
                    hit.block = info.surfaceAt(g, slope);
                    if (info.snowAt(g)) hit.over = snowId;
                }
                ColumnSampler.colour(hit, table, 0, out);
                short h = out.height[0];
                int top = out.top[0];
                int side = out.side[0];
                if (!underWater && !lava) {
                    side = info.cliffSide(side, slope, table);
                    // Trees
                    if (info.trees > 0.001f) {
                        if (s <= 4) {
                            int lift = TreeScatter.canopyAt(seed, wx, wz, info.trees, info.treeHeight, info.treeRadius);
                            if (lift > 0) {
                                leaf.found = true;
                                leaf.y = g + lift;
                                leaf.block = info.leaf;
                                leaf.over = hit.over;
                                leaf.depth = 0;
                                leaf.bed = -1;
                                leaf.biome = info.id;
                                ColumnSampler.colour(leaf, table, 0, out);
                                h = out.height[0];
                                top = out.top[0];
                                side = out.side[0];
                            }
                        } else {
                            float cover = TreeScatter.cover(info.trees, info.treeRadius);
                            float shown = TreeScatter.coverJitter(seed, wx, wz, s, cover);
                            h = (short) (h + TreeScatter.coarseLift(seed, wx, wz, s, cover, info.treeHeight));
                            int leafTop = table.top(info.leaf, info.id) & TileData.RGB, leafSide = table.side(info.leaf, info.id) & TileData.RGB;
                            top = ColumnSampler.mix(top & TileData.RGB, leafTop, shown) | (top & 0xFF000000);
                            side = ColumnSampler.mix(side & TileData.RGB, leafSide, shown);
                            if (shown >= 0.5f) top |= TileData.F_FOLIAGE;
                        }
                    }
                }
                made.height[i] = h;
                made.top[i] = top | TileData.F_ESTIMATED;
                made.side[i] = side;
            }
        }
        return made;
    }

    private static float slope(int[] ground, int x, int z, int n, int g) {
        int most = 0;
        if (x > 0 && ground[z * n + x - 1] != Integer.MIN_VALUE) most = Math.max(most, Math.abs(ground[z * n + x - 1] - g));
        if (x < n - 1 && ground[z * n + x + 1] != Integer.MIN_VALUE) most = Math.max(most, Math.abs(ground[z * n + x + 1] - g));
        if (z > 0 && ground[(z - 1) * n + x] != Integer.MIN_VALUE) most = Math.max(most, Math.abs(ground[(z - 1) * n + x] - g));
        if (z < n - 1 && ground[(z + 1) * n + x] != Integer.MIN_VALUE) most = Math.max(most, Math.abs(ground[(z + 1) * n + x] - g));
        return most;
    }

    // ---- the truth check

    // Compares a real chunk with what the seed says. A few columns, skipping tree tops. Now and then rather than always once it has seen plenty.
    void compare(int chunkX, int chunkZ, ChunkColumns real) {
        if (stopped) return;
        long asks = truthAsks.incrementAndGet();
        if (truth.chunks() >= TruthCheck.WINDOW && asks % 8 != 0) return;
        if (truth.chunks() < TruthCheck.WINDOW && asks % 2 != 0 && truth.chunks() >= TruthCheck.MIN_CHUNKS) return;
        float[] errors = new float[4];
        int count = 0;
        for (int k = 0; k < 4; k++) {
            int lx = (k & 1) == 0 ? 3 : 12, lz = (k & 2) == 0 ? 3 : 12;
            int i = lz * 16 + lx;
            short h = real.height[i];
            int est = estimateTop(chunkX * 16 + lx, chunkZ * 16 + lz);
            if (h == LodMath.NO_DATA) {
                if (est != Integer.MIN_VALUE) errors[count++] = 64f; // the seed says ground, really nothing
                continue;
            }
            if ((real.top[i] & TileData.F_FOLIAGE) != 0) continue;
            errors[count++] = est == Integer.MIN_VALUE ? 64f : Math.abs(h - est);
        }
        if (count == 0) return;
        if (truth.add(TruthCheck.median(errors, count))) world.seedWasWrong();
    }

    // ---- for the real game checks

    public String speedReport(int columns) {
        Density f = new Density(fine, 0.0), c = new Density(coarse, COARSE_SOLID);
        java.util.Random r = new java.util.Random(seed);
        int[] xs = new int[columns], zs = new int[columns];
        for (int i = 0; i < columns; i++) {
            xs[i] = r.nextInt(8000) - 4000;
            zs[i] = r.nextInt(8000) - 4000;
        }
        long t0 = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < columns; i++) sink += f.at(xs[i], 80, zs[i]) ? 1 : 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < columns; i++) sink += c.at(xs[i], 80, zs[i]) ? 1 : 0;
        long t2 = System.nanoTime();
        for (int i = 0; i < columns; i++) sink += biomeAt(xs[i], 80, zs[i]).hashCode() & 1;
        long t3 = System.nanoTime();
        int[] cheap = new int[columns], full = new int[columns];
        for (int i = 0; i < columns; i++) cheap[i] = SurfaceSearch.fromHint(c, xs[i], zs[i], minY, maxY, seaLevel, 2);
        long t4 = System.nanoTime();
        for (int i = 0; i < columns; i++) full[i] = ground(xs[i], zs[i], seaLevel, true, f, c);
        long t5 = System.nanoTime();
        double sumErr = 0, sumAbove = 0;
        int high = 0, big = 0;
        for (int i = 0; i < columns; i++) {
            int e = full[i] - cheap[i];
            sumErr += Math.abs(e);
            sumAbove += e;
            if (Math.abs(e) > 8) big++;
            if (full[i] >= MOUNTAIN_Y) high++;
        }
        return String.format("full maths %.1f us, cheap maths %.1f us, biome %.1f us, cheap search %.1f us a column, full search %.1f us a column; cheap vs full height: mean diff %.1f (signed %.1f), over 8 off in %.0f%%, mountains %.0f%% (%d)",
                (t1 - t0) / 1e3 / columns, (t2 - t1) / 1e3 / columns, (t3 - t2) / 1e3 / columns, (t4 - t3) / 1e3 / columns, (t5 - t4) / 1e3 / columns,
                sumErr / columns, sumAbove / columns, 100.0 * big / columns, 100.0 * high / columns, sink & 1);
    }

    // Several threads asking at once must get the same answers as one asking alone.
    public String stressReport(int columns, int threads) throws InterruptedException {
        java.util.Random r = new java.util.Random(seed + 1);
        int[] xs = new int[columns], zs = new int[columns];
        for (int i = 0; i < columns; i++) {
            xs[i] = r.nextInt(16000) - 8000;
            zs[i] = r.nextInt(16000) - 8000;
        }
        int[] heights = new int[columns];
        int[] biomesAlone = new int[columns];
        for (int i = 0; i < columns; i++) {
            heights[i] = estimateTop(xs[i], zs[i]);
            biomesAlone[i] = System.identityHashCode(biomeAt(xs[i], 64, zs[i]).value());
        }
        AtomicLong wrong = new AtomicLong(), errors = new AtomicLong();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int shift = t * 37;
            ts[t] = new Thread(() -> {
                try {
                    for (int k = 0; k < columns; k++) {
                        int i = (k + shift) % columns;
                        if (estimateTop(xs[i], zs[i]) != heights[i]) wrong.incrementAndGet();
                        if (System.identityHashCode(biomeAt(xs[i], 64, zs[i]).value()) != biomesAlone[i]) wrong.incrementAndGet();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    CharLod.LOG.error("[Far View] stress thread failed", e);
                }
            }, "Far View stress " + t);
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        return columns + " columns on " + threads + " threads at once: " + wrong.get() + " different answers, " + errors.get() + " threads failed";
    }

    public int surfaceBlockFlags(BlockTable t, int id) {
        return t.flags(id);
    }
}
