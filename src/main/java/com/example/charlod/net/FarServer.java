package com.example.charlod.net;

import com.example.charlod.CharLod;
import com.example.charlod.core.BlockFlags;
import com.example.charlod.core.ColumnSampler;
import com.example.charlod.core.McaChunk;
import com.example.charlod.core.McaFile;
import com.example.charlod.core.SurfacePacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;

// The optional server part. Off unless config/charlod-server.properties says shareChunkSurfaces=true. One low priority thread reads the region files,
// never loads a chunk, and sends surfaces (never anything below them) to players who ask, at a limited rate, one region per player at a time.
public final class FarServer {
    private static volatile boolean sharing;
    private static volatile int kilobytesPerSecond = 96;
    private static volatile int maxDistance = 16384;
    private static volatile MinecraftServer server;
    private static volatile Thread worker;
    private static final LinkedBlockingDeque<RegionJob> JOBS = new LinkedBlockingDeque<>();
    private static final Set<UUID> BUSY = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private FarServer() {}

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(FarServer::onStarting);
        MinecraftForge.EVENT_BUS.addListener(FarServer::onStopping);
    }

    private static void onStarting(ServerStartingEvent e) {
        server = e.getServer();
        load();
        JOBS.clear();
        BUSY.clear();
        if (sharing) {
            Thread t = new Thread(FarServer::run, "Far View server");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            worker = t;
            t.start();
            CharLod.LOG.info("[Far View] sharing chunk surfaces with players who have the mod, at most {} KB/s", kilobytesPerSecond);
        }
    }

    private static void onStopping(ServerStoppingEvent e) {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        JOBS.clear();
        server = null;
    }

    private static void load() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("charlod-server.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ex) {
                CharLod.LOG.warn("[Far View] couldn't read {}: {}", file, ex.toString());
            }
        } else {
            p.setProperty("shareChunkSurfaces", "false");
            p.setProperty("maxKilobytesPerSecond", "96");
            p.setProperty("maxDistance", "16384");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Char's Far View: send the surface of generated chunks to players who have the mod");
            } catch (IOException ex) {
                CharLod.LOG.warn("[Far View] couldn't write {}: {}", file, ex.toString());
            }
        }
        sharing = Boolean.parseBoolean(p.getProperty("shareChunkSurfaces", "false").trim());
        try {
            kilobytesPerSecond = Math.max(8, Integer.parseInt(p.getProperty("maxKilobytesPerSecond", "96").trim()));
            maxDistance = Math.max(512, Integer.parseInt(p.getProperty("maxDistance", "16384").trim()));
        } catch (NumberFormatException ignored) {
        }
    }

    static void onHello(ServerPlayer p, FarNet.Hello m) {
        if (p == null) return;
        FarNet.send(p, new FarNet.Welcome(m.dim(), sharing));
    }

    private static Path regionDir(String dim) {
        MinecraftServer s = server;
        if (s == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(dim);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel level = s.getLevel(key);
        if (level == null) return null;
        return DimensionType.getStorageFolder(key, s.getWorldPath(LevelResource.ROOT)).resolve("region");
    }

    static void onList(ServerPlayer p, FarNet.ListAsk m) {
        if (p == null || !sharing) return;
        Path dir = regionDir(m.dim());
        if (dir == null || !Files.isDirectory(dir)) {
            FarNet.send(p, new FarNet.ListReply(m.dim(), new int[0], new int[0], new long[0]));
            return;
        }
        double reach = Math.min(m.radius(), maxDistance) + 724.0;
        record Found(int rx, int rz, long time, double d) {}
        List<Found> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String[] parts = f.getFileName().toString().split("\\.");
                if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) continue;
                try {
                    int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
                    double d = Math.hypot((rx + 0.5) * 512 - m.blockX(), (rz + 0.5) * 512 - m.blockZ());
                    if (d <= reach) found.add(new Found(rx, rz, Files.getLastModifiedTime(f).toMillis(), d));
                } catch (NumberFormatException | IOException ignored) {
                }
            }
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] couldn't list regions: {}", e.toString());
        }
        found.sort(Comparator.comparingDouble(Found::d));
        int n = Math.min(found.size(), 4096);
        int[] rx = new int[n], rz = new int[n];
        long[] time = new long[n];
        for (int i = 0; i < n; i++) {
            rx[i] = found.get(i).rx;
            rz[i] = found.get(i).rz;
            time[i] = found.get(i).time;
        }
        FarNet.send(p, new FarNet.ListReply(m.dim(), rx, rz, time));
    }

    static void onRegion(ServerPlayer p, FarNet.RegionAsk m) {
        if (p == null || !sharing) return;
        Path dir = regionDir(m.dim());
        if (dir == null || !BUSY.add(p.getUUID())) return;
        ResourceLocation id = ResourceLocation.tryParse(m.dim());
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        int ceiling = level != null && level.dimensionType().hasCeiling() ? level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1 : Integer.MAX_VALUE;
        JOBS.add(new RegionJob(p, m.dim(), m.rx(), m.rz(), dir.resolve("r." + m.rx() + "." + m.rz() + ".mca"), ceiling));
    }

    // Names and kinds of blocks for this thread only.
    private static final class Table implements McaChunk.Names, BlockFlags, SurfacePacket.Names {
        private final Map<String, Integer> blocks = new HashMap<>(), biomes = new HashMap<>();
        private final List<String> blockNames = new ArrayList<>(), biomeNames = new ArrayList<>();
        private int[] flags = new int[1024];

        @Override
        public int block(String name, String properties) {
            String key = properties.isEmpty() ? name : name + "[" + properties + "]";
            Integer got = blocks.get(key);
            if (got != null) return got;
            int id = blockNames.size();
            blockNames.add(key);
            blocks.put(key, id);
            if (id >= flags.length) flags = java.util.Arrays.copyOf(flags, flags.length * 2);
            flags[id] = BlockRules.flagsOf(resolve(name, properties));
            return id;
        }

        private static BlockState resolve(String name, String properties) {
            ResourceLocation rl = ResourceLocation.tryParse(name);
            if (rl == null || !ForgeRegistries.BLOCKS.containsKey(rl)) return Blocks.STONE.defaultBlockState();
            Block block = ForgeRegistries.BLOCKS.getValue(rl);
            if (block == null) return Blocks.STONE.defaultBlockState();
            BlockState state = block.defaultBlockState();
            if (!properties.isEmpty()) {
                for (String pair : properties.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    Property<?> p = block.getStateDefinition().getProperty(pair.substring(0, eq));
                    if (p != null) state = with(state, p, pair.substring(eq + 1));
                }
            }
            return state;
        }

        private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> p, String value) {
            return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
        }

        @Override
        public int biome(String name) {
            Integer got = biomes.get(name);
            if (got != null) return got;
            int id = biomeNames.size();
            biomeNames.add(name);
            biomes.put(name, id);
            return id;
        }

        @Override
        public int flags(int block) {
            return flags[block];
        }

        @Override
        public String blockName(int block) {
            return blockNames.get(block);
        }

        @Override
        public String biomeName(int biome) {
            return biome < biomeNames.size() ? biomeNames.get(biome) : "minecraft:plains";
        }
    }

    private static final class RegionJob {
        final ServerPlayer player;
        final String dim;
        final int rx, rz, ceiling;
        final Path file;
        McaFile mca;
        int index;
        long time;

        RegionJob(ServerPlayer player, String dim, int rx, int rz, Path file, int ceiling) {
            this.player = player;
            this.dim = dim;
            this.rx = rx;
            this.rz = rz;
            this.file = file;
            this.ceiling = ceiling;
        }

        void close() {
            if (mca != null) {
                try {
                    mca.close();
                } catch (IOException ignored) {
                }
            }
            mca = null;
        }
    }

    private static void run() {
        Table table = new Table();
        McaChunk chunk = new McaChunk();
        ColumnSampler.Hit hit = new ColumnSampler.Hit();
        Thread me = Thread.currentThread();
        while (worker == me) {
            RegionJob job;
            try {
                job = JOBS.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                boolean more = step(job, table, chunk, hit);
                if (more) {
                    JOBS.addLast(job); // players take turns
                } else {
                    job.close();
                    BUSY.remove(job.player.getUUID());
                }
            } catch (InterruptedException e) {
                job.close();
                return;
            } catch (Throwable t) {
                CharLod.LOG.warn("[Far View] trouble reading {}: {}", job.file, t.toString());
                job.close();
                BUSY.remove(job.player.getUUID());
                FarNet.send(job.player, new FarNet.RegionPart(job.dim, job.rx, job.rz, true, job.time, new byte[0]));
            }
        }
    }

    // Sends up to one packet's worth of a region, then rests so the stream stays under the rate limit.
    private static boolean step(RegionJob job, Table table, McaChunk chunk, ColumnSampler.Hit hit) throws IOException, InterruptedException {
        if (job.player.hasDisconnected()) return false;
        if (job.mca == null) {
            if (!Files.exists(job.file)) {
                FarNet.send(job.player, new FarNet.RegionPart(job.dim, job.rx, job.rz, true, 0L, new byte[0]));
                return false;
            }
            job.time = Files.getLastModifiedTime(job.file).toMillis();
            job.mca = new McaFile(job.file);
        }
        SurfacePacket.Writer writer = new SurfacePacket.Writer();
        long start = System.nanoTime();
        while (job.index < 1024 && writer.chunks() < 32) {
            int i = job.index++;
            if (!job.mca.present(i) || !job.mca.read(i)) continue;
            if (!chunk.parse(job.mca.data(), job.mca.dataLength(), table) || !chunk.surfaceDone || !chunk.hasSections()) continue;
            writer.beginChunk(chunk.chunkX, chunk.chunkZ);
            int startY = ColumnSampler.startY(chunk, job.ceiling);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (startY == Integer.MIN_VALUE) hit.found = false;
                    else ColumnSampler.find(chunk, table, x, z, startY, job.ceiling, hit);
                    writer.column(hit, table);
                }
            }
        }
        boolean last = job.index >= 1024;
        byte[] data = writer.chunks() == 0 ? new byte[0] : writer.finish();
        if (data.length > 0 || last) FarNet.send(job.player, new FarNet.RegionPart(job.dim, job.rx, job.rz, last, job.time, data));
        long worked = (System.nanoTime() - start) / 1_000_000L;
        long rest = Math.max(worked, data.length * 1000L / (kilobytesPerSecond * 1024L));
        if (rest > 0) Thread.sleep(rest);
        return !last;
    }

    public static boolean sharing() {
        return sharing;
    }

    static Set<String> describe() {
        return new HashSet<>(List.of("sharing=" + sharing));
    }
}
