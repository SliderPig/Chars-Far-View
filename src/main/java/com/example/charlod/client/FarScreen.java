package com.example.charlod.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// The settings. Pink and plum, soft pills, no boxes; everything drawn by hand in real screen pixels.
public final class FarScreen extends Screen {
    private static final int W = 300, H = 270;
    private static final int PANEL = 0xFF2B1A30, WELL = 0xFF1A0F1E, BUTTON = 0xFF4A2F52, HOVER = 0xFF6A4575, PINK = 0xFFFF88C8, TEXT = 0xFFF4E4F0, DIM = 0xFFB8A0B8, DARK = 0xFF2B1A30;

    private record Hit(int x, int y, int w, int h, Runnable what) {}

    private record Slider(int x, int w, int y, int h, int count, IntConsumer pick) {}

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private Slider dragging;
    private GuiGraphics g;
    private double unit;
    private int ox, oy, realX, realY;
    private final int threadsAtOpen = FarSettings.threads;
    private boolean sureRebuild;

    public FarScreen(Screen parent) {
        super(Component.literal("Far View"));
        this.parent = parent;
    }

    private int X(double d) {
        return ox + (int) Math.round(d * unit);
    }

    private int Y(double d) {
        return oy + (int) Math.round(d * unit);
    }

    // The normal Minecraft font, only ever at whole real pixels so it stays sharp.
    private int k(double size) {
        return Math.max(1, (int) Math.round(size * unit));
    }

    private void text(String s, double x, double y, double size, int colour, int align) {
        Component c = Component.literal(s);
        int scale = k(size);
        g.pose().pushPose();
        g.pose().translate(X(x) - (align == 0 ? font.width(c) * scale / 2 : align > 0 ? font.width(c) * scale : 0), Y(y), 0);
        // (only moved by whole pixels, X and Y are already whole)
        g.pose().scale(scale, scale, 1);
        g.drawString(font, c, 0, 0, colour, false);
        g.pose().popPose();
    }

    private void pill(double x, double y, double w, double h, String label, boolean on, Runnable what) {
        int x0 = X(x), y0 = Y(y), x1 = X(x + w), y1 = Y(y + h), r = (y1 - y0) / 2;
        boolean over = realX >= x0 && realX < x1 && realY >= y0 && realY < y1;
        Round.plate(g, x0, y0, x1, y1, r, on ? PINK : over ? HOVER : BUTTON, Math.max(1, (int) Math.round(unit * 1.5)));
        text(label, x + w / 2, y + (h - 8 * k(1) / unit) / 2 - 0.5, 1, on ? DARK : TEXT, 0);
        hits.add(new Hit(x0, y0, x1 - x0, y1 - y0, what));
    }

    private void toggle(double y, String label, String hint, boolean on, Runnable what) {
        text(label, 20, y + 1, 1, TEXT, -1);
        if (hint != null) text(hint, 20, y + 12, 0.75, DIM, -1);
        double w = 34, h = 16, x = W - 20 - w;
        int x0 = X(x), y0 = Y(y), x1 = X(x + w), y1 = Y(y + h), r = (y1 - y0) / 2;
        Round.well(g, x0, y0, x1, y1, r, on ? 0xFFB85A90 : WELL, Math.max(1, (int) Math.round(unit)));
        int knob = (int) Math.round(12 * unit);
        int kx = on ? x1 - knob - (int) Math.round(2 * unit) : x0 + (int) Math.round(2 * unit);
        int ky = y0 + (int) Math.round(2 * unit);
        Round.fill(g, kx, ky + 1, kx + knob, ky + knob + 1, knob / 2, 0x60000000);
        Round.fill(g, kx, ky, kx + knob, ky + knob, knob / 2, on ? PINK : 0xFF9A849C);
        hits.add(new Hit(x0, y0, x1 - x0, y1 - y0, what));
    }

    private void slider(double y, String label, String value, int index, int count, IntConsumer pick) {
        text(label, 20, y, 1, TEXT, -1);
        text(value, W - 20, y, 1, PINK, 1);
        double x = 20, w = W - 40, h = 8, ty = y + 13;
        int x0 = X(x), y0 = Y(ty), x1 = X(x + w), y1 = Y(ty + h), r = (y1 - y0) / 2;
        Round.well(g, x0, y0, x1, y1, r, WELL, Math.max(1, (int) Math.round(unit)));
        float frac = count <= 1 ? 0 : (float) index / (count - 1);
        int knob = (int) Math.round(14 * unit);
        int travel = x1 - x0 - knob;
        int kx = x0 + Math.round(travel * frac);
        int ky = y0 + (y1 - y0) / 2 - knob / 2;
        if (kx > x0) Round.fill(g, x0 + 1, y0 + 1, kx + knob / 2, y1 - 1, Math.max(0, r - 1), 0xFFB85A90);
        Round.fill(g, kx, ky + 1, kx + knob, ky + knob + 1, knob / 2, 0x60000000);
        Round.fill(g, kx, ky, kx + knob, ky + knob, knob / 2, PINK);
        sliders.add(new Slider(x0, x1 - x0 - knob, y0 - knob / 2, y1 - y0 + knob, count, pick));
    }

    // A little black cat sitting beside the title.
    private void cat(double x, double y) {
        int u = Math.max(1, (int) Math.round(unit));
        int x0 = X(x), y0 = Y(y);
        int col = 0xFF0C070D;
        Round.fill(g, x0 + 2 * u, y0 + 5 * u, x0 + 14 * u, y0 + 16 * u, 5 * u, col);
        Round.fill(g, x0 + 3 * u, y0 + 1 * u, x0 + 13 * u, y0 + 9 * u, 4 * u, col);
        for (int i = 0; i < 3 * u; i++) {
            g.fill(x0 + 3 * u, y0 - 2 * u + i, x0 + 3 * u + Math.max(1, i * 3 / 4) , y0 - 2 * u + i + 1, col);
            g.fill(x0 + 13 * u - Math.max(1, i * 3 / 4), y0 - 2 * u + i, x0 + 13 * u, y0 - 2 * u + i + 1, col);
        }
        g.fill(x0 + 5 * u, y0 + 4 * u, x0 + 6 * u, y0 + 5 * u + u / 2, PINK);
        g.fill(x0 + 10 * u, y0 + 4 * u, x0 + 11 * u, y0 + 5 * u + u / 2, PINK);
        g.fill(x0 + 13 * u, y0 + 12 * u, x0 + 17 * u, y0 + 14 * u, col);
        g.fill(x0 + 16 * u, y0 + 9 * u, x0 + 18 * u, y0 + 14 * u, col);
    }

    private static int nearestIndex(int[] steps, int value) {
        int best = 0;
        for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) best = i;
        return best;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        Window window = minecraft.getWindow();
        double gui = window.getGuiScale();
        unit = Math.min(2.4, Math.min(window.getWidth() / (W + 10.0), window.getHeight() / (H + 10.0)));
        ox = (int) ((window.getWidth() - W * unit) / 2);
        oy = (int) ((window.getHeight() - H * unit) / 2);
        realX = (int) (mouseX * gui);
        realY = (int) (mouseY * gui);
        g = graphics;
        hits.clear();
        sliders.clear();
        graphics.pose().pushPose();
        graphics.pose().scale((float) (1 / gui), (float) (1 / gui), 1);
        graphics.drawManaged(this::paint);
        graphics.pose().popPose();
    }

    private void paint() {
        int lift = Math.max(3, (int) Math.round(3 * unit));
        int corner = (int) Math.round(14 * unit);
        Round.plate(g, X(0), Y(0), X(W), Y(H), corner, PANEL, lift);
        cat(18, 8);
        text("Char's Far View", W / 2.0, 10, 1.25, PINK, 0);
        double y = 32;
        toggle(y, "Far View", "F9 switches it on and off in the game", FarSettings.enabled, () -> {
            FarSettings.enabled = !FarSettings.enabled;
            changed();
        });
        y += 27;
        slider(y, "How far you can see", FarSettings.distance + " blocks", nearestIndex(FarSettings.DISTANCES, FarSettings.distance), FarSettings.DISTANCES.length, i -> {
            FarSettings.distance = FarSettings.DISTANCES[i];
            changed();
        });
        y += 29;
        text("Detail", 20, y + 3, 1, TEXT, -1);
        double chipW = 62, chipX = W - 20 - 3 * chipW - 8;
        for (FarSettings.Quality q : FarSettings.Quality.values()) {
            pill(chipX, y, chipW, 15, q.label, FarSettings.quality == q, () -> {
                FarSettings.quality = q;
                changed();
            });
            chipX += chipW + 4;
        }
        y += 22;
        text("Worker threads", 20, y + 3, 1, TEXT, -1);
        chipX = W - 20 - 2 * 40 - 4;
        for (int n = 1; n <= 2; n++) {
            int count = n;
            pill(chipX, y, 40, 15, String.valueOf(n), FarSettings.threads == n, () -> {
                FarSettings.threads = count;
                changed();
            });
            chipX += 44;
        }
        y += 22;
        toggle(y, "Use chunks already generated", "Your own worlds: read from the save files", FarSettings.useGeneratedChunks, () -> {
            FarSettings.useGeneratedChunks = !FarSettings.useGeneratedChunks;
            changed();
        });
        y += 26;
        slider(y, "Graphics memory limit", FarSettings.vramMb + " MB", nearestIndex(FarSettings.VRAM_STEPS, FarSettings.vramMb), FarSettings.VRAM_STEPS.length, i -> {
            FarSettings.vramMb = FarSettings.VRAM_STEPS[i];
            changed();
        });
        y += 27;
        slider(y, "Tile memory limit", FarSettings.memoryMb + " MB", nearestIndex(FarSettings.MEMORY_STEPS, FarSettings.memoryMb), FarSettings.MEMORY_STEPS.length, i -> {
            FarSettings.memoryMb = FarSettings.MEMORY_STEPS[i];
            changed();
        });
        y += 27;
        toggle(y, "Show numbers on screen", null, FarSettings.showHud, () -> {
            FarSettings.showHud = !FarSettings.showHud;
            changed();
        });
        y += 19;
        LodWorld w = FarClient.world();
        String line;
        if (w == null) line = "Not in a world right now";
        else if (w.scanner == null) line = "Seen " + w.seenChunksCount() + " chunks, " + w.remote.status;
        else if (w.scanner.regionsLeft == 0) line = "Save files read: all up to date";
        else line = "Reading save files: " + w.scanner.regionsLeft + " regions to go";
        text(line, W / 2.0, y, 0.75, DIM, 0);
        double by = H - 25;
        pill(20, by, 130, 17, sureRebuild ? "Sure? Click again" : "Rebuild this world", sureRebuild, () -> {
            if (!sureRebuild) {
                sureRebuild = true;
                return;
            }
            sureRebuild = false;
            FarClient.rebuild();
        });
        pill(W - 20 - 90, by, 90, 17, "Done", true, this::onClose);
    }

    private void changed() {
        FarSettings.save();
        LodWorld w = FarClient.world();
        if (w != null) {
            w.setCacheLimit();
            w.selectionDirty = true;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double gui = minecraft.getWindow().getGuiScale();
        int rx = (int) (mx * gui), ry = (int) (my * gui);
        for (Slider s : new ArrayList<>(sliders)) {
            if (rx >= s.x && rx < s.x + s.w + s.h && ry >= s.y && ry < s.y + s.h) {
                dragging = s;
                drag(rx);
                return true;
            }
        }
        for (Hit h : new ArrayList<>(hits)) {
            if (rx >= h.x && rx < h.x + h.w && ry >= h.y && ry < h.y + h.h) {
                h.what.run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            drag((int) (mx * minecraft.getWindow().getGuiScale()));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = null;
        return super.mouseReleased(mx, my, button);
    }

    private void drag(int rx) {
        Slider s = dragging;
        float f = s.w <= 0 ? 0 : (rx - s.x - (float) s.h / 4) / s.w;
        int i = Math.round(Math.max(0f, Math.min(1f, f)) * (s.count - 1));
        s.pick.accept(i);
    }

    @Override
    public void onClose() {
        if (FarSettings.threads != threadsAtOpen) FarClient.reopen();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // The numbers, if you asked for them: small rounded plate at the top left.
    static void drawHud(GuiGraphics g, List<String> lines) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        double gui = mc.getWindow().getGuiScale();
        float scale = (float) Math.max(1, Math.round(gui));
        g.pose().pushPose();
        g.pose().scale((float) (1 / gui), (float) (1 / gui), 1);
        g.drawManaged(() -> {
            int width = 0;
            for (String s : lines) width = Math.max(width, font.width(s));
            int pad = (int) (4 * scale);
            int x1 = (int) (width * scale) + pad * 2 + 6, y1 = (int) (lines.size() * 10 * scale) + pad * 2 + 6;
            Round.fill(g, 6, 6, x1, y1, (int) (6 * scale), 0xB01A0F1E);
            int y = 6 + pad;
            for (String s : lines) {
                g.pose().pushPose();
                g.pose().translate(6 + pad, y, 0);
                g.pose().scale(scale, scale, 1);
                g.drawString(font, s, 0, 0, 0xFFF4E4F0, false);
                g.pose().popPose();
                y += (int) (10 * scale);
            }
        });
        g.pose().popPose();
    }
}
