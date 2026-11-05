package com.example.charlod.client;

import com.example.charlod.CharLod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// config/charlod.properties
public final class FarSettings {
    public enum Quality {
        LOW("Low", 64), BALANCED("Balanced", 96), HIGH("High", 128);

        public final String label;
        public final double ratio; // a tile splits nearer than this many times its column size: bigger = finer

        Quality(String label, double ratio) {
            this.label = label;
            this.ratio = ratio;
        }
    }

    public static final int[] DISTANCES = {1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384};
    public static final int[] VRAM_STEPS = {64, 96, 128, 192, 256, 384, 512};
    public static final int[] MEMORY_STEPS = {32, 48, 64, 96, 128, 192, 256};

    public static boolean enabled = true;
    public static int distance = 2048;
    public static Quality quality = Quality.BALANCED;
    public static int threads = 2;
    public static boolean useGeneratedChunks = true;
    public static boolean seedGenerate = true;
    public static boolean askServer = true;
    public static boolean showHud = false;
    public static int memoryMb = 64;
    public static int vramMb = 256;

    private FarSettings() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("charlod.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] couldn't read settings: {}", e.toString());
            return;
        }
        enabled = bool(p, "enabled", enabled);
        distance = clamp(integer(p, "distance", distance), 256, 32768);
        threads = clamp(integer(p, "threads", threads), 1, 4);
        useGeneratedChunks = bool(p, "useGeneratedChunks", useGeneratedChunks);
        seedGenerate = bool(p, "seedGenerate", seedGenerate);
        askServer = bool(p, "askServer", askServer);
        showHud = bool(p, "showHud", showHud);
        memoryMb = clamp(integer(p, "memoryMb", memoryMb), 16, 1024);
        vramMb = clamp(integer(p, "vramMb", vramMb), 32, 2048);
        try {
            quality = Quality.valueOf(p.getProperty("quality", quality.name()));
        } catch (IllegalArgumentException ignored) {
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(enabled));
        p.setProperty("distance", String.valueOf(distance));
        p.setProperty("quality", quality.name());
        p.setProperty("threads", String.valueOf(threads));
        p.setProperty("useGeneratedChunks", String.valueOf(useGeneratedChunks));
        p.setProperty("seedGenerate", String.valueOf(seedGenerate));
        p.setProperty("askServer", String.valueOf(askServer));
        p.setProperty("showHud", String.valueOf(showHud));
        p.setProperty("memoryMb", String.valueOf(memoryMb));
        p.setProperty("vramMb", String.valueOf(vramMb));
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            p.store(w, "Char's Far View");
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] couldn't save settings: {}", e.toString());
        }
    }

    // One tile in memory is about 41 KB.
    public static int maxCachedTiles() {
        return Math.max(64, memoryMb * 1024 / 41);
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String k, int d) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
