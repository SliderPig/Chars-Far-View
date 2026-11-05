package com.example.charlod.core;

// Finding the top of the ground in a column without filling the column: ask the terrain maths "solid here?" as few times as possible.
public final class SurfaceSearch {
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    private SurfaceSearch() {}

    // The highest solid y found from the hint outwards, to within accuracy blocks, or Integer.MIN_VALUE when the whole range is air.
    // Comes down on the ground from above, so a cave under the surface doesn't fool it.
    public static int fromHint(Solid s, int x, int z, int minY, int maxY, int hint, int accuracy) {
        int y = Math.max(minY, Math.min(maxY, hint));
        int step = 8;
        int high; // known air
        int low;  // known solid
        if (s.at(x, y, z)) {
            low = y;
            high = y + step;
            while (high <= maxY && s.at(x, high, z)) {
                low = high;
                step *= 2;
                high += step;
            }
            if (high > maxY) high = maxY + 1;
        } else {
            high = y;
            low = y - step;
            while (low >= minY && !s.at(x, low, z)) {
                high = low;
                step *= 2;
                low -= step;
            }
            if (low < minY) {
                if (minY >= high || !s.at(x, minY, z)) return Integer.MIN_VALUE;
                low = minY;
            }
        }
        while (high - low > accuracy) {
            int mid = Math.floorDiv(low + high, 2);
            if (mid == low) break;
            if (s.at(x, mid, z)) low = mid;
            else high = mid;
        }
        return low;
    }

    // From the top down in steps: for terrain with gaps in it (the End's islands). Integer.MIN_VALUE when it only finds air.
    public static int fromAbove(Solid s, int x, int z, int minY, int maxY, int step, int accuracy) {
        for (int y = maxY; y >= minY; y -= step) {
            if (!s.at(x, y, z)) continue;
            int low = y, high = y + step;
            while (high - low > accuracy) {
                int mid = Math.floorDiv(low + high, 2);
                if (s.at(x, mid, z)) low = mid;
                else high = mid;
            }
            return low;
        }
        return Integer.MIN_VALUE;
    }

    // Under a roof (the Nether): first a run of open air below the roof, then the floor under it. Integer.MIN_VALUE when it never opens up.
    public static int belowRoof(Solid s, int x, int z, int minY, int roofY, int step, int accuracy) {
        int y = roofY;
        int air = 0;
        for (; y >= minY; y -= step) {
            if (!s.at(x, y, z)) {
                if (++air >= 2) break;
            } else {
                air = 0;
            }
        }
        if (y < minY) return Integer.MIN_VALUE;
        for (; y >= minY; y -= step) {
            if (!s.at(x, y, z)) continue;
            int low = y, high = y + step;
            while (high - low > accuracy) {
                int mid = Math.floorDiv(low + high, 2);
                if (s.at(x, mid, z)) low = mid;
                else high = mid;
            }
            return low;
        }
        return minY;
    }
}
