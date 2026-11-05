package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

// Ties it all into the game.
public final class FarClient {
    private static final KeyMapping TOGGLE = new KeyMapping("key.charlod.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.charlod");
    private static final KeyMapping SETTINGS = new KeyMapping("key.charlod.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.charlod");

    private static final LodRenderer RENDERER = new LodRenderer();
    private static final ConcurrentLinkedQueue<String> NOTES = new ConcurrentLinkedQueue<>();
    private static LodWorld world;
    private static long lastShaderCheck;
    private static boolean shaderPack, irisMissing, deepUnder;
    private static Class<?> irisApi;
    private static int tickCount, lastCaveCheck = -1;

    private FarClient() {}

    public static void init(IEventBus modBus) {
        FarSettings.load();
        modBus.addListener(FarClient::onKeys);
        modBus.addListener(FarClient::onStitch);
        MinecraftForge.EVENT_BUS.register(FarClient.class);
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new FarScreen(parent)));
    }

    private static void onKeys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
        e.register(SETTINGS);
    }

    private static void onStitch(TextureStitchEvent.Post e) {
        ColorTable.onStitch(e.getAtlas());
    }

    public static LodWorld world() {
        return world;
    }

    public static LodRenderer renderer() {
        return RENDERER;
    }

    // Called from any thread when something has gone wrong and the player should hear about it.
    public static void problem(String what) {
        NOTES.add(what);
    }

    private static boolean specialView(Minecraft mc, net.minecraft.client.Camera camera) {
        if (camera.getFluidInCamera() != FogType.NONE) return true;
        Entity e = camera.getEntity();
        return e instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }

    private static boolean drawing() {
        return FarSettings.enabled && !RENDERER.broken() && world != null && !shaderPackOn();
    }

    // A shader pack draws everything its own way: far terrain drawn by hand would come out wrong, so stand aside while one is on.
    private static boolean shaderPackOn() {
        long now = System.currentTimeMillis();
        if (now - lastShaderCheck > 1000) {
            lastShaderCheck = now;
            shaderPack = false;
            if (irisApi == null && !irisMissing) {
                try {
                    irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                } catch (Throwable t) {
                    irisMissing = true;
                }
            }
            if (irisApi != null) {
                try {
                    Object api = irisApi.getMethod("getInstance").invoke(null);
                    shaderPack = (Boolean) irisApi.getMethod("isShaderPackInUse").invoke(api);
                } catch (Throwable t) {
                    irisMissing = true;
                    irisApi = null;
                }
            }
        }
        return shaderPack;
    }

    // Deep in a cave with solid rock all round there is nothing far away to see: don't draw it.
    private static boolean deepUnderground(Minecraft mc, net.minecraft.client.Camera camera) {
        if ((tickCount & 15) == 0 && lastCaveCheck != tickCount) {
            lastCaveCheck = tickCount;
            ClientLevel level = mc.level;
            BlockPos at = camera.getBlockPosition();
            deepUnder = level.dimensionType().hasSkyLight()
                    && level.getBrightness(LightLayer.SKY, at) == 0
                    && at.getY() < level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()) - 12;
        }
        return deepUnder;
    }

    // Far terrain fog: {start, end}. The real chunks get the same numbers, so both fade the same way.
    public static float[] fogFor(Minecraft mc) {
        float end = FarSettings.distance;
        float rd = mc.options.getEffectiveRenderDistance() * 16f;
        ClientLevel level = mc.level;
        if (level != null && level.dimensionType().hasCeiling()) {
            end = Math.min(end, 768f);
            return new float[]{48f, Math.max(end, rd + 64f)};
        }
        float start = Math.max(end * 0.42f, rd * 1.2f);
        if (level != null) {
            float rain = level.getRainLevel(1f);
            if (rain > 0f) end *= 1f - 0.4f * rain;
        }
        return new float[]{Math.min(start, end * 0.8f), end};
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || !drawing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (specialView(mc, e.getCamera())) return;
        RENDERER.render(world, e, !deepUnderground(mc, e.getCamera()));
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog e) {
        if (!drawing() || e.getMode() != FogRenderer.FogMode.FOG_TERRAIN || e.getType() != FogType.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || specialView(mc, e.getCamera())) return;
        float[] f = fogFor(mc);
        // Where there is nothing beyond the real chunks yet, the game's own fog stays, or they'd end in a hard edge against the sky.
        float show = smooth(RENDERER.coverage(), 0.3f, 0.7f);
        e.setNearPlaneDistance(e.getNearPlaneDistance() + (f[0] - e.getNearPlaneDistance()) * show);
        e.setFarPlaneDistance(e.getFarPlaneDistance() + (f[1] - e.getFarPlaneDistance()) * show);
        e.setFogShape(FogShape.CYLINDER);
        e.setCanceled(true);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load e) {
        if (!(e.getLevel() instanceof ClientLevel level)) return;
        open(level);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ClientLevel level && world != null && world.level == level) close();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        close();
    }

    private static void close() {
        if (world == null) return;
        RENDERER.forget(world);
        world.close();
        world = null;
    }

    public static void reopen() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        close();
        if (level != null) open(level);
    }

    // Throws away this world's far terrain and starts it again from the save files or from what you see next.
    public static void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LodWorld w = world;
        if (level == null || w == null) return;
        Path dir = w.dir;
        close();
        w.afterClose(() -> deleteFolder(dir));
        open(level);
    }

    private static void deleteFolder(Path dir) {
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) java.nio.file.Files.deleteIfExists(f);
        } catch (java.io.IOException e) {
            CharLod.LOG.warn("[Far View] couldn't clear {}: {}", dir, e.toString());
        }
    }

    private static void open(ClientLevel level) {
        close();
        try {
            Minecraft mc = Minecraft.getInstance();
            String dim = level.dimension().location().toString().replace(':', '_').replace('/', '_');
            Path base = mc.gameDirectory.toPath().resolve(CharLod.MODID);
            Path regionDir = null;
            String id;
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null) {
                Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                long seed = server.getWorldData().worldGenOptions().seed();
                id = "sp_" + clean(root.getFileName().toString()) + "_" + Long.toHexString(seed);
                regionDir = DimensionType.getStorageFolder(level.dimension(), root).resolve("region");
            } else {
                ServerData data = mc.getCurrentServer();
                id = "mp_" + clean(data != null ? data.ip : "unknown");
            }
            world = new LodWorld(level, base.resolve(id).resolve(dim), regionDir);
            CharLod.LOG.info("[Far View] opened {} {}", id, dim);
        } catch (Throwable t) {
            CharLod.LOG.error("[Far View] couldn't start for this world", t);
            problem("couldn't start for this world");
        }
    }

    private static float smooth(float v, float lo, float hi) {
        float t = Math.max(0f, Math.min(1f, (v - lo) / (hi - lo)));
        return t * t * (3f - 2f * t);
    }

    private static String clean(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (world != null && e.getLevel() == world.level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk c) world.seen(c);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (world != null && e.getLevel() == world.level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk c) world.seen(c);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tickCount++;
        Minecraft mc = Minecraft.getInstance();
        String note;
        while ((note = NOTES.poll()) != null) {
            if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("Far View: " + note + ". It switched itself off, the rest of the game is fine.").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        while (TOGGLE.consumeClick()) {
            FarSettings.enabled = !FarSettings.enabled;
            FarSettings.save();
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Far View " + (FarSettings.enabled ? "on" : "off")).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
        while (SETTINGS.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new FarScreen(null));
        }
        if (world != null && world.remote != null) world.remote.tick(mc);
        if (world != null && mc.player != null && !FarSettings.enabled) {
            // Workers still need to know where you are when it's switched back on.
            world.camX = mc.player.getX();
            world.camY = mc.player.getY();
            world.camZ = mc.player.getZ();
        }
    }

    public static List<String> status() {
        List<String> out = new ArrayList<>();
        if (RENDERER.broken()) {
            out.add("Far View: off (" + RENDERER.brokenWhy() + ")");
            return out;
        }
        if (!FarSettings.enabled) {
            out.add("Far View: off");
            return out;
        }
        LodWorld w = world;
        if (w == null) {
            out.add("Far View: no world");
            return out;
        }
        out.add(String.format("Far View %d blocks, %s: draw %.2f ms, tiles %d of %d, quads %d", FarSettings.distance, FarSettings.quality.label, RENDERER.drawMillis(), RENDERER.drawnTiles(), RENDERER.selectedTiles(), RENDERER.drawnQuads()));
        Runtime rt = Runtime.getRuntime();
        out.add(String.format("Far View memory: gpu %.1f MB, tile cache %d, queue %d, heap %d of %d MB, pressure %.2f", RENDERER.gpuBytes() / 1048576.0, w.store() == null ? 0 : w.store().cachedTiles(), w.queuedTiles(), (rt.totalMemory() - rt.freeMemory()) / 1048576L, rt.maxMemory() / 1048576L, RENDERER.pressure()));
        if (w.scanner != null) {
            out.add(String.format("Far View scan: %s, regions left %d of %d, chunks %d (%.2f ms each), seen %d", w.scanner.status, w.scanner.regionsLeft, w.scanner.regionsTotal, w.scanner.chunksRead, w.scanner.chunksRead == 0 ? 0.0 : w.scanner.nanosReading / 1e6 / w.scanner.chunksRead, w.seenChunksCount()));
        } else {
            RemoteFeed f = w.remote;
            out.add(String.format("Far View: chunks seen %d, server: %s, regions left %d, got %d chunks in %d KB", w.seenChunksCount(), f.status, f.regionsLeft, f.chunksReceived, f.bytesReceived / 1024));
        }
        return out;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText e) {
        e.getLeft().addAll(status());
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onHud(net.minecraftforge.client.event.RenderGuiEvent.Post e) {
        if (!FarSettings.showHud) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.renderDebug || mc.screen != null) return;
        FarScreen.drawHud(e.getGuiGraphics(), status());
    }
}
