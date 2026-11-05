package com.example.charlod.smoke;

import com.example.charlod.CharLod;
import com.example.charlod.client.FarClient;
import com.example.charlod.client.FarScreen;
import com.example.charlod.client.FarSettings;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// Real game checks, only in -Psmoke jars. charlod.check = survey | pregen | scenes.
// survey: prints biome maps of a few seeds. pregen: makes chunks in a spiral for charlod.seconds, then saves and quits (run it again to go on).
// scenes: walks through run-real/scenes.txt, taking screenshots with far view on and off, and measures frame rates.
@Mod.EventBusSubscriber(modid = CharLod.MODID, value = Dist.CLIENT)
public final class LodChecks {
    private static final String MODE = System.getProperty("charlod.check", "");
    private static final boolean SCENES_AFTER = Boolean.getBoolean("charlod.then");
    private static String mode = MODE;
    private static final long SEED = Long.getLong("charlod.seed", 1L);
    private static final int CENTER_X = Integer.getInteger("charlod.cx", 0), CENTER_Z = Integer.getInteger("charlod.cz", 0);
    private static final int SECONDS = Integer.getInteger("charlod.seconds", 420);
    private static final boolean FRESH = Boolean.getBoolean("charlod.fresh");
    private static final String WORLD = FRESH ? "fresh" + (System.currentTimeMillis() / 1000) : System.getProperty("charlod.world", "lodtest");
    private static long createdNanos;

    private static boolean titleSeen, started, quitting;
    private static long startNanos;
    private static int tick;
    private static final Deque<String[]> SCRIPT = new ArrayDeque<>();
    private static int waitTicks;
    private static String[] waiting;
    private static long waitStart;
    private static boolean scriptLoaded;

    // frame timing
    private static boolean measuring;
    private static long lastFrame;
    private static final List<Long> FRAMES = new ArrayList<>(4096);
    private static String measureName;
    private static long measureEnd;

    private LodChecks() {}

    private static void log(String s) {
        CharLod.LOG.info("[FarCheck] {}", s);
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Init.Post e) {
        if (!MODE.isEmpty() && e.getScreen() instanceof TitleScreen) titleSeen = true;
    }

    @SubscribeEvent
    public static void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !measuring) return;
        long now = System.nanoTime();
        if (lastFrame != 0) FRAMES.add(now - lastFrame);
        lastFrame = now;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (MODE.isEmpty() || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (titleSeen && !started) {
            started = true;
            startNanos = System.nanoTime();
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.renderDistance().set(Integer.getInteger("charlod.rd", 10));
            mc.options.save();
            FarSettings.distance = Integer.getInteger("charlod.distance", 2048);
            FarSettings.quality = FarSettings.Quality.BALANCED;
            FarSettings.threads = Integer.getInteger("charlod.threads", 2);
            FarSettings.enabled = true;
            FarSettings.seedGenerate = !Boolean.getBoolean("charlod.noseed");
            FarSettings.showHud = false;
            createdNanos = System.nanoTime();
            Path save = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("level.dat");
            if (Files.exists(save)) {
                mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);
            } else {
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                mc.createWorldOpenFlows().createFreshLevel(WORLD, new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                        new WorldOptions(SEED, true, false), access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(Boolean.getBoolean("charlod.flat") ? WorldPresets.FLAT : WorldPresets.NORMAL).value().createWorldDimensions());
            }
            return;
        }
        if (mc.player == null || mc.getSingleplayerServer() == null || mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty()) return;
        switch (mode) {
            case "survey" -> survey(mc);
            case "pregen" -> pregenClient(mc);
            case "scenes" -> scenes(mc);
            default -> {}
        }
    }

    private static void quit(Minecraft mc) {
        if (quitting) return;
        quitting = true;
        log("saving and quitting after " + (System.nanoTime() - startNanos) / 1_000_000_000L + " s");
        mc.level.disconnect();
        mc.clearLevel(new GenericDirtMessageScreen(Component.literal("Saving")));
        mc.stop();
    }

    private static char letter(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_DEEP_OCEAN)) return '=';
        if (b.is(BiomeTags.IS_OCEAN)) return '~';
        if (b.is(BiomeTags.IS_RIVER)) return ',';
        if (b.is(BiomeTags.IS_BEACH)) return '.';
        if (b.is(BiomeTags.IS_MOUNTAIN)) return '^';
        if (b.is(BiomeTags.IS_BADLANDS)) return 'B';
        if (b.is(BiomeTags.IS_SAVANNA)) return 's';
        if (b.is(BiomeTags.IS_JUNGLE)) return 'J';
        if (b.is(BiomeTags.IS_TAIGA)) return 't';
        if (b.is(BiomeTags.IS_FOREST)) return 'f';
        if (b.is(Biomes.DESERT)) return 'D';
        if (b.is(Biomes.SWAMP) || b.is(Biomes.MANGROVE_SWAMP)) return 'w';
        if (b.is(Biomes.SNOWY_PLAINS) || b.is(Biomes.ICE_SPIKES)) return '*';
        if (b.is(Biomes.PLAINS) || b.is(Biomes.SUNFLOWER_PLAINS)) return 'p';
        return '?';
    }

    private static void survey(Minecraft mc) {
        if (quitting) return;
        var server = mc.getSingleplayerServer();
        ServerLevel level = server.overworld();
        quitting = true;
        server.execute(() -> {
            try {
                NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
                var settings = gen.generatorSettings().value();
                var noises = level.registryAccess().lookupOrThrow(Registries.NOISE);
                long[] seeds = Arrays.stream(System.getProperty("charlod.seeds", "1,2,3,4,5,6,7,8").split(",")).mapToLong(Long::parseLong).toArray();
                for (long seed : seeds) {
                    RandomState state = RandomState.create(settings, noises, seed);
                    StringBuilder sb = new StringBuilder("seed " + seed + ":\n");
                    int step = 128, half = 28;
                    for (int z = -half; z < half; z++) {
                        for (int x = -half; x < half; x++) {
                            sb.append(letter(gen.getBiomeSource().getNoiseBiome((x * step) >> 2, 100 >> 2, (z * step) >> 2, state.sampler())));
                        }
                        sb.append('\n');
                    }
                    log(sb.toString());
                }
            } catch (Throwable t) {
                CharLod.LOG.error("[FarCheck] survey failed", t);
            }
            mc.execute(() -> {
                quitting = false;
                quit(mc);
            });
        });
    }

    // ---- making chunks

    private static final TicketType<ChunkPos> PREGEN = TicketType.create("charlod_pregen", Comparator.comparingLong(ChunkPos::toLong));
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static volatile long madeChunks;
    private static int ring, ringStep;
    private static boolean pregenStarted, pregenStop, pregenSaved;
    private static volatile boolean pregenRunning;
    private static long lastReport;

    private static Path progressFile(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("pregen-progress.txt");
    }

    private static void pregenClient(Minecraft mc) {
        if (!pregenStarted) {
            pregenStarted = true;
            mc.options.framerateLimit().set(10);
            mc.options.renderDistance().set(3);
            FarSettings.enabled = false;
            try {
                Path p = progressFile(mc);
                if (Files.exists(p)) {
                    String[] parts = Files.readString(p).trim().split(" ");
                    ring = Integer.parseInt(parts[0]);
                    ringStep = 0;
                    madeChunks = Long.parseLong(parts[1]);
                }
            } catch (Throwable t) {
                CharLod.LOG.warn("[FarCheck] no progress", t);
            }
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(CENTER_X + 0.5, 200, CENTER_Z + 0.5);
                pregenRunning = true;
            });
            lastReport = System.nanoTime();
        }
        long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        if (System.nanoTime() - lastReport > 30_000_000_000L) {
            lastReport = System.nanoTime();
            Runtime rt = Runtime.getRuntime();
            log("made " + madeChunks + " chunks, ring " + ring + " (radius " + ring * 16 + " blocks), in flight " + IN_FLIGHT.get() + ", heap " + (rt.totalMemory() - rt.freeMemory()) / 1048576L + " MB, " + seconds + " s");
        }
        if (seconds >= SECONDS && !pregenStop) {
            pregenStop = true;
            log("time is up");
        }
        if (pregenStop && IN_FLIGHT.get() == 0 && !pregenSaved) {
            pregenSaved = true;
            try {
                Files.writeString(progressFile(mc), ring + " " + madeChunks);
            } catch (IOException ignored) {
            }
            if (!SCENES_AFTER) {
                quit(mc);
                return;
            }
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                log("saving before the scenes");
                server.saveEverything(false, true, true);
                log("saved");
                mc.execute(() -> {
                    mc.options.framerateLimit().set(260);
                    mc.options.renderDistance().set(Integer.getInteger("charlod.rd", 10));
                    FarSettings.enabled = true;
                    mode = "scenes";
                });
            });
        }
    }

    private static void pregenTick(ServerLevel level) {
        var server = level.getServer();
        ServerChunkCache source = level.getChunkSource();
        int started = 0;
        while (!pregenStop && IN_FLIGHT.get() < 64 && started < 16) {
            int side = ring == 0 ? 1 : ring * 8;
            if (ringStep >= side) {
                ring++;
                ringStep = 0;
                continue;
            }
            int[] offset = ringOffset(ring, ringStep++);
            int x = (CENTER_X >> 4) + offset[0], z = (CENTER_Z >> 4) + offset[1];
            ChunkPos pos = new ChunkPos(x, z);
            source.addRegionTicket(PREGEN, pos, 0, pos);
            IN_FLIGHT.incrementAndGet();
            started++;
            // Asked from the server thread this call waits for the chunk: one at a time. Asked from elsewhere it just hands back a future.
            net.minecraft.Util.backgroundExecutor().execute(() -> source.getChunkFuture(x, z, ChunkStatus.FULL, true).whenComplete((result, error) -> {
                server.execute(() -> {
                    source.removeRegionTicket(PREGEN, pos, 0, pos);
                    IN_FLIGHT.decrementAndGet();
                    madeChunks++;
                });
            }));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (!pregenRunning || e.phase != TickEvent.Phase.END || e.getServer() == null) return;
        pregenTick(e.getServer().overworld());
    }

    private static int[] ringOffset(int r, int i) {
        if (r == 0) return new int[]{0, 0};
        int side = r * 2;
        int s = i / side, along = i % side;
        return switch (s) {
            case 0 -> new int[]{-r + along, -r};
            case 1 -> new int[]{r, -r + along};
            case 2 -> new int[]{r - along, r};
            default -> new int[]{-r, r - along};
        };
    }

    // ---- scenes

    private static void scenes(Minecraft mc) {
        if (!scriptLoaded) {
            scriptLoaded = true;
            try {
                for (String line : Files.readAllLines(mc.gameDirectory.toPath().resolve("scenes.txt"), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) SCRIPT.add(line.split("\\s+"));
                }
            } catch (IOException ex) {
                CharLod.LOG.error("[FarCheck] no scenes.txt", ex);
            }
            log("scenes: " + SCRIPT.size() + " steps");
            prepareScenePlayer(mc);
            waitTicks = 40;
            return;
        }
        if (measuring) {
            if (System.nanoTime() >= measureEnd) finishMeasure();
            return;
        }
        if (waiting != null) {
            if (!waitDone(mc)) return;
            waiting = null;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        String[] step = SCRIPT.poll();
        if (step == null) {
            for (String s : FarClient.status()) log(s);
            quit(mc);
            return;
        }
        try {
            run(mc, step);
        } catch (Throwable t) {
            CharLod.LOG.error("[FarCheck] step failed: {}", String.join(" ", step), t);
        }
    }

    private static void prepareScenePlayer(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            ServerLevel l = server.overworld();
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        });
    }

    private static void run(Minecraft mc, String[] s) {
        var server = mc.getSingleplayerServer();
        switch (s[0]) {
            case "tp" -> { // tp x y z yaw pitch
                double x = Double.parseDouble(s[1]), y = Double.parseDouble(s[2]), z = Double.parseDouble(s[3]);
                float yaw = Float.parseFloat(s[4]), pitch = Float.parseFloat(s[5]);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                    p.connection.teleport(x, y, z, yaw, pitch);
                });
                waitTicks = 10;
            }
            case "up" -> { // up dy yaw pitch: straight up from where you are, from the ground there
                double dy = Double.parseDouble(s[1]);
                float yaw = Float.parseFloat(s[2]), pitch = Float.parseFloat(s[3]);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.getAbilities().flying = true;
                    p.onUpdateAbilities();
                    p.connection.teleport(p.getX(), p.getY() + dy, p.getZ(), yaw, pitch);
                });
                waitTicks = 10;
            }
            case "dim" -> { // dim minecraft:the_nether x y z yaw pitch
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(s[1]));
                double x = Double.parseDouble(s[2]), y = Double.parseDouble(s[3]), z = Double.parseDouble(s[4]);
                float yaw = Float.parseFloat(s[5]), pitch = Float.parseFloat(s[6]);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    ServerLevel to = server.getLevel(key);
                    if (to != null) p.teleportTo(to, x, y, z, yaw, pitch);
                });
                waitTicks = 40;
            }
            case "time" -> {
                long t = Long.parseLong(s[1]);
                server.execute(() -> server.overworld().setDayTime(t));
                waitTicks = 6;
            }
            case "weather" -> {
                boolean rain = s[1].equals("rain");
                server.execute(() -> server.overworld().setWeatherParameters(rain ? 0 : 6000, rain ? 6000 : 0, rain, false));
                waitTicks = 120;
            }
            case "far" -> {
                FarSettings.enabled = s[1].equals("on");
                waitTicks = 4;
            }
            case "set" -> {
                switch (s[1]) {
                    case "distance" -> FarSettings.distance = Integer.parseInt(s[2]);
                    case "quality" -> FarSettings.quality = FarSettings.Quality.valueOf(s[2]);
                    case "rd" -> mc.options.renderDistance().set(Integer.parseInt(s[2]));
                    case "hud" -> FarSettings.showHud = Boolean.parseBoolean(s[2]);
                    case "threads" -> {
                        FarSettings.threads = Integer.parseInt(s[2]);
                        FarClient.reopen();
                    }
                    default -> log("unknown setting " + s[1]);
                }
                if (FarClient.world() != null) FarClient.world().touch();
                waitTicks = 4;
            }
            case "seedreport" -> {
                var w = FarClient.world();
                if (w == null || w.seed == null) {
                    log("no seed generator: " + (w == null ? "no world" : w.seedNote));
                } else {
                    log("seed speed: " + w.seed.speedReport(Integer.parseInt(s[1])));
                }
            }
            case "seedstress" -> {
                var w = FarClient.world();
                if (w != null && w.seed != null) {
                    try {
                        log("seed stress: " + w.seed.stressReport(Integer.parseInt(s[1]), Integer.parseInt(s[2])));
                    } catch (InterruptedException e) {
                        log("stress interrupted");
                    }
                }
            }
            case "fillarea" -> { // fillarea blockX blockZ halfWidthInChunks y block: a big flat plate over real terrain, like a terrain plugin or WorldEdit
                int bx = Integer.parseInt(s[1]), bz = Integer.parseInt(s[2]), half = Integer.parseInt(s[3]), y = Integer.parseInt(s[4]);
                var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new ResourceLocation(s[5])).defaultBlockState();
                server.execute(() -> {
                    long start = System.nanoTime();
                    ServerLevel l = server.overworld();
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    int chunks = 0;
                    for (int cz = (bz >> 4) - half; cz <= (bz >> 4) + half; cz++) {
                        for (int cx = (bx >> 4) - half; cx <= (bx >> 4) + half; cx++) {
                            var chunk = l.getChunk(cx, cz);
                            chunks++;
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    pos.set(cx * 16 + x, y, cz * 16 + z);
                                    chunk.setBlockState(pos, block, false);
                                }
                            }
                            chunk.setUnsaved(true);
                        }
                    }
                    log("filled " + chunks + " chunks at y " + y + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
                });
                waitTicks = 20;
            }
            case "saveall" -> {
                server.execute(() -> {
                    server.saveEverything(false, true, true);
                    log("saved everything");
                });
                waitTicks = 40;
            }
            case "settle" -> { // settle maxSeconds [scan|seed]: until far view has nothing left to load and the real chunks are built
                waiting = s;
                waitStart = System.nanoTime();
            }
            case "wait" -> waitTicks = Integer.parseInt(s[1]);
            case "sinceworld" -> log("now " + String.format("%.1f", (System.nanoTime() - createdNanos) / 1e9) + " s since the world was asked for");
            case "shot" -> {
                String name = s[1] + ".png";
                Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
                log("shot " + name + " at " + (int) mc.player.getX() + " " + (int) mc.player.getY() + " " + (int) mc.player.getZ());
                for (String line : FarClient.status()) log(line);
                waitTicks = 5;
            }
            case "fps" -> { // fps name seconds
                measuring = true;
                measureName = s[1];
                FRAMES.clear();
                lastFrame = 0;
                measureEnd = System.nanoTime() + Long.parseLong(s[2]) * 1_000_000_000L;
            }
            case "screen" -> {
                mc.setScreen(s[1].equals("settings") ? new FarScreen(null) : null);
                waitTicks = 10;
            }
            case "log" -> {
                for (String line : FarClient.status()) log(line);
            }
            default -> log("unknown step " + s[0]);
        }
    }

    private static boolean waitDone(Minecraft mc) {
        double max = Double.parseDouble(waiting[1]);
        double passed = (System.nanoTime() - waitStart) / 1e9;
        if (passed >= max) {
            log("settle gave up after " + (int) passed + " s");
            return true;
        }
        if (passed < 2) return false;
        var w = FarClient.world();
        boolean far = !FarSettings.enabled || (w != null && w.queuedTiles() == 0 && w.uploads.isEmpty());
        boolean real = mc.levelRenderer.countRenderedChunks() > 0 && mc.levelRenderer.hasRenderedAllChunks();
        String extra = waiting.length < 3 ? "" : waiting[2];
        boolean scan = !extra.equals("scan") || w == null || w.scanner == null || w.scanner.regionsLeft == 0;
        boolean seed = !extra.equals("seed") || w == null || w.seed == null || w.seed.idle();
        if (far && real && scan && seed) {
            log("settled in " + (int) passed + " s (" + String.format("%.1f", (System.nanoTime() - createdNanos) / 1e9) + " s since the world was asked for)");
            return true;
        }
        return false;
    }

    private static void finishMeasure() {
        measuring = false;
        if (FRAMES.isEmpty()) return;
        long[] f = FRAMES.stream().mapToLong(Long::longValue).sorted().toArray();
        double sum = 0;
        for (long v : f) sum += v;
        double avg = sum / f.length / 1e6;
        double p99 = f[(int) (f.length * 0.99)] / 1e6;
        double p50 = f[f.length / 2] / 1e6;
        Runtime rt = Runtime.getRuntime();
        log(String.format("FPS %s: %.1f fps (frame %.2f ms avg, %.2f median, %.2f at 99%%), %d frames, heap %d MB", measureName, 1000.0 / avg, avg, p50, p99, f.length, (rt.totalMemory() - rt.freeMemory()) / 1048576L));
        for (String s : FarClient.status()) log(s);
    }
}
