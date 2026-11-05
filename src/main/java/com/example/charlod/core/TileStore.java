package com.example.charlod.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

// All tile data, in memory and on disk. New chunks go into level 0; flush() writes changed tiles and passes the change up through the coarser levels.
// Seed-made tiles go straight into the level they were made for and stay there. Everything is locked: at most a couple of low priority workers use this.
public final class TileStore {
    public interface Listener {
        void changed(long key);
    }

    private final PackStore packs;
    private final TileCodec codec = new TileCodec();
    private final LinkedHashMap<Long, TileData> cache = new LinkedHashMap<>(256, 0.75f, true);
    private final List<Set<Long>> dirty = new ArrayList<>();
    private final Set<Long> noPassUp = new HashSet<>();
    private final Listener listener;
    private volatile int maxCached;
    private boolean closed;

    public TileStore(Path dir, int maxCached, Listener listener) throws IOException {
        this.packs = new PackStore(dir);
        this.maxCached = maxCached;
        this.listener = listener;
        for (int i = 0; i <= LodMath.TOP_LEVEL; i++) dirty.add(new HashSet<>());
    }

    public void setMaxCached(int max) {
        maxCached = max;
    }

    public synchronized int cachedTiles() {
        return cache.size();
    }

    // Null when there is nothing for this tile. Don't change what this returns.
    public synchronized TileData read(long key) throws IOException {
        if (closed) return null;
        TileData t = cache.get(key);
        if (t != null) return t;
        t = load(key);
        if (t != null) {
            cache.put(key, t);
            trim();
        }
        return t;
    }

    private TileData load(long key) throws IOException {
        byte[] bytes = packs.read(key);
        if (bytes == null) return null;
        try {
            return codec.decode(key, bytes, 0, bytes.length);
        } catch (IOException broken) {
            return null; // the next time this chunk is seen it gets rebuilt
        }
    }

    private TileData getOrCreate(long key) throws IOException {
        TileData t = cache.get(key);
        if (t == null) t = load(key);
        if (t == null) {
            t = new TileData(key);
            // Nearest rougher tile that exists.
            long up = key;
            while (LodMath.level(up) < LodMath.TOP_LEVEL) {
                up = LodMath.parent(up);
                TileData a = cache.get(up);
                if (a == null) a = load(up);
                if (a != null) {
                    t.inheritEstimates(a);
                    break;
                }
            }
        }
        cache.put(key, t);
        return t;
    }

    // One chunk's columns into level 0. Returns true if anything was new.
    public synchronized boolean putChunk(int chunkX, int chunkZ, ChunkColumns c) throws IOException {
        if (closed) return false;
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        long key = LodMath.key(0, LodMath.tileOfBlock(x0, 0), LodMath.tileOfBlock(z0, 0));
        TileData t = getOrCreate(key);
        int cx = (x0 & 127) >> 1, cz = (z0 & 127) >> 1;
        boolean changed = false;
        for (int j = 0; j < 8; j++) {
            for (int i = 0; i < 8; i++) {
                int a = j * 32 + i * 2;
                changed |= ColumnMerge.merge(c.height, c.top, c.side, a, a + 1, a + 16, a + 17, t.height, t.top, t.side, LodMath.cell(cx + i, cz + j));
            }
        }
        if (changed) {
            dirty.get(0).add(key);
            noPassUp.remove(key);
        }
        return changed;
    }

    // Seed-made columns for a tile. Only fills what is missing or was only a blocky copy: never touches real columns.
    public synchronized boolean putEstimate(TileData made) throws IOException {
        if (closed) return false;
        long key = made.key;
        TileData t = getOrCreate(key);
        boolean changed = false;
        for (int i = 0; i < LodMath.TILE_CELLS; i++) {
            if (made.height[i] == LodMath.NO_DATA) continue;
            boolean takes = t.height[i] == LodMath.NO_DATA || (t.top[i] & TileData.F_ESTIMATED) != 0;
            if (!takes) continue;
            if (t.height[i] == made.height[i] && t.top[i] == made.top[i] && t.side[i] == made.side[i]) continue;
            t.height[i] = made.height[i];
            t.top[i] = made.top[i];
            t.side[i] = made.side[i];
            changed = true;
        }
        Set<Long> set = dirty.get(LodMath.level(key));
        if (changed || packs.read(key) == null) {
            if (!set.contains(key)) noPassUp.add(key);
            set.add(key);
        }
        if (changed) listener.changed(key);
        return changed;
    }

    public synchronized boolean exists(long key) throws IOException {
        return !closed && (cache.containsKey(key) || packs.read(key) != null);
    }

    public synchronized boolean hasDirty() {
        for (Set<Long> s : dirty) if (!s.isEmpty()) return true;
        return false;
    }

    // Writes everything changed and works it up through the levels.
    public synchronized void flush() throws IOException {
        if (closed) return;
        for (int level = 0; level <= LodMath.TOP_LEVEL; level++) {
            Set<Long> now = dirty.get(level);
            if (now.isEmpty()) continue;
            List<Long> keys = new ArrayList<>(now);
            now.clear();
            for (long key : keys) {
                TileData t = cache.get(key);
                if (t == null) continue;
                packs.write(key, codec.encode(t));
                if (noPassUp.remove(key)) continue; // seed-made: stays in its own level, and whoever wants it has already heard
                if (level < LodMath.TOP_LEVEL) {
                    long up = LodMath.parent(key);
                    TileData parent = getOrCreate(up);
                    if (parent.fillQuarterFrom(t, LodMath.tx(key) & 1, LodMath.tz(key) & 1)) {
                        dirty.get(level + 1).add(up);
                        noPassUp.remove(up);
                    }
                }
                listener.changed(key);
            }
        }
        trim();
    }

    private void trim() {
        if (cache.size() <= maxCached) return;
        Iterator<Long> it = cache.keySet().iterator();
        int over = cache.size() - maxCached;
        while (it.hasNext() && over > 0) {
            long key = it.next();
            if (dirty.get(LodMath.level(key)).contains(key)) continue;
            it.remove();
            over--;
        }
    }

    public long bytesOnDisk() {
        return packs.bytesOnDisk();
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        flush();
        closed = true;
        codec.close();
        packs.close();
        cache.clear();
    }
}
