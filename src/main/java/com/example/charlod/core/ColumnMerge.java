package com.example.charlod.core;

// Four columns become one, one level further out. The mean height keeps hills where they are, a little pull towards the highest
// keeps peaks and tree tops on the skyline; a column counts as water only when at least half of it is.
public final class ColumnMerge {
    private ColumnMerge() {}

    public static boolean merge(short[] sh, int[] st, int[] ss, int a, int b, int c, int d, short[] dh, int[] dt, int[] ds, int out) {
        int count = 0, water = 0;
        if (sh[a] != LodMath.NO_DATA) { count++; if ((st[a] & TileData.F_WATER) != 0) water++; }
        if (sh[b] != LodMath.NO_DATA) { count++; if ((st[b] & TileData.F_WATER) != 0) water++; }
        if (sh[c] != LodMath.NO_DATA) { count++; if ((st[c] & TileData.F_WATER) != 0) water++; }
        if (sh[d] != LodMath.NO_DATA) { count++; if ((st[d] & TileData.F_WATER) != 0) water++; }
        if (count == 0) {
            if (dh[out] == LodMath.NO_DATA) return false;
            dh[out] = LodMath.NO_DATA;
            dt[out] = 0;
            ds[out] = 0;
            return true;
        }
        boolean wantWater = water * 2 >= count;
        int used = 0, sum = 0, max = Integer.MIN_VALUE, tr = 0, tg = 0, tb = 0, sr = 0, sg = 0, sb = 0, foliage = 0, glow = 0;
        for (int i = 0; i < 4; i++) {
            int s = i == 0 ? a : i == 1 ? b : i == 2 ? c : d;
            short h = sh[s];
            if (h == LodMath.NO_DATA) continue;
            int t = st[s];
            if (((t & TileData.F_WATER) != 0) != wantWater) continue;
            used++;
            sum += h;
            if (h > max) max = h;
            tr += (t >> 16) & 255;
            tg += (t >> 8) & 255;
            tb += t & 255;
            int e = ss[s];
            sr += (e >> 16) & 255;
            sg += (e >> 8) & 255;
            sb += e & 255;
            if ((t & TileData.F_FOLIAGE) != 0) foliage++;
            if ((t & TileData.F_GLOW) != 0) glow++;
        }
        int mean = Math.round((float) sum / used);
        short height = (short) (wantWater ? mean : Math.round(mean + (max - mean) * 0.25f));
        int top = ((tr / used) << 16) | ((tg / used) << 8) | (tb / used);
        if (wantWater) top |= TileData.F_WATER;
        if (foliage * 2 >= used) top |= TileData.F_FOLIAGE;
        if (glow * 2 >= used) top |= TileData.F_GLOW;
        int side = ((sr / used) << 16) | ((sg / used) << 8) | (sb / used);
        if (dh[out] == height && dt[out] == top && ds[out] == side) return false;
        dh[out] = height;
        dt[out] = top;
        ds[out] = side;
        return true;
    }
}
