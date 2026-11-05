package com.example.charlod.client;

import com.example.charlod.core.Selector;

// One tile as the renderer knows it. Workers fill "pending", the render thread moves it to the graphics card.
public final class Tile {
    final long key;
    volatile int state = Selector.UNKNOWN;
    volatile double priority;
    volatile long lastWanted;
    volatile boolean queued;
    volatile Pending pending;

    // render thread only
    int vao, vbo, indexCount, gpuBytes;
    int minY, maxY;
    long lastUsed;
    double distance;

    long occupied; // 8 x 8 grid over the tile: which parts have any columns

    record Pending(byte[] bytes, int vertices, int minY, int maxY, long occupied) {}

    Tile(long key) {
        this.key = key;
    }
}
