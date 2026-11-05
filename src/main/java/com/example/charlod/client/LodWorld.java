package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.example.charlod.core.ChunkColumns;
import com.example.charlod.core.ColumnSampler;
import com.example.charlod.core.GreedyMesher;
import com.example.charlod.core.LodMath;
import com.example.charlod.core.Selector;
import com.example.charlod.core.TileData;
import com.example.charlod.core.TileStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

// Everything for one dimension of one world: its tiles, its low priority worker threads, where the data comes from.
public final class LodWorld {
    final ClientLevel level;
    final Path dir;
    final ColorTable colors;
    final GreedyMesher.Shade shade;
    final int ceilingTop;
    public final McaScanner scanner;

    final Map<Long, Tile> tiles = new ConcurrentHashMap<>();
    public final ConcurrentLinkedQueue<Tile> uploads = new ConcurrentLinkedQueue<>();
    private final List<Tile> pending = new ArrayList<>();
    private final Map<Long, LevelChunk> seen = new ConcurrentHashMap<>();
    private final List<Thread> workers = new ArrayList<>();

    volatile double camX, camY, camZ;
    volatile boolean selectionDirty = true;
    private volatile boolean closing;
    private volatile TileStore store;
    private volatile long lastFlush;
    volatile long seenChunks;
    volatile long meshNanos, meshCount;
    private long lastErrorLog;

    private static volatile Thread lastCloser;

    LodWorld(ClientLevel level, Path dir, Path regionDir) {
        this.level = level;
        this.dir = dir;
        this.colors = new ColorTable(level);
        this.shade = level.effects().constantAmbientLight() ? GreedyMesher.Shade.CONSTANT : GreedyMesher.Shade.NORMAL;
        this.ceilingTop = level.dimensionType().hasCeiling() ? level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1 : Integer.MAX_VALUE;
        this.scanner = regionDir != null ? new McaScanner(this, regionDir, dir.resolve("scan.idx")) : null;
        int count = Math.max(1, Math.min(4, FarSettings.threads));
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(this::run, "Far View worker " + (i + 1));
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            workers.add(t);
            t.start();
        }
    }

    TileStore store() {
        return store;
    }

    // ---- the render thread asks

    int state(long key) {
        Tile t = tiles.get(key);
        return t == null ? Selector.UNKNOWN : t.state;
    }

    void want(long key, double priority) {
        Tile t = tiles.computeIfAbsent(key, Tile::new);
        t.priority = priority;
        t.lastWanted = System.currentTimeMillis();
        if (t.state == Selector.UNKNOWN) {
            t.state = Selector.LOADING;
            enqueue(t);
        }
    }

    void seen(LevelChunk chunk) {
        if (closing) return;
        seen.put(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z), chunk);
    }

    public int queuedTiles() {
        synchronized (pending) {
            return pending.size();
        }
    }

    int seenQueue() {
        return seen.size();
    }

    // ---- the workers

    private void enqueue(Tile t) {
        synchronized (pending) {
            if (t.queued) return;
            t.queued = true;
            pending.add(t);
        }
    }

    private Tile nextTile() {
        synchronized (pending) {
            int best = -1;
            double bestPriority = Double.MAX_VALUE;
            for (int i = 0; i < pending.size(); i++) {
                double p = pending.get(i).priority;
                if (p < bestPriority) {
                    bestPriority = p;
                    best = i;
                }
            }
            if (best < 0) return null;
            Tile t = pending.get(best);
            pending.set(best, pending.get(pending.size() - 1));
            pending.remove(pending.size() - 1);
            t.queued = false;
            return t;
        }
    }

    private void changed(long key) {
        selectionDirty = true;
        Tile t = tiles.get(key);
        if (t == null) return;
        int s = t.state;
        if (s == Selector.EMPTY) t.state = Selector.LOADING;
        if (s != Selector.UNKNOWN) enqueue(t);
    }

    void flushNow() {
        try {
            store.flush();
            lastFlush = System.currentTimeMillis();
        } catch (IOException e) {
            logError("couldn't save tiles", e);
        }
    }

    private void run() {
        try {
            Thread before = lastCloser;
            if (before != null) before.join(15_000);
            synchronized (this) {
                if (store == null) store = new TileStore(dir, FarSettings.maxCachedTiles(), this::changed);
            }
        } catch (InterruptedException e) {
            return;
        } catch (Throwable t) {
            CharLod.LOG.error("[Far View] couldn't open its data folder {}", dir, t);
            FarClient.problem("couldn't open its data folder");
            return;
        }
        GreedyMesher mesher = new GreedyMesher();
        GreedyMesher.Mesh mesh = new GreedyMesher.Mesh();
        ChunkColumns columns = new ChunkColumns();
        LevelChunkSource source = new LevelChunkSource(colors.biomes());
        ColorTable.View table = colors.new View();
        long debt = 0;
        while (!closing) {
            try {
                Tile t = nextTile();
                if (t != null) {
                    long start = System.nanoTime();
                    makeTile(t, mesher, mesh);
                    long took = System.nanoTime() - start;
                    meshNanos += took;
                    meshCount++;
                    continue;
                }
                if (ingestSeen(source, table, columns)) continue;
                if (store.hasDirty() && System.currentTimeMillis() - lastFlush > 4000) {
                    flushNow();
                    continue;
                }
                if (scanner != null && FarSettings.useGeneratedChunks && FarSettings.enabled) {
                    long start = System.nanoTime();
                    if (scanner.step(table)) {
                        // Stay well under half of one CPU core, however many workers there are.
                        debt += System.nanoTime() - start;
                        if (debt > 2_000_000L) {
                            Thread.sleep(debt / 1_000_000L);
                            debt = 0;
                        }
                        continue;
                    }
                }
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                logError("worker trouble", t);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void makeTile(Tile t, GreedyMesher mesher, GreedyMesher.Mesh mesh) throws IOException {
        if (System.currentTimeMillis() - t.lastWanted > 8000 && t.vao == 0) {
            t.state = Selector.UNKNOWN; // walked away from it before its turn came
            return;
        }
        TileData d = store.read(t.key);
        if (d == null || d.isEmpty()) {
            t.state = Selector.EMPTY;
            selectionDirty = true;
            return;
        }
        mesher.build(d, shade, mesh);
        if (mesh.quads == 0) {
            t.state = Selector.EMPTY;
            selectionDirty = true;
            return;
        }
        t.pending = new Tile.Pending(Arrays.copyOf(mesh.bytes, mesh.byteCount()), mesh.vertices, mesh.minY, mesh.maxY);
        uploads.add(t);
    }

    private boolean ingestSeen(LevelChunkSource source, ColorTable.View table, ChunkColumns columns) throws IOException {
        if (seen.isEmpty()) return false;
        Iterator<Map.Entry<Long, LevelChunk>> it = seen.entrySet().iterator();
        if (!it.hasNext()) return false;
        Map.Entry<Long, LevelChunk> e = it.next();
        it.remove();
        LevelChunk chunk = e.getValue();
        source.set(chunk);
        ColumnSampler.sample(source, table, ceilingTop, columns);
        store.putChunk(chunk.getPos().x, chunk.getPos().z, columns);
        seenChunks++;
        return true;
    }

    private void logError(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 10_000) {
            lastErrorLog = now;
            CharLod.LOG.warn("[Far View] {}", what, t);
        }
    }

    private volatile Runnable afterClose;

    // Runs once the data files are shut, before the next world opens them. Call it right after close().
    void afterClose(Runnable what) {
        afterClose = what;
    }

    // Lets go of the data on a side thread, so leaving a world doesn't hang on disk writes. The render thread deletes the tiles' graphics memory itself.
    void close() {
        closing = true;
        seen.clear();
        synchronized (pending) {
            pending.clear();
        }
        List<Thread> ws = workers;
        Thread previous = lastCloser;
        Thread closer = new Thread(() -> {
            try {
                if (previous != null) previous.join(15_000);
                for (Thread w : ws) {
                    w.interrupt();
                    w.join(5_000);
                }
                if (scanner != null) scanner.close();
                TileStore s = store;
                if (s != null) s.close();
                Thread.sleep(0);
                Runnable then = afterClose;
                if (then != null) then.run();
            } catch (Throwable t) {
                CharLod.LOG.warn("[Far View] trouble closing {}", dir, t);
            }
        }, "Far View closing");
        closer.setDaemon(false);
        lastCloser = closer;
        closer.start();
    }

    long seenChunksCount() {
        return seenChunks;
    }

    // Something the tile choice depends on has changed.
    public void touch() {
        selectionDirty = true;
    }

    void setCacheLimit() {
        TileStore s = store;
        if (s != null) s.setMaxCached(FarSettings.maxCachedTiles());
    }

    static String describe(long key) {
        return "L" + LodMath.level(key) + " " + LodMath.tx(key) + "," + LodMath.tz(key);
    }
}
