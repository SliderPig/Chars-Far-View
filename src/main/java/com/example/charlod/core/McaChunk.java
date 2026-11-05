package com.example.charlod.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

// A hand-written reader for the chunk format of Minecraft 1.18 to 1.20: it walks the bytes, keeps only what it needs (no whole NBT tree,
// which is most of the cost of loading a chunk) and points back into the buffer for the long block arrays. One per worker: it reuses everything.
public final class McaChunk implements ColumnSource {
    public interface Names {
        int block(String name, String properties);

        int biome(String name);
    }

    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final Set<String> DONE = Set.of("minecraft:full", "full", "minecraft:spawn", "spawn", "minecraft:light", "light", "minecraft:heightmaps", "heightmaps", "minecraft:features", "features", "minecraft:initialize_light", "initialize_light");
    private static final int OFFSET = 128;

    private static final class Section {
        int y;
        int[] blocks = new int[16];
        int blockCount, blockData, blockBits, blockPerLong;
        int[] biomes = new int[8];
        int biomeCount, biomeData, biomeBits, biomePerLong;
        boolean onlyAir;

        void reset() {
            blockCount = 0;
            blockData = -1;
            biomeCount = 0;
            biomeData = -1;
            onlyAir = true;
        }
    }

    private final Section[] pool = new Section[64];
    private final Section[] byY = new Section[OFFSET * 2];
    private int poolUsed;
    private int minSection, maxSection;
    private byte[] b;
    private int p;
    private Names names;
    private int airBlock;

    public int chunkX, chunkZ;
    public boolean surfaceDone;
    public int dataVersion;

    public McaChunk() {
        for (int i = 0; i < pool.length; i++) pool[i] = new Section();
    }

    // False if the bytes make no sense as a chunk.
    public boolean parse(byte[] bytes, int length, Names names) {
        this.b = bytes;
        this.p = 0;
        this.names = names;
        this.airBlock = names.block("minecraft:air", "");
        poolUsed = 0;
        minSection = Integer.MAX_VALUE;
        maxSection = Integer.MIN_VALUE;
        Arrays.fill(byY, null);
        surfaceDone = false;
        chunkX = chunkZ = Integer.MIN_VALUE;
        dataVersion = 0;
        try {
            if (length < 3 || u1() != 10) return false;
            skipString();
            int type;
            while ((type = u1()) != 0) {
                String name = shortName();
                switch (name) {
                    case "xPos" -> chunkX = i4();
                    case "zPos" -> chunkZ = i4();
                    case "DataVersion" -> dataVersion = i4();
                    case "Status" -> surfaceDone = DONE.contains(string());
                    case "sections" -> sections(type);
                    default -> skip(type);
                }
            }
            return chunkX != Integer.MIN_VALUE && chunkZ != Integer.MIN_VALUE;
        } catch (ArrayIndexOutOfBoundsException | NegativeArraySizeException e) {
            return false;
        }
    }

    public boolean hasSections() {
        return minSection != Integer.MAX_VALUE;
    }

    private void sections(int type) {
        if (type != 9) {
            skip(type);
            return;
        }
        int inner = u1();
        int count = i4();
        for (int i = 0; i < count; i++) {
            if (inner != 10) {
                skip(inner);
                continue;
            }
            Section s = poolUsed < pool.length ? pool[poolUsed] : null;
            if (s != null) s.reset();
            Section use = s != null ? s : new Section();
            if (s == null) use.reset();
            use.y = Integer.MIN_VALUE;
            int t;
            while ((t = u1()) != 0) {
                String name = shortName();
                switch (name) {
                    case "Y" -> use.y = t == 1 ? (byte) u1() : t == 3 ? i4() : skipAndZero(t);
                    case "block_states" -> blockStates(t, use);
                    case "biomes" -> biomes(t, use);
                    default -> skip(t);
                }
            }
            if (use.y == Integer.MIN_VALUE || use.y < -OFFSET || use.y >= OFFSET || use.blockCount == 0) continue;
            poolUsed++;
            byY[use.y + OFFSET] = use;
            if (use.y < minSection) minSection = use.y;
            if (use.y > maxSection) maxSection = use.y;
        }
    }

    private int skipAndZero(int t) {
        skip(t);
        return Integer.MIN_VALUE;
    }

    private void blockStates(int type, Section s) {
        if (type != 10) {
            skip(type);
            return;
        }
        int t;
        while ((t = u1()) != 0) {
            String name = shortName();
            if (name.equals("palette") && t == 9) {
                int inner = u1();
                int n = i4();
                if (s.blocks.length < n) s.blocks = new int[n];
                for (int i = 0; i < n; i++) {
                    if (inner != 10) {
                        skip(inner);
                        continue;
                    }
                    int id = paletteEntry();
                    s.blocks[s.blockCount++] = id;
                    if (id != airBlock) s.onlyAir = false;
                }
            } else if (name.equals("data") && t == 12) {
                int n = i4();
                s.blockData = p;
                p += n * 8;
            } else {
                skip(t);
            }
        }
        s.blockBits = Math.max(4, bitsFor(s.blockCount));
        s.blockPerLong = 64 / s.blockBits;
    }

    private int paletteEntry() {
        String blockName = null, props = "";
        int t;
        while ((t = u1()) != 0) {
            String name = shortName();
            if (name.equals("Name") && t == 8) {
                blockName = string();
            } else if (name.equals("Properties") && t == 10) {
                StringBuilder sb = null;
                int pt;
                while ((pt = u1()) != 0) {
                    String key = shortName();
                    if (pt != 8) {
                        skip(pt);
                        continue;
                    }
                    String value = string();
                    if (sb == null) sb = new StringBuilder();
                    else sb.append(',');
                    sb.append(key).append('=').append(value);
                }
                if (sb != null) props = sb.toString();
            } else {
                skip(t);
            }
        }
        return blockName == null ? airBlock : names.block(blockName, props);
    }

    private void biomes(int type, Section s) {
        if (type != 10) {
            skip(type);
            return;
        }
        int t;
        while ((t = u1()) != 0) {
            String name = shortName();
            if (name.equals("palette") && t == 9) {
                int inner = u1();
                int n = i4();
                if (s.biomes.length < n) s.biomes = new int[n];
                for (int i = 0; i < n; i++) {
                    if (inner == 8) s.biomes[s.biomeCount++] = names.biome(string());
                    else skip(inner);
                }
            } else if (name.equals("data") && t == 12) {
                int n = i4();
                s.biomeData = p;
                p += n * 8;
            } else {
                skip(t);
            }
        }
        s.biomeBits = Math.max(1, bitsFor(s.biomeCount));
        s.biomePerLong = 64 / s.biomeBits;
    }

    private static int bitsFor(int count) {
        return count <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
    }

    @Override
    public int minSection() {
        return minSection;
    }

    @Override
    public int maxSection() {
        return maxSection;
    }

    @Override
    public boolean sectionEmpty(int sectionY) {
        Section s = sectionY < -OFFSET || sectionY >= OFFSET ? null : byY[sectionY + OFFSET];
        return s == null || s.onlyAir;
    }

    @Override
    public int block(int x, int y, int z) {
        int sy = y >> 4;
        Section s = sy < -OFFSET || sy >= OFFSET ? null : byY[sy + OFFSET];
        if (s == null) return airBlock;
        if (s.blockData < 0 || s.blockCount <= 1) return s.blocks[0];
        int index = ((y & 15) << 8) | (z << 4) | x;
        int pack = index / s.blockPerLong;
        int bit = (index % s.blockPerLong) * s.blockBits;
        long v = (long) LONG_BE.get(b, s.blockData + pack * 8);
        int entry = (int) ((v >>> bit) & ((1L << s.blockBits) - 1));
        return entry < s.blockCount ? s.blocks[entry] : airBlock;
    }

    @Override
    public int biome(int x, int y, int z) {
        int sy = y >> 4;
        Section s = sy < -OFFSET || sy >= OFFSET ? null : byY[sy + OFFSET];
        if (s == null || s.biomeCount == 0) return 0;
        if (s.biomeData < 0 || s.biomeCount == 1) return s.biomes[0];
        int index = (((y & 15) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2);
        int pack = index / s.biomePerLong;
        int bit = (index % s.biomePerLong) * s.biomeBits;
        long v = (long) LONG_BE.get(b, s.biomeData + pack * 8);
        int entry = (int) ((v >>> bit) & ((1L << s.biomeBits) - 1));
        return entry < s.biomeCount ? s.biomes[entry] : s.biomes[0];
    }

    private int u1() {
        return b[p++] & 255;
    }

    private int u2() {
        return ((b[p++] & 255) << 8) | (b[p++] & 255);
    }

    private int i4() {
        return ((b[p++] & 255) << 24) | ((b[p++] & 255) << 16) | ((b[p++] & 255) << 8) | (b[p++] & 255);
    }

    private void skipString() {
        int n = u2();
        p += n;
    }

    private String string() {
        int n = u2();
        String s = new String(b, p, n, StandardCharsets.UTF_8);
        p += n;
        return s;
    }

    // Tag names are nearly all short and from a small set: ISO_8859_1 skips the UTF-8 work.
    private String shortName() {
        int n = u2();
        String s = new String(b, p, n, StandardCharsets.ISO_8859_1);
        p += n;
        return s;
    }

    private void skip(int type) {
        switch (type) {
            case 1 -> p += 1;
            case 2 -> p += 2;
            case 3, 5 -> p += 4;
            case 4, 6 -> p += 8;
            case 7 -> {
                int n = i4();
                p += n;
            }
            case 8 -> skipString();
            case 9 -> {
                int inner = u1();
                int n = i4();
                int fixed = switch (inner) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 2;
                    case 3, 5 -> 4;
                    case 4, 6 -> 8;
                    default -> -1;
                };
                if (fixed >= 0) p += n * fixed;
                else for (int i = 0; i < n; i++) skip(inner);
            }
            case 10 -> {
                int t;
                while ((t = u1()) != 0) {
                    skipString();
                    skip(t);
                }
            }
            case 11 -> {
                int n = i4();
                p += n * 4;
            }
            case 12 -> {
                int n = i4();
                p += n * 8;
            }
            default -> throw new ArrayIndexOutOfBoundsException("bad tag " + type);
        }
    }
}
