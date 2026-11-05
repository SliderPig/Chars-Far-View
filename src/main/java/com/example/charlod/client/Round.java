package com.example.charlod.client;

import net.minecraft.client.gui.GuiGraphics;

// Rounded shapes with soft edges, drawn in real screen pixels (scale the pose by 1 / GUI scale first) so corners are smooth at any GUI scale.
final class Round {
    private Round() {}

    private static int faded(int colour, float amount) {
        return ((int) (((colour >>> 24) & 0xFF) * amount) << 24) | (colour & 0xFFFFFF);
    }

    private static float inset(int row, int r) {
        float d = r - row - 0.5f;
        return r - (float) Math.sqrt(Math.max(0f, r * r - d * d));
    }

    private static void span(GuiGraphics g, int x0, int x1, int y, float left, int colour) {
        int whole = (int) Math.ceil(left);
        float part = whole - left;
        if (x0 + whole < x1 - whole) g.fill(x0 + whole, y, x1 - whole, y + 1, colour);
        if (part > 0.02f && whole > 0) {
            int edge = faded(colour, part);
            g.fill(x0 + whole - 1, y, x0 + whole, y + 1, edge);
            g.fill(x1 - whole, y, x1 - whole + 1, y + 1, edge);
        }
    }

    static void fill(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int colour) {
        int h = y1 - y0;
        r = Math.min(r, Math.min(x1 - x0, h) / 2);
        for (int i = 0; i < r; i++) {
            float in = inset(i, r);
            span(g, x0, x1, y0 + i, in, colour);
            span(g, x0, x1, y1 - 1 - i, in, colour);
        }
        if (h > 2 * r) g.fill(x0, y0 + r, x1, y1 - r, colour);
    }

    private static int shift(int colour, int by) {
        int r = Math.max(0, Math.min(255, ((colour >> 16) & 255) + by)), gr = Math.max(0, Math.min(255, ((colour >> 8) & 255) + by)), b = Math.max(0, Math.min(255, (colour & 255) + by));
        return (colour & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }

    // A raised plate: a soft shadow under it, a lit top edge, a dark bottom edge, then its face.
    static void plate(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int colour, int lift) {
        fill(g, x0 + 1, y0 + lift, x1 + 1, y1 + lift, r, 0x50000000);
        fill(g, x0, y0, x1, y1, r, shift(colour, -40) | 0xFF000000);
        fill(g, x0, y0, x1, y1 - lift, r, shift(colour, 50) | 0xFF000000);
        fill(g, x0 + 1, y0 + 1, x1 - 1, y1 - lift, Math.max(0, r - 1), colour | 0xFF000000);
    }

    // A sunken well: dark at the top where the edge shades it, a thin lit lip along the bottom.
    static void well(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int colour, int depth) {
        fill(g, x0, y0, x1, y1, r, 0x50FFA0D8);
        fill(g, x0, y0, x1, y1 - 1, r, 0xF0120812);
        fill(g, x0 + 1, y0 + depth, x1 - 1, y1 - 1, Math.max(0, r - 1), colour);
    }
}
