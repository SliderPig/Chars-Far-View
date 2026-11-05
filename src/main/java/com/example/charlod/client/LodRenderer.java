package com.example.charlod.client;

import com.example.charlod.CharLod;
import com.example.charlod.core.Frustum;
import com.example.charlod.core.GreedyMesher;
import com.example.charlod.core.LodMath;
import com.example.charlod.core.Selector;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

// Draws the far terrain right after the sky, with its own far-reaching view, then wipes the depth buffer: the real chunks then simply paint over it.
public final class LodRenderer {
    private static final int MASK_UNIT = 7;
    private static final float NEAR = 4f;
    private static final long UPLOAD_BUDGET_NANOS = 1_500_000L;

    private static final class Program {
        int id, mvp, origin, scale, light, glow, fogColor, fog, mask, maskOrigin, maskScale;

        void find() {
            mvp = GL20.glGetUniformLocation(id, "uMvp");
            origin = GL20.glGetUniformLocation(id, "uOrigin");
            scale = GL20.glGetUniformLocation(id, "uScale");
            light = GL20.glGetUniformLocation(id, "uLight");
            glow = GL20.glGetUniformLocation(id, "uGlow");
            fogColor = GL20.glGetUniformLocation(id, "uFogColor");
            fog = GL20.glGetUniformLocation(id, "uFog");
            mask = GL20.glGetUniformLocation(id, "uMask");
            maskOrigin = GL20.glGetUniformLocation(id, "uMaskOrigin");
            maskScale = GL20.glGetUniformLocation(id, "uMaskScale");
        }
    }

    private final Program near = new Program(), far = new Program();
    private final HoleMask mask = new HoleMask();
    private int quadIndices;
    private boolean ready;
    private boolean broken;
    private String brokenWhy;

    private final List<Tile> selected = new ArrayList<>();
    private final Selector selector;
    private LodWorld world;
    private final Frustum frustum = new Frustum();
    private final Matrix4f mvp = new Matrix4f();
    private final Matrix4f zFix = new Matrix4f();
    private final float[] mvpArray = new float[16];
    private double lastSelectX = Double.NaN, lastSelectY, lastSelectZ;
    private long lastSelectTime;
    private long lastHouseKeeping;
    private double pressure = 1.0;
    private long gpuBytes;
    private int frameDrawn, frameQuads;
    private double smoothDrawMicros, smoothUploadMicros;
    private NativeImage lightPixels;
    private boolean lightSearched;
    private int checkedErrors;
    private float coverage;

    public LodRenderer() {
        selector = new Selector(new Selector.Tiles() {
            @Override
            public int state(long key) {
                Tile t = world.tiles.get(key);
                if (t == null) return Selector.UNKNOWN;
                t.lastUsed = System.currentTimeMillis();
                return t.state;
            }

            @Override
            public void want(long key, double priority) {
                world.want(key, priority);
            }

            @Override
            public void draw(long key, double distance) {
                Tile t = world.tiles.get(key);
                if (t != null && t.vao != 0) {
                    t.distance = distance;
                    selected.add(t);
                }
            }
        });
    }

    public boolean broken() {
        return broken;
    }

    public void render(LodWorld w, RenderLevelStageEvent event, boolean show) {
        if (broken) return;
        if (world != w) {
            world = w;
            selected.clear();
            lastSelectX = Double.NaN;
            pressure = 1.0;
        }
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int prevActive = GlStateManager._getActiveTexture();
        try {
            if (!ready) setup();
            draw(event, show);
            if (checkedErrors < 5) {
                checkedErrors++;
                int err = GL11.glGetError();
                if (err != 0) CharLod.LOG.warn("[Far View] OpenGL said {} after drawing", err);
            }
        } catch (Throwable t) {
            fail("drawing went wrong", t);
        } finally {
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
            RenderSystem.activeTexture(GL30.GL_TEXTURE0 + MASK_UNIT);
            RenderSystem.bindTexture(0);
            RenderSystem.activeTexture(prevActive);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private void fail(String what, Throwable t) {
        broken = true;
        brokenWhy = what;
        CharLod.LOG.error("[Far View] {}; switching myself off until the game restarts", what, t);
        FarClient.problem(what);
    }

    private void setup() throws IOException {
        compile(near, true);
        compile(far, false);
        mask.create();
        quadIndices = GL15.glGenBuffers();
        int quads = GreedyMesher.MAX_VERTS / 4;
        ShortBuffer indices = MemoryUtil.memAllocShort(quads * 6);
        for (int q = 0; q < quads; q++) {
            int v = q * 4;
            indices.put((short) v).put((short) (v + 1)).put((short) (v + 2)).put((short) (v + 2)).put((short) (v + 3)).put((short) v);
        }
        indices.flip();
        // Filled through the plain buffer slot: the element slot belongs to whichever vertex array is bound right now.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadIndices);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(indices);
        ready = true;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = LodRenderer.class.getResourceAsStream("/assets/charlod/shaders/" + name)) {
            if (in == null) throw new IOException("missing shader " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int shader(int type, String source) throws IOException {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(s, 2000);
            GL20.glDeleteShader(s);
            throw new IOException("shader failed: " + log);
        }
        return s;
    }

    private void compile(Program p, boolean hole) throws IOException {
        String vert = resource("far.vert");
        String frag = resource("far.frag");
        if (hole) frag = frag.replaceFirst("#version 150\\r?\\n", "#version 150\n#define HOLE\n");
        int vs = shader(GL20.GL_VERTEX_SHADER, vert), fs = shader(GL20.GL_FRAGMENT_SHADER, frag);
        p.id = GL20.glCreateProgram();
        GL20.glAttachShader(p.id, vs);
        GL20.glAttachShader(p.id, fs);
        GL20.glBindAttribLocation(p.id, 0, "aXZ");
        GL20.glBindAttribLocation(p.id, 1, "aY");
        GL20.glBindAttribLocation(p.id, 2, "aColor");
        GL30.glBindFragDataLocation(p.id, 0, "fragColor");
        GL20.glLinkProgram(p.id);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p.id, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) throw new IOException("link failed: " + GL20.glGetProgramInfoLog(p.id, 2000));
        p.find();
    }

    private void upload(Tile t, Tile.Pending m) {
        if (t.vao == 0) {
            t.vao = GL30.glGenVertexArrays();
            t.vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(t.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, t.vbo);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, quadIndices);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_UNSIGNED_BYTE, false, GreedyMesher.VERTEX_BYTES, 0L);
            GL20.glVertexAttribPointer(1, 1, GL11.GL_SHORT, false, GreedyMesher.VERTEX_BYTES, 2L);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, GreedyMesher.VERTEX_BYTES, 4L);
        } else {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, t.vbo);
        }
        ByteBuffer buf = MemoryUtil.memAlloc(m.bytes().length);
        buf.put(m.bytes()).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);
        gpuBytes += m.bytes().length - t.gpuBytes;
        t.gpuBytes = m.bytes().length;
        t.indexCount = m.vertices() / 4 * 6;
        t.minY = m.minY();
        t.maxY = m.maxY();
        t.occupied = m.occupied();
        t.state = Selector.READY;
        world.selectionDirty = true;
    }

    private void free(Tile t) {
        if (t.vao != 0) {
            GL30.glDeleteVertexArrays(t.vao);
            GL15.glDeleteBuffers(t.vbo);
            gpuBytes -= t.gpuBytes;
        }
        t.vao = 0;
        t.vbo = 0;
        t.gpuBytes = 0;
        t.indexCount = 0;
    }

    // Dropping a whole world: called when leaving it.
    public void forget(LodWorld w) {
        if (w == null) return;
        for (Tile t : w.tiles.values()) free(t);
        w.tiles.clear();
        w.uploads.clear();
        if (world == w) {
            world = null;
            selected.clear();
        }
    }

    public void shutdown() {
        if (!ready) return;
        GL20.glDeleteProgram(near.id);
        GL20.glDeleteProgram(far.id);
        GL15.glDeleteBuffers(quadIndices);
        mask.delete();
        ready = false;
    }

    private void draw(RenderLevelStageEvent event, boolean show) {
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        var pos = camera.getPosition();
        double cx = pos.x, cy = pos.y, cz = pos.z;
        world.camX = cx;
        world.camY = cy;
        world.camZ = cz;

        // new meshes, a little per frame
        long uploadStart = System.nanoTime();
        Tile up;
        int uploaded = 0;
        while ((up = world.uploads.peek()) != null) {
            if (uploaded > 0 && System.nanoTime() - uploadStart > UPLOAD_BUDGET_NANOS) break;
            world.uploads.poll();
            Tile.Pending m = up.pending;
            up.pending = null;
            if (m != null && world.tiles.get(up.key) == up) {
                upload(up, m);
                uploaded++;
            }
        }
        long uploadNanos = System.nanoTime() - uploadStart;

        long now = System.currentTimeMillis();
        boolean moved = Double.isNaN(lastSelectX) || Math.abs(cx - lastSelectX) + Math.abs(cy - lastSelectY) + Math.abs(cz - lastSelectZ) > 6;
        if (moved || world.selectionDirty || now - lastSelectTime > 500) {
            lastSelectX = cx;
            lastSelectY = cy;
            lastSelectZ = cz;
            lastSelectTime = now;
            world.selectionDirty = false;
            selected.clear();
            selector.select(cx, cy, cz, FarSettings.distance, FarSettings.quality.ratio * pressure);
            selected.sort(Comparator.comparingDouble(t -> t.distance));
        }
        if (now - lastHouseKeeping > 1000) {
            lastHouseKeeping = now;
            houseKeeping(now);
            measureCoverage(cx, cz, mc.options.getEffectiveRenderDistance() * 16f);
        }

        if (!show) {
            frameDrawn = 0;
            frameQuads = 0;
            return;
        }

        // our own view: same as the game's, but it reaches much further
        float farPlane = FarSettings.distance * 1.5f + 2048f;
        float vanillaNear = 0.05f, vanillaFar = mc.gameRenderer.getDepthFar();
        float a = (vanillaFar + vanillaNear) / (vanillaNear - vanillaFar), b = 2f * vanillaFar * vanillaNear / (vanillaNear - vanillaFar);
        float a2 = (farPlane + NEAR) / (NEAR - farPlane), b2 = 2f * farPlane * NEAR / (NEAR - farPlane);
        zFix.identity().m22(b2 / b).m32(b2 * a / b - a2);
        mvp.set(zFix).mul(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        mvp.get(mvpArray);
        frustum.set(mvpArray);

        mask.update(mc, cx, cz, MASK_UNIT);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        float[] fog = RenderSystem.getShaderFogColor();
        float[] fogRange = FarClient.fogFor(mc);
        float[] sky = lightColours(mc);

        frameDrawn = 0;
        frameQuads = 0;
        Program current = null;
        boolean blending = false;
        float rd = mc.options.getEffectiveRenderDistance() * 16f;
        float fadeFrom = fogRange[1] * 0.7f;
        for (int i = 0, n = selected.size(); i < n; i++) {
            Tile t = selected.get(i);
            int level = LodMath.level(t.key);
            int size = LodMath.tileBlocks(level);
            double wx = (double) LodMath.tx(t.key) * size, wz = (double) LodMath.tz(t.key) * size;
            float rx = (float) (wx - cx), rz = (float) (wz - cz);
            if (t.vao == 0 || !frustum.visible(rx, (float) (t.minY - cy), rz, rx + size, (float) (t.maxY - cy), rz + size)) continue;
            float hx = rx > 0 ? rx : rx + size < 0 ? -(rx + size) : 0, hz = rz > 0 ? rz : rz + size < 0 ? -(rz + size) : 0;
            boolean overlapsHole = hx * hx + hz * hz < (rd + 32f) * (rd + 32f);
            if (overlapsHole && size <= 256 && mask.fullyReal(wx, wz, size)) continue;
            Program want = overlapsHole ? near : far;
            if (want != current) {
                current = want;
                GL20.glUseProgram(want.id);
                GL20.glUniformMatrix4fv(want.mvp, false, mvpArray);
                GL20.glUniform3f(want.light, sky[0], sky[1], sky[2]);
                GL20.glUniform3f(want.glow, sky[3], sky[4], sky[5]);
                GL20.glUniform3f(want.fogColor, fog[0], fog[1], fog[2]);
                GL20.glUniform2f(want.fog, fogRange[0], fogRange[1]);
                if (want == near) {
                    RenderSystem.activeTexture(GL30.GL_TEXTURE0 + MASK_UNIT);
                    RenderSystem.bindTexture(mask.texture());
                    GL20.glUniform1i(want.mask, MASK_UNIT);
                    GL20.glUniform2f(want.maskOrigin, (float) (mask.originChunkX() * 16.0 - cx), (float) (mask.originChunkZ() * 16.0 - cz));
                    GL20.glUniform1f(want.maskScale, 1f / (HoleMask.SIZE * 16f));
                }
            }
            boolean needBlend = t.distance + size > fadeFrom;
            if (needBlend != blending) {
                blending = needBlend;
                if (blending) {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                } else {
                    RenderSystem.disableBlend();
                }
            }
            GL20.glUniform3f(want.origin, rx, (float) -cy, rz);
            GL20.glUniform1f(want.scale, LodMath.blocksPerColumn(level));
            GL30.glBindVertexArray(t.vao);
            GL11.glDrawElements(GL11.GL_TRIANGLES, t.indexCount, GL11.GL_UNSIGNED_SHORT, 0L);
            frameDrawn++;
            frameQuads += t.indexCount / 6;
        }
        if (frameDrawn > 0) {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        long total = System.nanoTime() - t0;
        smoothDrawMicros += (total / 1000.0 - smoothDrawMicros) * 0.05;
        smoothUploadMicros += (uploadNanos / 1000.0 - smoothUploadMicros) * 0.05;
    }

    // How much of the ring just past the real chunks has far terrain to show, 0 to 1. Asked about once a second.
    private void measureCoverage(double cx, double cz, float rd) {
        int hits = 0;
        final int samples = 24;
        for (int i = 0; i < samples; i++) {
            double a = i * Math.PI * 2 / samples;
            int x = (int) Math.floor(cx + Math.cos(a) * (rd + 40));
            int z = (int) Math.floor(cz + Math.sin(a) * (rd + 40));
            if (hasDataAt(x, z)) hits++;
        }
        coverage = hits / (float) samples;
    }

    private boolean hasDataAt(int x, int z) {
        // The finest ready tile there knows best.
        boolean found = false;
        for (int level = LodMath.TOP_LEVEL; level >= 0; level--) {
            Tile t = world.tiles.get(LodMath.key(level, LodMath.tileOfBlock(x, level), LodMath.tileOfBlock(z, level)));
            if (t == null || t.state != Selector.READY) break;
            int size = LodMath.tileBlocks(level);
            int cell = (Math.floorMod(z, size) * 8 / size) * 8 + Math.floorMod(x, size) * 8 / size;
            found = (t.occupied >>> cell & 1L) != 0;
            if (!found) break;
        }
        return found;
    }

    public float coverage() {
        return coverage;
    }

    private void houseKeeping(long now) {
        long cap = FarSettings.vramMb * 1024L * 1024L;
        if (gpuBytes > cap * 0.92) pressure = Math.max(0.45, pressure * 0.93);
        else if (gpuBytes < cap * 0.7 && pressure < 1.0) pressure = Math.min(1.0, pressure * 1.02);
        Iterator<Tile> it = world.tiles.values().iterator();
        long unusedFor = gpuBytes > cap * 0.8 ? 3000 : 45_000;
        while (it.hasNext()) {
            Tile t = it.next();
            if (t.queued) continue;
            if (t.state == Selector.EMPTY && t.vao != 0) free(t);
            if (now - t.lastUsed > unusedFor && now - t.lastWanted > unusedFor) {
                if (t.state == Selector.LOADING) continue;
                free(t);
                t.pending = null;
                t.state = Selector.UNKNOWN;
                it.remove();
            }
        }
    }

    // The game's own light map: what daylight looks like right now, and what something glowing looks like.
    private float[] lightColours(Minecraft mc) {
        if (!lightSearched) {
            lightSearched = true;
            try {
                for (Field f : LightTexture.class.getDeclaredFields()) {
                    if (f.getType() == NativeImage.class) {
                        f.setAccessible(true);
                        lightPixels = (NativeImage) f.get(mc.gameRenderer.lightTexture());
                        break;
                    }
                }
            } catch (Throwable t) {
                CharLod.LOG.warn("[Far View] couldn't read the light map: {}", t.toString());
            }
        }
        float[] out = {1f, 1f, 1f, 1f, 1f, 1f};
        if (lightPixels == null) return out;
        boolean sky = mc.level.dimensionType().hasSkyLight();
        int lit = lightPixels.getPixelRGBA(sky ? 0 : 7, sky ? 15 : 0);
        int glow = lightPixels.getPixelRGBA(15, 15);
        out[0] = (lit & 255) / 255f;
        out[1] = ((lit >> 8) & 255) / 255f;
        out[2] = ((lit >> 16) & 255) / 255f;
        out[3] = (glow & 255) / 255f;
        out[4] = ((glow >> 8) & 255) / 255f;
        out[5] = ((glow >> 16) & 255) / 255f;
        return out;
    }

    // ---- numbers for the screen

    public double drawMillis() {
        return smoothDrawMicros / 1000.0;
    }

    public int drawnTiles() {
        return frameDrawn;
    }

    public int drawnQuads() {
        return frameQuads;
    }

    public int selectedTiles() {
        return selected.size();
    }

    public long gpuBytes() {
        return gpuBytes;
    }

    public double pressure() {
        return pressure;
    }

    public String brokenWhy() {
        return brokenWhy;
    }

}
