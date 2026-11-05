package com.example.charlod.core;

import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

// What a tile looks like on disk: each channel as differences from the column before it, so land compresses small, then deflate.
// One codec per thread: it reuses its buffers.
public final class TileCodec {
    private static final int CHANNELS = 2 + 3 + 3 + 1;
    private static final int RAW = CHANNELS * LodMath.TILE_CELLS;
    private final byte[] raw = new byte[RAW];
    private final byte[] packed = new byte[RAW + RAW / 500 + 64];
    private final Deflater deflater = new Deflater(4);
    private final Inflater inflater = new Inflater();

    // Returns a fresh array, ready to be written.
    public byte[] encode(TileData t) {
        int n = LodMath.TILE_CELLS;
        int prevH = 0, ptr = 0, ptg = 0, ptb = 0, psr = 0, psg = 0, psb = 0, pf = 0;
        for (int i = 0; i < n; i++) {
            int h = t.height[i];
            int dh = h - prevH;
            prevH = h;
            raw[i] = (byte) dh;
            raw[n + i] = (byte) (dh >> 8);
            int c = t.top[i], s = t.side[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, f = c >>> 24;
            raw[2 * n + i] = (byte) (r - ptr);
            raw[3 * n + i] = (byte) (g - ptg);
            raw[4 * n + i] = (byte) (b - ptb);
            ptr = r;
            ptg = g;
            ptb = b;
            int sr = (s >> 16) & 255, sg = (s >> 8) & 255, sb = s & 255;
            raw[5 * n + i] = (byte) (sr - psr);
            raw[6 * n + i] = (byte) (sg - psg);
            raw[7 * n + i] = (byte) (sb - psb);
            psr = sr;
            psg = sg;
            psb = sb;
            raw[8 * n + i] = (byte) (f ^ pf);
            pf = f;
        }
        deflater.reset();
        deflater.setInput(raw, 0, RAW);
        deflater.finish();
        int len = deflater.deflate(packed);
        byte[] result = new byte[len];
        System.arraycopy(packed, 0, result, 0, len);
        return result;
    }

    public TileData decode(long key, byte[] data, int offset, int length) throws IOException {
        inflater.reset();
        inflater.setInput(data, offset, length);
        int got;
        try {
            got = inflater.inflate(raw);
        } catch (DataFormatException e) {
            throw new IOException("broken tile", e);
        }
        if (got != RAW) throw new IOException("short tile: " + got);
        TileData t = new TileData(key);
        int n = LodMath.TILE_CELLS;
        int prevH = 0, ptr = 0, ptg = 0, ptb = 0, psr = 0, psg = 0, psb = 0, pf = 0;
        for (int i = 0; i < n; i++) {
            int dh = (raw[i] & 255) | (raw[n + i] << 8);
            prevH = (short) (prevH + dh);
            t.height[i] = (short) prevH;
            ptr = (ptr + raw[2 * n + i]) & 255;
            ptg = (ptg + raw[3 * n + i]) & 255;
            ptb = (ptb + raw[4 * n + i]) & 255;
            psr = (psr + raw[5 * n + i]) & 255;
            psg = (psg + raw[6 * n + i]) & 255;
            psb = (psb + raw[7 * n + i]) & 255;
            pf = (pf ^ raw[8 * n + i]) & 255;
            t.top[i] = (pf << 24) | (ptr << 16) | (ptg << 8) | ptb;
            t.side[i] = (psr << 16) | (psg << 8) | psb;
        }
        return t;
    }

    public void close() {
        deflater.end();
        inflater.end();
    }
}
