package com.example.charlod.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

// A tiny texture, one pixel per chunk, saying where real chunks are drawn. The far terrain cuts itself out there, slightly smaller than the real area,
// so it runs on under the very edge of the real chunks and no gap can show between them. Anything not surely drawn stays far terrain.
final class HoleMask {
    static final int SIZE = 80;
    private int texture;
    private ByteBuffer pixels;
    private int originX, originZ;
    private long lastUpdate;
    private boolean uploadedOnce;

    void create() {
        texture = GL11.glGenTextures();
        pixels = MemoryUtil.memCalloc(SIZE * SIZE);
    }

    int texture() {
        return texture;
    }

    float originBlockX() {
        return originX * 16f;
    }

    float originBlockZ() {
        return originZ * 16f;
    }

    // originBlock is only used relative to the camera, as double maths on the caller's side
    int originChunkX() {
        return originX;
    }

    int originChunkZ() {
        return originZ;
    }

    void update(Minecraft mc, double camX, double camY, double camZ, int unit) {
        long now = System.currentTimeMillis();
        int cx = (int) Math.floor(camX) >> 4, cz = (int) Math.floor(camZ) >> 4;
        int nx = cx - SIZE / 2, nz = cz - SIZE / 2;
        boolean moved = nx != originX || nz != originZ;
        if (!moved && uploadedOnce && now - lastUpdate < 200) return;
        lastUpdate = now;
        originX = nx;
        originZ = nz;
        int rd = Math.min(mc.options.getEffectiveRenderDistance(), SIZE / 2 - 2);
        // Embeddium only draws chunks whose nearest corner is within the render distance circle. Keep a little inside that.
        double limit = rd * 16.0 - 2.0;
        var source = mc.level.getChunkSource();
        var renderer = mc.levelRenderer;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        MemoryUtil.memSet(pixels, 0);
        for (int z = -rd; z <= rd; z++) {
            for (int x = -rd; x <= rd; x++) {
                int chunkX = cx + x, chunkZ = cz + z;
                double dx = nearest(camX, chunkX * 16.0), dz = nearest(camZ, chunkZ * 16.0);
                if (dx * dx + dz * dz > limit * limit) continue;
                LevelChunk c = source.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (c == null) continue;
                boolean drawn;
                try {
                    int y = c.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
                    pos.set(chunkX * 16 + 8, Math.max(y - 1, mc.level.getMinBuildHeight()), chunkZ * 16 + 8);
                    // Flying high, ground further below you than the render distance isn't drawn however close it is sideways.
                    drawn = Math.abs(camY - y) < limit - 16.0 && renderer.isChunkCompiled(pos);
                } catch (Throwable t) {
                    drawn = false;
                }
                if (drawn) pixels.put((chunkZ - originZ) * SIZE + (chunkX - originX), (byte) 255);
            }
        }
        RenderSystem.activeTexture(GL30.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        if (!uploadedOnce) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, SIZE, SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);
            uploadedOnce = true;
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);
        }
    }

    // True when every chunk of this block square is real, so nothing of it would ever show.
    boolean fullyReal(double minBlockX, double minBlockZ, int size) {
        if (pixels == null) return false;
        int x0 = (int) Math.floor(minBlockX) >> 4, z0 = (int) Math.floor(minBlockZ) >> 4;
        int count = size >> 4;
        for (int z = 0; z < count; z++) {
            int mz = z0 + z - originZ;
            if (mz < 0 || mz >= SIZE) return false;
            for (int x = 0; x < count; x++) {
                int mx = x0 + x - originX;
                if (mx < 0 || mx >= SIZE || pixels.get(mz * SIZE + mx) == 0) return false;
            }
        }
        return true;
    }

    private static double nearest(double cam, double min) {
        return cam < min ? min - cam : cam > min + 16 ? cam - min - 16 : 0;
    }

    void delete() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        texture = 0;
        if (pixels != null) MemoryUtil.memFree(pixels);
        pixels = null;
        uploadedOnce = false;
    }
}
