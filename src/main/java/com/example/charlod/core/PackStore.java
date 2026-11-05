package com.example.charlod.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Stream;

// Tiles on disk: one file per level holding 16 x 16 tiles, so a whole world is a few hundred files, not tens of thousands.
// Header = position and length of each tile. A tile that grows past its slot moves to the end of the file.
// Not thread safe on its own: TileStore locks around it.
public final class PackStore {
    private static final int SIDE = 16, HEADER_ENTRIES = SIDE * SIDE, HEADER_BYTES = 16 + HEADER_ENTRIES * 8, SLOT = 256;
    private static final long MAGIC = 0x4348524C4F443031L; // CHRLOD01
    private static final int MAX_OPEN = 8;

    private final Path dir;
    private final LinkedHashMap<Long, Pack> open = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Long> missing = new HashSet<>();

    private static final class Pack {
        final RandomAccessFile file;
        final int[] offsets = new int[HEADER_ENTRIES], lengths = new int[HEADER_ENTRIES];
        long end;

        Pack(RandomAccessFile file) {
            this.file = file;
        }
    }

    public PackStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    private static long packKey(long tile) {
        return LodMath.key(LodMath.level(tile), LodMath.tx(tile) >> 4, LodMath.tz(tile) >> 4);
    }

    private static int slotOf(long tile) {
        return (LodMath.tz(tile) & 15) * SIDE + (LodMath.tx(tile) & 15);
    }

    private Path fileOf(long pack) {
        return dir.resolve("l" + LodMath.level(pack) + "_" + LodMath.tx(pack) + "_" + LodMath.tz(pack) + ".clp");
    }

    private Pack pack(long tile, boolean create) throws IOException {
        long id = packKey(tile);
        Pack p = open.get(id);
        if (p != null) return p;
        if (!create && missing.contains(id)) return null;
        Path path = fileOf(id);
        if (!create && !Files.exists(path)) {
            missing.add(id);
            return null;
        }
        missing.remove(id);
        RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw");
        p = new Pack(f);
        boolean good = false;
        if (f.length() >= HEADER_BYTES) {
            byte[] head = new byte[HEADER_BYTES];
            f.seek(0);
            f.readFully(head);
            ByteBuffer b = ByteBuffer.wrap(head);
            if (b.getLong() == MAGIC) {
                b.getLong();
                for (int i = 0; i < HEADER_ENTRIES; i++) {
                    p.offsets[i] = b.getInt();
                    p.lengths[i] = b.getInt();
                }
                good = true;
            }
        }
        if (!good) {
            f.setLength(0);
            f.seek(0);
            ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES);
            b.putLong(MAGIC);
            f.write(b.array());
        }
        p.end = Math.max(HEADER_BYTES, f.length());
        open.put(id, p);
        if (open.size() > MAX_OPEN) {
            var eldest = open.entrySet().iterator().next();
            eldest.getValue().file.close();
            open.remove(eldest.getKey());
        }
        return p;
    }

    public byte[] read(long tile) throws IOException {
        Pack p = pack(tile, false);
        if (p == null) return null;
        int slot = slotOf(tile);
        int len = p.lengths[slot];
        if (len <= 0) return null;
        byte[] data = new byte[len];
        p.file.seek(((long) p.offsets[slot]) * SLOT);
        p.file.readFully(data);
        return data;
    }

    public void write(long tile, byte[] data) throws IOException {
        Pack p = pack(tile, true);
        int slot = slotOf(tile);
        int oldSlots = (p.lengths[slot] + SLOT - 1) / SLOT;
        int newSlots = (data.length + SLOT - 1) / SLOT;
        long where;
        if (p.offsets[slot] > 0 && newSlots <= oldSlots) {
            where = p.offsets[slot];
        } else {
            where = (p.end + SLOT - 1) / SLOT;
            p.end = (where + newSlots) * SLOT;
        }
        p.file.seek(where * SLOT);
        p.file.write(data);
        p.offsets[slot] = (int) where;
        p.lengths[slot] = data.length;
        ByteBuffer b = ByteBuffer.allocate(8);
        b.putInt((int) where);
        b.putInt(data.length);
        p.file.seek(16L + slot * 8L);
        p.file.write(b.array());
    }

    public long bytesOnDisk() {
        long total = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) total += Files.size(f);
        } catch (IOException ignored) {
        }
        return total;
    }

    public void close() throws IOException {
        IOException first = null;
        for (Pack p : open.values()) {
            try {
                p.file.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        open.clear();
        if (first != null) throw first;
    }
}
