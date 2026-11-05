package com.example.charlod.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

// How a server sends what chunks have at their surface: block and biome names once per packet, then each column as small numbers, all deflated.
public final class SurfacePacket {
    public static final int COLUMNS = 256;

    private SurfacePacket() {}

    public static final class Writer {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(64 * 1024);
        private final DataOutputStream out = new DataOutputStream(body);
        private final Map<String, Integer> blocks = new HashMap<>(), biomes = new HashMap<>();
        private final List<String> blockList = new ArrayList<>(), biomeList = new ArrayList<>();
        private int chunks;

        private static int index(String name, Map<String, Integer> map, List<String> list) {
            Integer i = map.get(name);
            if (i != null) return i;
            int n = list.size();
            map.put(name, n);
            list.add(name);
            return n;
        }

        public void beginChunk(int chunkX, int chunkZ) throws IOException {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            chunks++;
        }

        public void column(ColumnSampler.Hit h, Names names) throws IOException {
            if (!h.found) {
                out.writeShort(LodMath.NO_DATA);
                return;
            }
            out.writeShort(h.y);
            out.writeShort(index(names.blockName(h.block), blocks, blockList));
            out.writeShort(h.over < 0 ? 0 : index(names.blockName(h.over), blocks, blockList) + 1);
            out.writeByte(index(names.biomeName(h.biome), biomes, biomeList));
            out.writeByte(Math.min(h.depth, 255));
            if (h.depth > 0) {
                out.writeShort(h.bed < 0 ? 0 : index(names.blockName(h.bed), blocks, blockList) + 1);
                if (h.bed >= 0) out.writeByte(index(names.biomeName(h.bedBiome), biomes, biomeList));
            }
        }

        public int chunks() {
            return chunks;
        }

        public int rawSize() {
            return body.size();
        }

        public byte[] finish() throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream(body.size() + 1024);
            DataOutputStream head = new DataOutputStream(all);
            head.writeInt(chunks);
            head.writeShort(blockList.size());
            for (String s : blockList) head.writeUTF(s);
            head.writeShort(biomeList.size());
            for (String s : biomeList) head.writeUTF(s);
            head.flush();
            body.writeTo(all);
            byte[] raw = all.toByteArray();
            Deflater d = new Deflater(6);
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream packed = new ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] buf = new byte[16 * 1024];
            while (!d.finished()) packed.write(buf, 0, d.deflate(buf));
            d.end();
            return packed.toByteArray();
        }
    }

    public interface Names {
        String blockName(int block);

        String biomeName(int biome);
    }

    // Called for every chunk that comes out. The Hit array is reused, so copy what you keep.
    public interface Sink {
        void chunk(int chunkX, int chunkZ, ColumnSampler.Hit[] columns) throws IOException;
    }

    public static void read(byte[] packed, McaChunk.Names names, Sink sink) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)), 32 * 1024))) {
            int chunks = in.readInt();
            int[] blocks = new int[in.readUnsignedShort()];
            for (int i = 0; i < blocks.length; i++) {
                String entry = in.readUTF();
                int cut = entry.indexOf('[');
                blocks[i] = cut < 0 ? names.block(entry, "") : names.block(entry.substring(0, cut), entry.substring(cut + 1, entry.length() - 1));
            }
            int[] biomes = new int[in.readUnsignedShort()];
            for (int i = 0; i < biomes.length; i++) biomes[i] = names.biome(in.readUTF());
            ColumnSampler.Hit[] hits = new ColumnSampler.Hit[COLUMNS];
            for (int i = 0; i < COLUMNS; i++) hits[i] = new ColumnSampler.Hit();
            for (int c = 0; c < chunks; c++) {
                int x = in.readInt(), z = in.readInt();
                for (int i = 0; i < COLUMNS; i++) {
                    ColumnSampler.Hit h = hits[i];
                    h.clear();
                    short y = in.readShort();
                    if (y == LodMath.NO_DATA) continue;
                    h.found = true;
                    h.y = y;
                    h.block = blocks[in.readUnsignedShort()];
                    int over = in.readUnsignedShort();
                    h.over = over == 0 ? -1 : blocks[over - 1];
                    h.biome = biomes[in.readUnsignedByte()];
                    h.depth = in.readUnsignedByte();
                    if (h.depth > 0) {
                        int bed = in.readUnsignedShort();
                        if (bed != 0) {
                            h.bed = blocks[bed - 1];
                            h.bedBiome = biomes[in.readUnsignedByte()];
                        }
                    }
                }
                sink.chunk(x, z, hits);
            }
        }
    }
}
