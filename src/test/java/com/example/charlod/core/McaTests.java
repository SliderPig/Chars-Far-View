package com.example.charlod.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// Writes real region files the way the game does, then reads them back with the fast reader.
class McaTests {
    static final class Names implements McaChunk.Names {
        final Map<String, Integer> blocks = new HashMap<>(), biomes = new HashMap<>();

        @Override
        public int block(String name, String properties) {
            return blocks.computeIfAbsent(properties.isEmpty() ? name : name + "[" + properties + "]", k -> blocks.size());
        }

        @Override
        public int biome(String name) {
            return biomes.computeIfAbsent(name, k -> biomes.size());
        }
    }

    // Plain NBT writer for the test files.
    static final class Nbt {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);

        Nbt name(int type, String name) throws IOException {
            out.writeByte(type);
            out.writeUTF(name);
            return this;
        }

        Nbt intTag(String name, int v) throws IOException {
            name(3, name);
            out.writeInt(v);
            return this;
        }

        Nbt str(String name, String v) throws IOException {
            name(8, name);
            out.writeUTF(v);
            return this;
        }

        Nbt longs(String name, long[] v) throws IOException {
            name(12, name);
            out.writeInt(v.length);
            for (long l : v) out.writeLong(l);
            return this;
        }

        Nbt end() throws IOException {
            out.writeByte(0);
            return this;
        }
    }

    // Packs entries the way the game's bit storage does: whole entries per long, lowest bits first.
    static long[] pack(int[] entries, int bits) {
        int per = 64 / bits;
        long[] out = new long[(entries.length + per - 1) / per];
        for (int i = 0; i < entries.length; i++) out[i / per] |= ((long) entries[i]) << ((i % per) * bits);
        return out;
    }

    // layers[sectionIndex] maps (y&15)<<8|z<<4|x to a palette index; palette[sectionIndex] holds names like "minecraft:stone" or "minecraft:x[a=b]".
    static byte[] chunk(int x, int z, String status, int[] sectionYs, String[][] palettes, IntUnaryOperator[] fill, String[][] biomePalettes, int[][] biomeEntries) throws IOException {
        Nbt n = new Nbt();
        n.name(10, "");
        n.intTag("DataVersion", 3465);
        n.intTag("xPos", x);
        n.intTag("zPos", z);
        n.name(1, "isLightOn");
        n.out.writeByte(1);
        n.str("Status", status);
        // something the reader has to step over: a compound holding lists and arrays
        n.name(10, "Heightmaps");
        n.longs("WORLD_SURFACE", new long[37]);
        n.name(9, "stuff");
        n.out.writeByte(10);
        n.out.writeInt(2);
        n.intTag("a", 1);
        n.end();
        n.str("b", "text");
        n.end();
        n.end();
        n.name(9, "sections");
        n.out.writeByte(10);
        n.out.writeInt(sectionYs.length);
        for (int s = 0; s < sectionYs.length; s++) {
            n.name(1, "Y");
            n.out.writeByte(sectionYs[s]);
            n.name(10, "block_states");
            n.name(9, "palette");
            n.out.writeByte(10);
            n.out.writeInt(palettes[s].length);
            for (String entry : palettes[s]) {
                String name = entry;
                String props = null;
                int cut = entry.indexOf('[');
                if (cut > 0) {
                    name = entry.substring(0, cut);
                    props = entry.substring(cut + 1, entry.length() - 1);
                }
                n.str("Name", name);
                if (props != null) {
                    n.name(10, "Properties");
                    for (String kv : props.split(",")) {
                        String[] pair = kv.split("=");
                        n.str(pair[0], pair[1]);
                    }
                    n.end();
                }
                n.end();
            }
            if (palettes[s].length > 1) {
                int[] entries = new int[4096];
                for (int i = 0; i < 4096; i++) entries[i] = fill[s].applyAsInt(i);
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palettes[s].length - 1));
                n.longs("data", pack(entries, bits));
            }
            n.end();
            if (biomePalettes != null) {
                n.name(10, "biomes");
                n.name(9, "palette");
                n.out.writeByte(8);
                n.out.writeInt(biomePalettes[s].length);
                for (String b : biomePalettes[s]) n.out.writeUTF(b);
                if (biomePalettes[s].length > 1) {
                    int bits = 32 - Integer.numberOfLeadingZeros(biomePalettes[s].length - 1);
                    n.longs("data", pack(biomeEntries[s], bits));
                }
                n.end();
            }
            n.name(7, "SkyLight");
            n.out.writeInt(2048);
            n.out.write(new byte[2048]);
            n.end();
        }
        n.end();
        return n.bytes.toByteArray();
    }

    static void writeRegion(Path file, Map<Integer, byte[]> chunks, Map<Integer, Integer> compression) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(8192);
            int sector = 2;
            for (var e : chunks.entrySet()) {
                int type = compression.getOrDefault(e.getKey(), 2);
                byte[] packed = packed(e.getValue(), type);
                int sectors = (packed.length + 5 + 4095) / 4096;
                f.seek(e.getKey() * 4L);
                f.writeInt((sector << 8) | sectors);
                f.seek(sector * 4096L);
                f.writeInt(packed.length + 1);
                f.writeByte(type);
                f.write(packed);
                sector += sectors;
            }
            f.setLength(sector * 4096L);
        }
    }

    static byte[] packed(byte[] raw, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (type == 3) return raw;
        try (var d = type == 1 ? new GZIPOutputStream(out) : new DeflaterOutputStream(out)) {
            d.write(raw);
        }
        return out.toByteArray();
    }

    static byte[] simpleColumnChunk(int x, int z, String status) throws IOException {
        // section 4 (y 64-79) holds stone up to y 70 at x<8 and water at x>=8 up to y 66, section 3 is solid stone, section 5 is air
        String[][] palettes = {{"minecraft:stone"}, {"minecraft:air", "minecraft:stone", "minecraft:water[level=0]"}, {"minecraft:air"}};
        IntUnaryOperator fill = i -> {
            int y = 64 + (i >> 8), px = i & 15;
            if (px < 8) return y <= 70 ? 1 : 0;
            return y <= 66 ? 2 : 0;
        };
        int[] biomes = new int[64];
        for (int i = 0; i < 64; i++) biomes[i] = (i & 3) < 2 ? 0 : 1; // low x biome 0, high x biome 1
        return chunk(x, z, status, new int[]{3, 4, 5}, palettes, new IntUnaryOperator[]{null, fill, null},
                new String[][]{{"minecraft:plains"}, {"minecraft:plains", "minecraft:ocean"}, {"minecraft:plains"}}, new int[][]{null, biomes, null});
    }

    @Test
    void readsRegionFilesInEveryPackingTheGameUses(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.-1.2.mca");
        Map<Integer, byte[]> chunks = new HashMap<>();
        Map<Integer, Integer> types = new HashMap<>();
        chunks.put(0, simpleColumnChunk(-32, 64, "minecraft:full"));
        types.put(0, 2);
        chunks.put(1, simpleColumnChunk(-31, 64, "full"));
        types.put(1, 1);
        chunks.put(33, simpleColumnChunk(-31, 65, "minecraft:noise"));
        types.put(33, 3);
        writeRegion(file, chunks, types);
        Names names = new Names();
        try (McaFile mca = new McaFile(file)) {
            assertEquals(3, mca.presentCount());
            assertTrue(mca.present(0));
            assertFalse(mca.present(2));
            McaChunk c = new McaChunk();
            for (int index : new int[]{0, 1}) {
                assertTrue(mca.read(index));
                assertTrue(c.parse(mca.data(), mca.dataLength(), names));
                assertTrue(c.surfaceDone);
                assertEquals(-32 + (index & 31), c.chunkX);
                assertEquals(64, c.chunkZ);
                assertEquals(3465, c.dataVersion);
                assertEquals(3, c.minSection());
                assertEquals(5, c.maxSection());
                assertTrue(c.sectionEmpty(5));
                assertFalse(c.sectionEmpty(4));
                int stone = names.block("minecraft:stone", ""), water = names.block("minecraft:water", "level=0"), air = names.block("minecraft:air", "");
                assertEquals(stone, c.block(0, 70, 0));
                assertEquals(air, c.block(0, 71, 0));
                assertEquals(water, c.block(15, 66, 15));
                assertEquals(air, c.block(15, 67, 15));
                assertEquals(stone, c.block(5, 50, 5));
                assertEquals(air, c.block(5, 90, 5));
                assertEquals(air, c.block(5, 500, 5));
                assertEquals(names.biome("minecraft:plains"), c.biome(2, 66, 2));
                assertEquals(names.biome("minecraft:ocean"), c.biome(12, 66, 2));
            }
            assertTrue(mca.read(33));
            assertTrue(c.parse(mca.data(), mca.dataLength(), names));
            assertFalse(c.surfaceDone, "a chunk still being made is not used");
            assertFalse(mca.read(2));
        }
    }

    @Test
    void manyPaletteEntriesAndWideBiomePalettesStillRead(@TempDir Path dir) throws IOException {
        String[] palette = new String[40];
        palette[0] = "minecraft:air";
        for (int i = 1; i < 40; i++) palette[i] = "mod:block" + i;
        int[] biomes = new int[64];
        String[] biomeNames = new String[6];
        for (int i = 0; i < 6; i++) biomeNames[i] = "mod:biome" + i;
        for (int i = 0; i < 64; i++) biomes[i] = i % 6;
        byte[] raw = chunk(0, 0, "minecraft:full", new int[]{-4}, new String[][]{palette}, new IntUnaryOperator[]{i -> i % 40}, new String[][]{biomeNames}, new int[][]{biomes});
        Names names = new Names();
        McaChunk c = new McaChunk();
        assertTrue(c.parse(raw, raw.length, names));
        for (int i = 0; i < 4096; i += 13) {
            int x = i & 15, z = (i >> 4) & 15, y = -64 + (i >> 8);
            assertEquals(names.block(palette[i % 40], ""), c.block(x, y, z));
        }
        assertEquals(names.biome("mod:biome" + (((1 << 4) | (2 << 2) | 3) % 6)), c.biome(3 * 4, -64 + 4, 2 * 4));
    }

    @Test
    void junkDoesNotBlowUp() {
        Names names = new Names();
        McaChunk c = new McaChunk();
        assertFalse(c.parse(new byte[]{10, 0, 0, 3, 0, 1, 'x'}, 7, names));
        assertFalse(c.parse(new byte[]{1, 2}, 2, names));
    }

    @Test
    void sampledFromARegionFileLikeARealWorld(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        writeRegion(file, Map.of(0, simpleColumnChunk(0, 0, "minecraft:full")), Map.of(0, 2));
        Names names = new Names();
        int stone = names.block("minecraft:stone", ""), water = names.block("minecraft:water", "level=0"), air = names.block("minecraft:air", "");
        int ocean = names.biome("minecraft:ocean"), plains = names.biome("minecraft:plains");
        BlockTable table = new BlockTable() {
            @Override
            public int flags(int b) {
                return b == air ? AIR : b == water ? WATER : 0;
            }

            @Override
            public int top(int b, int biome) {
                return b == stone ? 0x808080 : biome == ocean ? 0x2040C0 : 0x4080F0;
            }

            @Override
            public int side(int b, int biome) {
                return top(b, biome);
            }
        };
        ChunkColumns out = new ChunkColumns();
        try (McaFile mca = new McaFile(file)) {
            assertTrue(mca.read(0));
            McaChunk c = new McaChunk();
            assertTrue(c.parse(mca.data(), mca.dataLength(), names));
            ColumnSampler.sample(c, table, Integer.MAX_VALUE, out);
        }
        assertEquals(71, out.height[0]);
        assertEquals(0x808080, out.top[0]);
        assertEquals(67, out.height[15]);
        assertTrue((out.top[15] & TileData.F_WATER) != 0);
        assertEquals(ColumnSampler.waterTop(0x2040C0, 0x808080, 67 - 64 + 0) , out.top[15] & TileData.RGB, "surface 66, sea bed under 3 blocks of water, at y 63");
        assertEquals(plains, names.biome("minecraft:plains"));
    }

    private static final class Fake implements ColumnSource {
        final int[][] column;

        Fake(int[][] byY) {
            column = byY;
        }

        @Override
        public int minSection() {
            return 0;
        }

        @Override
        public int maxSection() {
            return column.length / 16 - 1;
        }

        @Override
        public boolean sectionEmpty(int s) {
            for (int y = s * 16; y < s * 16 + 16; y++) if (column[y][0] != 0) return false;
            return true;
        }

        @Override
        public int block(int x, int y, int z) {
            return y >= 0 && y < column.length ? column[y][0] : 0;
        }

        @Override
        public int biome(int x, int y, int z) {
            return 0;
        }
    }

    private static final int AIR_B = 0, STONE = 1, GRASS = 2, FLOWER = 3, SNOW = 4, LEAF = 5, LAVA = 6, WATER_B = 7;
    private static final BlockTable TABLE = new BlockTable() {
        @Override
        public int flags(int b) {
            return switch (b) {
                case AIR_B -> AIR;
                case FLOWER -> THIN;
                case SNOW -> SNOW_LAYER;
                case LEAF -> FOLIAGE;
                case LAVA -> GLOW;
                case WATER_B -> WATER;
                default -> 0;
            };
        }

        @Override
        public int top(int b, int biome) {
            return switch (b) {
                case STONE -> 0x707070;
                case GRASS -> 0x20A020;
                case FLOWER -> 0xFF0000;
                case SNOW -> 0xFFFFFF;
                case LEAF -> 0x106010;
                case LAVA -> 0xFF6000;
                case WATER_B -> 0x2040C0;
                default -> 0;
            };
        }

        @Override
        public int side(int b, int biome) {
            return b == GRASS ? 0x806040 : top(b, biome);
        }
    };

    private static ChunkColumns sample(int ceiling, int... blocksFromBottom) {
        int[][] col = new int[32][1];
        for (int y = 0; y < blocksFromBottom.length; y++) col[y][0] = blocksFromBottom[y];
        ChunkColumns out = new ChunkColumns();
        ColumnSampler.sample(new Fake(col), TABLE, ceiling, out);
        return out;
    }

    @Test
    void flowersTintAndSnowCoversTheGroundBelow() {
        ChunkColumns plain = sample(Integer.MAX_VALUE, STONE, GRASS);
        assertEquals(2, plain.height[0]);
        assertEquals(0x20A020, plain.top[0]);
        assertEquals(0x806040, plain.side[0]);
        ChunkColumns flower = sample(Integer.MAX_VALUE, STONE, GRASS, FLOWER);
        assertEquals(2, flower.height[0], "a flower is not ground");
        assertNotEquals(0x20A020, flower.top[0] & TileData.RGB);
        ChunkColumns snow = sample(Integer.MAX_VALUE, STONE, GRASS, SNOW);
        assertEquals(2, snow.height[0]);
        assertEquals(0xFFFFFF, snow.top[0] & TileData.RGB);
    }

    @Test
    void leavesAndLavaAreFlagged() {
        assertTrue((sample(Integer.MAX_VALUE, STONE, LEAF).top[0] & TileData.F_FOLIAGE) != 0);
        assertTrue((sample(Integer.MAX_VALUE, STONE, LAVA).top[0] & TileData.F_GLOW) != 0);
    }

    @Test
    void waterSeesTheSeaBedThroughShallowsButNotTheDeep() {
        ChunkColumns shallow = sample(Integer.MAX_VALUE, STONE, WATER_B, WATER_B);
        assertEquals(3, shallow.height[0]);
        assertTrue((shallow.top[0] & TileData.F_WATER) != 0);
        int deep = ColumnSampler.waterTop(0x2040C0, 0xFFFFFF, 20);
        int near = ColumnSampler.waterTop(0x2040C0, 0xFFFFFF, 1);
        assertTrue(((near >> 16) & 255) > ((deep >> 16) & 255));
    }

    @Test
    void aRoofIsSkippedAndSmallPocketsInItIgnored() {
        int[] blocks = new int[32];
        for (int y = 0; y < 4; y++) blocks[y] = STONE;              // floor, top at y 3
        for (int y = 12; y < 32; y++) blocks[y] = STONE;            // the roof
        blocks[20] = AIR_B;                                         // a small pocket in the roof, too small to count
        blocks[21] = AIR_B;
        ChunkColumns under = sample(31, blocks);
        assertEquals(4, under.height[0]);
        ChunkColumns open = sample(Integer.MAX_VALUE, blocks);
        assertEquals(32, open.height[0]);
    }

    @Test
    void anEmptyColumnHasNoData() {
        assertEquals(LodMath.NO_DATA, sample(Integer.MAX_VALUE).height[0]);
    }
}
