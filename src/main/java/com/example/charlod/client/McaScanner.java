package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.example.charlod.core.ChunkColumns;
import com.example.charlod.core.ColumnSampler;
import com.example.charlod.core.McaChunk;
import com.example.charlod.core.McaFile;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

// On your own world: goes through the region files on disk, nearest first, a chunk at a time, without the game loading anything.
// Remembers which region files it has been through (and when they last changed) so it never does the same work twice.
public final class McaScanner {
    private final LodWorld world;
    private final Path regionDir, indexFile;
    private final Map<String, Long> done = new HashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final McaChunk chunk = new McaChunk();
    private final ChunkColumns columns = new ChunkColumns();

    private List<Path> plan = List.of();
    private int planIndex;
    private double planX = Double.NaN, planZ;
    private int planDistance;
    private long planTime;
    private McaFile current;
    private Path currentPath;
    private int index;
    private boolean loaded;
    private long scanStart;

    public volatile int regionsLeft, regionsTotal;
    volatile long chunksRead, skippedChunks, nanosReading;
    volatile String status = "starting";

    McaScanner(LodWorld world, Path regionDir, Path indexFile) {
        this.world = world;
        this.regionDir = regionDir;
        this.indexFile = indexFile;
    }

    // Does one chunk's work if there is any. Returns false when there is nothing to do (or another worker is on it).
    boolean step(ColorTable.View table) {
        if (!busy.compareAndSet(false, true)) return false;
        try {
            return stepLocked(table);
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] region file trouble: {}", e.toString());
            closeCurrent();
            return false;
        } finally {
            busy.set(false);
        }
    }

    private boolean stepLocked(ColorTable.View table) throws IOException {
        if (!loaded) loadIndex();
        if (current == null && !nextRegion()) return false;
        while (index < 1024 && !current.present(index)) index++;
        if (index >= 1024) {
            finishRegion();
            return true;
        }
        long start = System.nanoTime();
        int i = index++;
        if (!current.read(i) || !chunk.parse(current.data(), current.dataLength(), world.colors) || !chunk.surfaceDone || !chunk.hasSections()) {
            skippedChunks++;
            return true;
        }
        ColumnSampler.sample(chunk, table, world.ceilingTop, columns);
        world.checkTruth(chunk.chunkX, chunk.chunkZ, columns);
        world.store().putChunk(chunk.chunkX, chunk.chunkZ, columns);
        chunksRead++;
        nanosReading += System.nanoTime() - start;
        return true;
    }

    private boolean nextRegion() throws IOException {
        double cx = world.camX, cz = world.camZ;
        long now = System.currentTimeMillis();
        boolean moved = Double.isNaN(planX) || Math.abs(cx - planX) > 256 || Math.abs(cz - planZ) > 256;
        boolean stale = planDistance != FarSettings.distance || now - planTime > 30_000;
        if (moved || stale) makePlan(cx, cz, now);
        while (planIndex < plan.size()) {
            Path p = plan.get(planIndex++);
            regionsLeft = plan.size() - planIndex;
            Long known = done.get(p.getFileName().toString());
            long time;
            try {
                time = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException gone) {
                continue;
            }
            if (known != null && known == time) continue;
            if (scanStart == 0) scanStart = System.nanoTime();
            current = new McaFile(p);
            currentPath = p;
            index = 0;
            status = "reading " + p.getFileName();
            return true;
        }
        regionsLeft = 0;
        status = "up to date";
        if (scanStart != 0) {
            CharLod.LOG.info("[Far View] went through {} chunks of the save files in {} s ({} ms of reading each), {} MB of far terrain on disk",
                    chunksRead, (System.nanoTime() - scanStart) / 1_000_000_000L, String.format("%.2f", chunksRead == 0 ? 0.0 : nanosReading / 1e6 / chunksRead), world.store().bytesOnDisk() / 1048576L);
            scanStart = 0;
        }
        return false;
    }

    private void makePlan(double cx, double cz, long now) throws IOException {
        planX = cx;
        planZ = cz;
        planTime = now;
        planDistance = FarSettings.distance;
        planIndex = 0;
        List<Path> found = new ArrayList<>();
        Map<Path, Double> distanceOf = new HashMap<>();
        if (Files.isDirectory(regionDir)) {
            double reach = planDistance + 724.0;
            try (Stream<Path> files = Files.list(regionDir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String[] parts = p.getFileName().toString().split("\\.");
                    if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) continue;
                    try {
                        double rx = (Integer.parseInt(parts[1]) + 0.5) * 512, rz = (Integer.parseInt(parts[2]) + 0.5) * 512;
                        double d = Math.hypot(rx - cx, rz - cz);
                        if (d > reach) continue;
                        Long known = done.get(p.getFileName().toString());
                        if (known != null) {
                            long time = Files.getLastModifiedTime(p).toMillis();
                            if (known == time) continue;
                            // A region with chunks being played in right now changes all the time, and what you see is taken live anyway: leave it until you've gone.
                            // Anything else changed since we read it (a plugin, WorldEdit, an old save put back) gets read again once it has settled.
                            double rd = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
                            if (d < rd + 362 || now - time < 60_000) continue;
                        }
                        found.add(p);
                        distanceOf.put(p, d);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(distanceOf::get));
        plan = found;
        regionsTotal = found.size();
        regionsLeft = found.size();
    }

    private void finishRegion() {
        Path p = currentPath;
        closeCurrent();
        world.flushNow();
        try {
            long time = Files.getLastModifiedTime(p).toMillis();
            String name = p.getFileName().toString();
            done.put(name, time);
            Files.writeString(indexFile, name + " " + time + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] couldn't note region as done: {}", e.toString());
        }
    }

    private void closeCurrent() {
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        current = null;
        currentPath = null;
    }

    private void loadIndex() {
        loaded = true;
        if (!Files.exists(indexFile)) return;
        try {
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                int cut = line.indexOf(' ');
                if (cut <= 0) continue;
                try {
                    done.put(line.substring(0, cut), Long.parseLong(line.substring(cut + 1).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] couldn't read scan notes: {}", e.toString());
        }
    }

    void forgetAll() {
        done.clear();
        planX = Double.NaN;
        try {
            Files.deleteIfExists(indexFile);
        } catch (IOException ignored) {
        }
    }

    void close() {
        closeCurrent();
    }
}
