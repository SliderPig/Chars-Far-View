package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.example.charlod.core.ChunkColumns;
import com.example.charlod.core.ColumnSampler;
import com.example.charlod.core.SurfacePacket;
import com.example.charlod.net.FarNet;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

// On a server that shares chunk surfaces: asks for the nearest regions it doesn't have yet, one at a time, and turns what arrives into columns.
public final class RemoteFeed {
    private static final long ASK_AGAIN_MILLIS = 60_000L, GIVE_UP_MILLIS = 120_000L;

    private final LodWorld world;
    private final String dim;
    private final Path indexFile;
    private final Map<String, Long> done = new HashMap<>();
    private final ArrayDeque<int[]> todo = new ArrayDeque<>();
    private final Map<String, Long> times = new HashMap<>();
    private final ConcurrentLinkedQueue<FarNet.RegionPart> parts = new ConcurrentLinkedQueue<>();
    private boolean said, sharing, loaded;
    private long lastList, lastAsk;
    private int[] asked;
    private double listX = Double.NaN, listZ;
    volatile int regionsLeft;
    volatile long chunksReceived, bytesReceived;
    volatile String status = "waiting";

    RemoteFeed(LodWorld world, String dim, Path indexFile) {
        this.world = world;
        this.dim = dim;
        this.indexFile = indexFile;
    }

    private static RemoteFeed current() {
        LodWorld w = FarClient.world();
        return w == null ? null : w.remote;
    }

    // ---- client thread

    public static void onWelcome(FarNet.Welcome m) {
        RemoteFeed f = current();
        if (f == null || !m.dim().equals(f.dim)) return;
        f.sharing = m.sharing();
        f.status = m.sharing() ? "server shares its terrain" : "server has sharing off";
    }

    public static void onList(FarNet.ListReply m) {
        RemoteFeed f = current();
        if (f == null || !m.dim().equals(f.dim)) return;
        f.loadIndex();
        f.todo.clear();
        for (int i = 0; i < m.rx().length; i++) {
            String name = name(m.rx()[i], m.rz()[i]);
            Long known = f.done.get(name);
            if (known != null && known == m.time()[i]) continue;
            f.times.put(name, m.time()[i]);
            f.todo.add(new int[]{m.rx()[i], m.rz()[i]});
        }
        f.regionsLeft = f.todo.size();
    }

    public static void onPart(FarNet.RegionPart m) {
        RemoteFeed f = current();
        if (f == null || !m.dim().equals(f.dim)) return;
        f.parts.add(m);
        f.bytesReceived += m.data().length;
    }

    private static String name(int rx, int rz) {
        return "r." + rx + "." + rz + ".mca";
    }

    void tick(Minecraft mc) {
        if (!FarSettings.askServer || !FarSettings.enabled || mc.player == null || mc.getConnection() == null) return;
        if (!said) {
            said = true;
            if (!FarNet.CHANNEL.isRemotePresent(mc.getConnection().getConnection())) {
                status = "server doesn't have the mod";
                return;
            }
            FarNet.CHANNEL.sendToServer(new FarNet.Hello(dim));
            return;
        }
        if (!sharing) return;
        long now = System.currentTimeMillis();
        if (asked != null && now - lastAsk > GIVE_UP_MILLIS) asked = null;
        boolean moved = Double.isNaN(listX) || Math.abs(mc.player.getX() - listX) > 256 || Math.abs(mc.player.getZ() - listZ) > 256;
        if (asked == null && todo.isEmpty() && (moved || now - lastList > ASK_AGAIN_MILLIS)) {
            lastList = now;
            listX = mc.player.getX();
            listZ = mc.player.getZ();
            FarNet.CHANNEL.sendToServer(new FarNet.ListAsk(dim, (int) listX, (int) listZ, FarSettings.distance));
            return;
        }
        if (asked == null && !todo.isEmpty() && parts.isEmpty()) {
            asked = todo.poll();
            regionsLeft = todo.size();
            lastAsk = now;
            status = "getting " + name(asked[0], asked[1]);
            FarNet.CHANNEL.sendToServer(new FarNet.RegionAsk(dim, asked[0], asked[1]));
        }
    }

    // ---- worker thread

    // Handles one packet. Returns false if there was none.
    boolean process(ColorTable.View table, ChunkColumns columns) throws IOException {
        FarNet.RegionPart part = parts.poll();
        if (part == null) return false;
        if (part.data().length > 0) {
            SurfacePacket.read(part.data(), world.colors, (x, z, hits) -> {
                for (int i = 0; i < SurfacePacket.COLUMNS; i++) ColumnSampler.colour(hits[i], table, i, columns);
                world.store().putChunk(x, z, columns);
                chunksReceived++;
            });
        }
        if (part.last()) {
            world.flushNow();
            mark(name(part.rx(), part.rz()), part.time());
            Minecraft.getInstance().execute(() -> asked = null);
            status = regionsLeft == 0 ? "up to date" : "getting terrain from the server";
        }
        return true;
    }

    private synchronized void loadIndex() {
        if (loaded) return;
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
            CharLod.LOG.warn("[Far View] couldn't read server notes: {}", e.toString());
        }
    }

    private synchronized void mark(String name, long time) {
        done.put(name, time);
        try {
            Files.writeString(indexFile, name + " " + time + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CharLod.LOG.warn("[Far View] couldn't note region: {}", e.toString());
        }
    }

    static List<String> names() {
        return new ArrayList<>();
    }
}
