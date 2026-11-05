package com.example.charlod.core;

// Just what kind of surface a block makes: all a server needs to know.
public interface BlockFlags {
    int flags(int block);
}
