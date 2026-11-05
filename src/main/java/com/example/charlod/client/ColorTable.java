package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.example.charlod.core.BlockTable;
import com.example.charlod.core.McaChunk;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

// What colour each block is from far away: the average of its real textures, layered like its model, with its biome's tint worked out through the
// game's own colour rules, so modded blocks and resource packs just work.
public final class ColorTable implements McaChunk.Names {
    private static final int TINT_NONE = -1, TINT_FLUID = -2;

    private record Avg(float r, float g, float b, float cover) {}

    private record Layer(float r, float g, float b, float cover, int tint) {}

    private static final class Entry {
        BlockState state;
        int flags;
        Layer[] top, side;
        boolean tinted;
        int plainTop, plainSide;
    }

    // Filled on the render thread when the block atlas is built, so workers never touch texture memory themselves.
    private static volatile Map<ResourceLocation, Avg> sprites = Map.of();

    private final Registry<Biome> biomes;
    private final AtomicReferenceArray<Entry> entries;
    private final Map<String, Integer> blockNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> biomeNames = new ConcurrentHashMap<>();
    private final int stoneId;

    public ColorTable(net.minecraft.client.multiplayer.ClientLevel level) {
        biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        entries = new AtomicReferenceArray<>(Block.BLOCK_STATE_REGISTRY.size() + 1);
        stoneId = Block.getId(Blocks.STONE.defaultBlockState());
    }

    public Registry<Biome> biomes() {
        return biomes;
    }

    public static void onStitch(TextureAtlas atlas) {
        if (!atlas.location().equals(InventoryMenu.BLOCK_ATLAS)) return;
        long start = System.nanoTime();
        Map<ResourceLocation, TextureAtlasSprite> byName = null;
        try {
            for (Field f : TextureAtlas.class.getDeclaredFields()) {
                if (f.getType() != Map.class) continue;
                f.setAccessible(true);
                @SuppressWarnings("unchecked") Map<ResourceLocation, TextureAtlasSprite> m = (Map<ResourceLocation, TextureAtlasSprite>) f.get(atlas);
                if (m != null && !m.isEmpty() && m.keySet().iterator().next() instanceof ResourceLocation && m.values().iterator().next() instanceof TextureAtlasSprite) {
                    byName = m;
                    break;
                }
            }
        } catch (Throwable t) {
            CharLod.LOG.warn("[Far View] couldn't list block textures: {}", t.toString());
        }
        if (byName == null) return;
        Map<ResourceLocation, Avg> out = new HashMap<>(byName.size() * 2);
        for (var e : byName.entrySet()) {
            Avg a = average(e.getValue());
            if (a != null) out.put(e.getKey(), a);
        }
        sprites = out;
        CharLod.LOG.info("[Far View] {} block textures averaged in {} ms", out.size(), (System.nanoTime() - start) / 1_000_000);
    }

    private static Avg average(TextureAtlasSprite sprite) {
        try {
            NativeImage img = sprite.contents().getOriginalImage();
            int w = img.getWidth(), h = img.getHeight();
            int step = Math.max(1, Math.max(w, h) / 64);
            long r = 0, g = 0, b = 0;
            int solid = 0, total = 0;
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int c = img.getPixelRGBA(x, y); // alpha, blue, green, red
                    total++;
                    if ((c >>> 24) < 32) continue;
                    solid++;
                    r += c & 255;
                    g += (c >> 8) & 255;
                    b += (c >> 16) & 255;
                }
            }
            if (solid == 0) return new Avg(0.5f, 0.5f, 0.5f, 0f);
            return new Avg(r / (255f * solid), g / (255f * solid), b / (255f * solid), (float) solid / total);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int block(String name, String properties) {
        String key = properties.isEmpty() ? name : name + "[" + properties + "]";
        Integer got = blockNames.get(key);
        if (got != null) return got;
        int id = resolve(name, properties);
        blockNames.put(key, id);
        return id;
    }

    private int resolve(String name, String properties) {
        ResourceLocation rl = ResourceLocation.tryParse(name);
        if (rl == null || !ForgeRegistries.BLOCKS.containsKey(rl)) return stoneId; // a block from a mod that's gone: at least stays solid
        Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null) return stoneId;
        BlockState state = block.defaultBlockState();
        if (!properties.isEmpty()) {
            for (String pair : properties.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) state = with(state, pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return Block.getId(state);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static BlockState with(BlockState state, String key, String value) {
        Property<?> p = state.getBlock().getStateDefinition().getProperty(key);
        return p == null ? state : withValue(state, p, value);
    }

    @Override
    public int biome(String name) {
        Integer got = biomeNames.get(name);
        if (got != null) return got;
        ResourceLocation rl = ResourceLocation.tryParse(name);
        Biome b = rl == null ? null : biomes.get(rl);
        int id = b == null ? 0 : biomes.getId(b);
        biomeNames.put(name, id);
        return id;
    }

    private Entry entry(int id) {
        if (id < 0 || id >= entries.length()) id = stoneId;
        Entry e = entries.get(id);
        if (e != null) return e;
        e = build(id);
        entries.set(id, e);
        return e;
    }

    private Entry build(int id) {
        BlockState state = Block.stateById(id);
        if (state == null) state = Blocks.STONE.defaultBlockState();
        Entry e = new Entry();
        e.state = state;
        try {
            e.flags = com.example.charlod.net.BlockRules.flagsOf(state);
            if ((e.flags & BlockTable.AIR) == 0) layers(state, e);
        } catch (Throwable t) {
            e.flags = 0;
            e.top = e.side = new Layer[]{new Layer(0.5f, 0.5f, 0.5f, 1f, TINT_NONE)};
        }
        if (e.top == null) e.top = e.side = new Layer[]{new Layer(0f, 0f, 0f, 1f, TINT_NONE)};
        e.tinted = hasTint(e.top) || hasTint(e.side);
        if (!e.tinted) {
            e.plainTop = compose(e.top, e, null);
            e.plainSide = compose(e.side, e, null);
        }
        return e;
    }

    private static boolean hasTint(Layer[] ls) {
        for (Layer l : ls) if (l.tint != TINT_NONE) return true;
        return false;
    }

    private static void layers(BlockState state, Entry e) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && (e.flags & BlockTable.WATER) != 0 || !fluid.isEmpty() && state.getRenderShape() == RenderShape.INVISIBLE) {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
            Avg a = sprites.get(ext.getStillTexture());
            Layer l = a != null ? new Layer(a.r, a.g, a.b, 1f, TINT_FLUID) : new Layer(0.25f, 0.4f, 0.9f, 1f, TINT_NONE);
            e.top = e.side = new Layer[]{l};
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        RandomSource rand = RandomSource.create(42L);
        List<BakedQuad> loose = model.getQuads(state, null, rand, ModelData.EMPTY, null);
        List<BakedQuad> top = facing(model, state, Direction.UP, loose, rand);
        List<BakedQuad> side = facing(model, state, Direction.NORTH, loose, rand);
        if (top.isEmpty()) top = !side.isEmpty() ? side : loose;
        if (side.isEmpty()) side = top;
        e.top = toLayers(top);
        e.side = toLayers(side);
        if (e.top == null) e.top = fallback(state, model);
        if (e.side == null) e.side = e.top;
    }

    private static List<BakedQuad> facing(BakedModel model, BlockState state, Direction dir, List<BakedQuad> loose, RandomSource rand) {
        List<BakedQuad> out = new ArrayList<>(model.getQuads(state, dir, rand, ModelData.EMPTY, null));
        for (BakedQuad q : loose) if (q.getDirection() == dir) out.add(q);
        return out;
    }

    private static Layer[] toLayers(List<BakedQuad> quads) {
        if (quads.isEmpty()) return null;
        List<Layer> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            Avg a = sprites.get(q.getSprite().contents().name());
            if (a == null || a.cover <= 0f) continue;
            out.add(new Layer(a.r, a.g, a.b, a.cover, q.isTinted() ? q.getTintIndex() : TINT_NONE));
            if (out.size() >= 8) break;
        }
        return out.isEmpty() ? null : out.toArray(new Layer[0]);
    }

    private static Layer[] fallback(BlockState state, BakedModel model) {
        try {
            Avg a = sprites.get(model.getParticleIcon(ModelData.EMPTY).contents().name());
            if (a != null && a.cover > 0f && !model.getParticleIcon(ModelData.EMPTY).contents().name().getPath().equals("missingno")) {
                return new Layer[]{new Layer(a.r, a.g, a.b, 1f, TINT_NONE)};
            }
        } catch (Throwable ignored) {
        }
        int c = 0x808080;
        try {
            int map = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            if (map != 0) c = map;
        } catch (Throwable ignored) {
        }
        return new Layer[]{new Layer(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, 1f, TINT_NONE)};
    }

    private final class TintLevel implements BlockAndTintGetter {
        Biome biome;
        BlockState state;

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1f;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return state.getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    private int compose(Layer[] layers, Entry e, TintLevel tint) {
        float r = 0, g = 0, b = 0;
        BlockColors colours = Minecraft.getInstance().getBlockColors();
        for (int i = 0; i < layers.length; i++) {
            Layer l = layers[i];
            float lr = l.r, lg = l.g, lb = l.b;
            if (l.tint != TINT_NONE && tint != null) {
                int c = -1;
                try {
                    tint.state = e.state;
                    if (l.tint == TINT_FLUID) c = IClientFluidTypeExtensions.of(e.state.getFluidState()).getTintColor(e.state.getFluidState(), tint, BlockPos.ZERO);
                    else c = colours.getColor(e.state, tint, BlockPos.ZERO, l.tint);
                } catch (Throwable ignored) {
                }
                if (c != -1) {
                    lr *= ((c >> 16) & 255) / 255f;
                    lg *= ((c >> 8) & 255) / 255f;
                    lb *= (c & 255) / 255f;
                }
            }
            if (i == 0) {
                r = lr;
                g = lg;
                b = lb;
            } else {
                r += (lr - r) * l.cover;
                g += (lg - g) * l.cover;
                b += (lb - b) * l.cover;
            }
        }
        if ((e.flags & BlockTable.FOLIAGE) != 0) {
            // Leaves are full of holes with dark behind them.
            r *= 0.74f;
            g *= 0.74f;
            b *= 0.74f;
        }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, (int) (v * 255f + 0.5f)));
    }

    // One per worker: a small fast memory of block + biome colours, no locks.
    public final class View implements BlockTable {
        private static final int SIZE = 4096;
        private final long[] keys = new long[SIZE];
        private final int[] tops = new int[SIZE], sides = new int[SIZE];
        private final TintLevel tint = new TintLevel();

        public View() {
            java.util.Arrays.fill(keys, -1L);
        }

        @Override
        public int flags(int block) {
            return entry(block).flags;
        }

        private int slot(int block, int biome) {
            long key = ((long) block << 20) | (biome & 0xFFFFF);
            int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 52);
            if (keys[i] != key) {
                Entry e = entry(block);
                Biome b = biomes.byId(biome);
                if (b == null) b = biomes.byId(0);
                tint.biome = b;
                try {
                    tops[i] = b == null ? 0x808080 : compose(e.top, e, tint);
                    sides[i] = b == null ? 0x808080 : compose(e.side, e, tint);
                } catch (Throwable t) {
                    tops[i] = sides[i] = 0x808080;
                }
                keys[i] = key;
            }
            return i;
        }

        @Override
        public int top(int block, int biome) {
            Entry e = entry(block);
            return e.tinted ? tops[slot(block, biome)] : e.plainTop;
        }

        @Override
        public int side(int block, int biome) {
            Entry e = entry(block);
            return e.tinted ? sides[slot(block, biome)] : e.plainSide;
        }
    }
}
