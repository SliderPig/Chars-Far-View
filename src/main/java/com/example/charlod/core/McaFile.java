package com.example.charlod.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

// Reads chunks straight out of a region file without asking the game to load them. Only ever opens the file for reading.
public final class McaFile implements Closeable {
    private final Path path;
    private final RandomAccessFile file;
    private final int[] locations = new int[1024];
    private final Inflater zlib = new Inflater(), gzip = new Inflater(true);
    private byte[] packed = new byte[64 * 1024];
    private byte[] data = new byte[512 * 1024];
    private int dataLength;

    public McaFile(Path path) throws IOException {
        this.path = path;
        file = new RandomAccessFile(path.toFile(), "r");
        byte[] head = new byte[4096];
        if (file.length() >= 4096) {
            file.readFully(head);
            for (int i = 0; i < 1024; i++) locations[i] = ((head[i * 4] & 255) << 24) | ((head[i * 4 + 1] & 255) << 16) | ((head[i * 4 + 2] & 255) << 8) | (head[i * 4 + 3] & 255);
        }
    }

    public boolean present(int index) {
        return locations[index] != 0;
    }

    public int presentCount() {
        int n = 0;
        for (int l : locations) if (l != 0) n++;
        return n;
    }

    public byte[] data() {
        return data;
    }

    public int dataLength() {
        return dataLength;
    }

    // Reads and unpacks one chunk's NBT into data(). False = not there, or something this code can't read (it just gets skipped).
    public boolean read(int index) throws IOException {
        int loc = locations[index];
        if (loc == 0) return false;
        long at = (long) (loc >>> 8) * 4096L;
        int sectors = loc & 255;
        if (at + 5 > file.length() || sectors == 0) return false;
        file.seek(at);
        int length = file.readInt();
        int type = file.readUnsignedByte();
        if ((type & 128) != 0) return readExternal(index, type & 127);
        if (length <= 1 || length > sectors * 4096) return false;
        int count = length - 1;
        if (packed.length < count) packed = new byte[count + count / 2];
        file.readFully(packed, 0, count);
        return unpack(type, packed, count);
    }

    private boolean readExternal(int index, int type) throws IOException {
        String name = path.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length < 3) return false;
        int cx = Integer.parseInt(parts[1]) * 32 + (index & 31), cz = Integer.parseInt(parts[2]) * 32 + (index >> 5);
        Path big = path.resolveSibling("c." + cx + "." + cz + ".mcc");
        if (!Files.exists(big)) return false;
        byte[] all = Files.readAllBytes(big);
        return unpack(type, all, all.length);
    }

    private boolean unpack(int type, byte[] in, int count) {
        try {
            switch (type) {
                case 3 -> {
                    if (data.length < count) data = new byte[count];
                    System.arraycopy(in, 0, data, 0, count);
                    dataLength = count;
                    return true;
                }
                case 2 -> {
                    zlib.reset();
                    zlib.setInput(in, 0, count);
                    return inflate(zlib);
                }
                case 1 -> {
                    int skip = gzipHeader(in, count);
                    if (skip < 0) return false;
                    gzip.reset();
                    gzip.setInput(in, skip, count - skip);
                    return inflate(gzip);
                }
                default -> {
                    return false;
                }
            }
        } catch (DataFormatException e) {
            return false;
        }
    }

    private boolean inflate(Inflater inflater) throws DataFormatException {
        int total = 0;
        while (!inflater.finished()) {
            if (total == data.length) data = Arrays.copyOf(data, data.length * 2);
            int got = inflater.inflate(data, total, data.length - total);
            if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
            total += got;
        }
        dataLength = total;
        return total > 0;
    }

    private static int gzipHeader(byte[] in, int count) {
        if (count < 18 || (in[0] & 255) != 0x1f || (in[1] & 255) != 0x8b) return -1;
        int flags = in[3] & 255;
        int p = 10;
        if ((flags & 4) != 0) p += 2 + (in[p] & 255) + ((in[p + 1] & 255) << 8);
        if ((flags & 8) != 0) while (p < count && in[p++] != 0) ;
        if ((flags & 16) != 0) while (p < count && in[p++] != 0) ;
        if ((flags & 2) != 0) p += 2;
        return p < count ? p : -1;
    }

    @Override
    public void close() throws IOException {
        zlib.end();
        gzip.end();
        file.close();
    }
}
