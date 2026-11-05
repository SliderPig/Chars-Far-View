package com.example.charlod.core;

// Four columns become one, one level further out. The mean height keeps hills where they are, a little pull towards the highest
// keeps peaks and tree tops on the skyline; a column counts as water only when at least half of it is.
// Real columns always beat seed-made ones: seed-made ones only count when there is nothing real among the four.
public final class ColumnMerge {
    private ColumnMerge() {}

    public static boolean merge(short[] sh, int[] st, int[] ss, int a, int b, int c, int d, short[] dh, int[] dt, int[] ds, int out) {
        int count = 0, real = 0;
        for (int i = 0; i < 4; i++) {
            int s = i == 0 ? a : i == 1 ? b : i == 2 ? c : d;
            if (sh[s] == LodMath.NO_DATA) continue;
            count++;
            if ((st[s] & TileData.F_ESTIMATED) == 0) real++;
        }
        if (count == 0) {
            if (dh[out] == LodMath.NO_DATA || (dt[out] & TileData.F_ESTIMATED) != 0) return false; // whatever the seed said here still stands
            dh[out] = LodMath.NO_DATA;
            dt[out] = 0;
            ds[out] = 0;
            return true;
        }
        boolean onlyReal = real > 0;
        int water = 0, seen = 0;
        for (int i = 0; i < 4; i++) {
            int s = i == 0 ? a : i == 1 ? b : i == 2 ? c : d;
            if (sh[s] == LodMath.NO_DATA || (onlyReal && (st[s] & TileData.F_ESTIMATED) != 0)) continue;
            seen++;
            if ((st[s] & TileData.F_WATER) != 0) water++;
        }
        boolean wantWater = water * 2 >= seen;
        int used = 0, sum = 0, max = Integer.MIN_VALUE, tr = 0, tg = 0, tb = 0, sr = 0, sg = 0, sb = 0, foliage = 0, glow = 0;
        for (int i = 0; i < 4; i++) {
            int s = i == 0 ? a : i == 1 ? b : i == 2 ? c : d;
            short h = sh[s];
            if (h == LodMath.NO_DATA) continue;
            int t = st[s];
            if (onlyReal && (t & TileData.F_ESTIMATED) != 0) continue;
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
        if (!onlyReal) top |= TileData.F_ESTIMATED;
        int side = ((sr / used) << 16) | ((sg / used) << 8) | (sb / used);
        if (dh[out] == height && dt[out] == top && ds[out] == side) return false;
        dh[out] = height;
        dt[out] = top;
        ds[out] = side;
        return true;
    }
}
