package com.example.charlod.core;

// Picks which tiles to draw: coarse far away, fine nearby. A tile only gives way to its four children once they are all ready,
// so while things load you see a rougher picture, never a hole.
public final class Selector {
    public static final int UNKNOWN = 0, LOADING = 1, EMPTY = 2, READY = 3;

    public interface Tiles {
        int state(long key);

        // Please get this tile ready. Smaller priority = sooner.
        void want(long key, double priority);

        // This tile has nothing yet. True if something is going to fill it (the seed generator), so its parent should stay on show meanwhile.
        boolean missing(long key, double priority);

        void draw(long key, double distance);
    }

    private final Tiles tiles;
    private double cx, cy, cz, maxDistance, ratio;

    public Selector(Tiles tiles) {
        this.tiles = tiles;
    }

    // ratio: a tile splits when it is nearer than ratio x its blocks per column. Bigger = finer = more work.
    public void select(double cx, double cy, double cz, double maxDistance, double ratio) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.maxDistance = maxDistance;
        this.ratio = ratio;
        int top = LodMath.TOP_LEVEL;
        int minX = LodMath.tileOfBlock((int) Math.floor(cx - maxDistance), top), maxX = LodMath.tileOfBlock((int) Math.floor(cx + maxDistance), top);
        int minZ = LodMath.tileOfBlock((int) Math.floor(cz - maxDistance), top), maxZ = LodMath.tileOfBlock((int) Math.floor(cz + maxDistance), top);
        for (int tz = minZ; tz <= maxZ; tz++) for (int tx = minX; tx <= maxX; tx++) visit(top, tx, tz);
    }

    // Straight line from the camera to the nearest point of the tile. Height only counts well above the usual ground, so flying high gets coarser.
    public double distance(int level, int tx, int tz) {
        double h = horizontal(level, tx, tz);
        double dy = Math.max(0, cy - 128);
        return Math.sqrt(h * h + dy * dy);
    }

    private double horizontal(int level, int tx, int tz) {
        double size = LodMath.tileBlocks(level);
        double x0 = (double) tx * size, z0 = (double) tz * size;
        double dx = cx < x0 ? x0 - cx : cx > x0 + size ? cx - x0 - size : 0;
        double dz = cz < z0 ? z0 - cz : cz > z0 + size ? cz - z0 - size : 0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void visit(int level, int tx, int tz) {
        if (horizontal(level, tx, tz) > maxDistance) return;
        long key = LodMath.key(level, tx, tz);
        int state = tiles.state(key);
        double d = distance(level, tx, tz);
        int perColumn = LodMath.blocksPerColumn(level);
        if (state == EMPTY) {
            tiles.missing(key, d / perColumn);
            return;
        }
        if (state != READY) {
            tiles.want(key, d / perColumn);
            return;
        }
        if (level == 0 || d >= ratio * perColumn) {
            tiles.draw(key, d);
            return;
        }
        boolean ready = true;
        for (int i = 0; i < 4; i++) {
            int cxT = tx * 2 + (i & 1), czT = tz * 2 + (i >> 1);
            if (horizontal(level - 1, cxT, czT) > maxDistance) continue;
            long child = LodMath.key(level - 1, cxT, czT);
            int s = tiles.state(child);
            double priority = distance(level - 1, cxT, czT) / (perColumn / 2.0);
            if (s == UNKNOWN || s == LOADING) {
                ready = false;
                tiles.want(child, priority);
            } else if (s == EMPTY && tiles.missing(child, priority)) {
                ready = false;
            }
        }
        if (!ready) {
            tiles.draw(key, d);
            return;
        }
        for (int i = 0; i < 4; i++) visit(level - 1, tx * 2 + (i & 1), tz * 2 + (i >> 1));
    }
}
