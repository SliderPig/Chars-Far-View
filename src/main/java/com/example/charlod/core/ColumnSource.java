package com.example.charlod.core;

// A chunk you can read blocks from: either parsed straight out of a region file, or one the client has loaded. x and z are 0-15, y is the real height.
public interface ColumnSource {
    int minSection();

    int maxSection();

    boolean sectionEmpty(int sectionY);

    int block(int x, int y, int z);

    int biome(int x, int y, int z);
}
