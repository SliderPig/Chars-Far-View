package com.example.charlod.net;

import com.example.charlod.CharLod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

// Only used when the mod is on both the server and your PC, and the server's owner has switched sharing on. Everyone else never sees any of this.
public final class FarNet {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(CharLod.MODID, "far"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    private FarNet() {}

    public record Hello(String dim) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(dim);
        }

        static Hello read(FriendlyByteBuf b) {
            return new Hello(b.readUtf());
        }
    }

    public record Welcome(String dim, boolean sharing) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(dim);
            b.writeBoolean(sharing);
        }

        static Welcome read(FriendlyByteBuf b) {
            return new Welcome(b.readUtf(), b.readBoolean());
        }
    }

    public record ListAsk(String dim, int blockX, int blockZ, int radius) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(dim);
            b.writeInt(blockX);
            b.writeInt(blockZ);
            b.writeInt(radius);
        }

        static ListAsk read(FriendlyByteBuf b) {
            return new ListAsk(b.readUtf(), b.readInt(), b.readInt(), b.readInt());
        }
    }

    public record ListReply(String dim, int[] rx, int[] rz, long[] time) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(dim);
            b.writeVarInt(rx.length);
            for (int i = 0; i < rx.length; i++) {
                b.writeInt(rx[i]);
                b.writeInt(rz[i]);
                b.writeLong(time[i]);
            }
        }

        static ListReply read(FriendlyByteBuf b) {
            String dim = b.readUtf();
            int n = b.readVarInt();
            int[] rx = new int[n], rz = new int[n];
            long[] time = new long[n];
            for (int i = 0; i < n; i++) {
                rx[i] = b.readInt();
                rz[i] = b.readInt();
                time[i] = b.readLong();
            }
            return new ListReply(dim, rx, rz, time);
        }
    }

    public record RegionAsk(String dim, int rx, int rz) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(dim);
            b.writeInt(rx);
            b.writeInt(rz);
        }

        static RegionAsk read(FriendlyByteBuf b) {
            return new RegionAsk(b.readUtf(), b.readInt(), b.readInt());
        }
    }

    public record RegionPart(String dim, int rx, int rz, boolean last, long time, byte[] data) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(dim);
            b.writeInt(rx);
            b.writeInt(rz);
            b.writeBoolean(last);
            b.writeLong(time);
            b.writeByteArray(data);
        }

        static RegionPart read(FriendlyByteBuf b) {
            return new RegionPart(b.readUtf(), b.readInt(), b.readInt(), b.readBoolean(), b.readLong(), b.readByteArray(1 << 20));
        }
    }

    private static boolean toClient(Supplier<NetworkEvent.Context> c) {
        return c.get().getDirection() == NetworkDirection.PLAY_TO_CLIENT;
    }

    private static void onClient(Supplier<NetworkEvent.Context> c, Runnable what) {
        NetworkEvent.Context ctx = c.get();
        ctx.enqueueWork(() -> DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> what::run));
        ctx.setPacketHandled(true);
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Hello.class, Hello::write, Hello::read, (m, c) -> {
            ServerPlayer p = c.get().getSender();
            c.get().enqueueWork(() -> FarServer.onHello(p, m));
            c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, Welcome.class, Welcome::write, Welcome::read, (m, c) -> {
            c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSide.welcome(m)));
            c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ListAsk.class, ListAsk::write, ListAsk::read, (m, c) -> {
            ServerPlayer p = c.get().getSender();
            c.get().enqueueWork(() -> FarServer.onList(p, m));
            c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ListReply.class, ListReply::write, ListReply::read, (m, c) -> {
            c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSide.list(m)));
            c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, RegionAsk.class, RegionAsk::write, RegionAsk::read, (m, c) -> {
            ServerPlayer p = c.get().getSender();
            c.get().enqueueWork(() -> FarServer.onRegion(p, m));
            c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, RegionPart.class, RegionPart::write, RegionPart::read, (m, c) -> {
            c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSide.part(m)));
            c.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void registerServerSide() {
        FarServer.init();
    }

    static void send(ServerPlayer p, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), message);
    }

    // Kept apart so a server never loads any client class.
    private static final class ClientSide {
        static void welcome(Welcome m) {
            com.example.charlod.client.RemoteFeed.onWelcome(m);
        }

        static void list(ListReply m) {
            com.example.charlod.client.RemoteFeed.onList(m);
        }

        static void part(RegionPart m) {
            com.example.charlod.client.RemoteFeed.onPart(m);
        }
    }
}
