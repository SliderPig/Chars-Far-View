package com.example.charlod.core;

// Does the seed still tell the truth about this world? Every so often a real chunk's surface is compared with what the seed says should be there.
// Terrain plugins, WorldEdit, WorldPainter, odd generators: if enough real chunks disagree badly, the seed is wrong for this world and gets dropped.
public final class TruthCheck {
    public static final int WINDOW = 128, MIN_CHUNKS = 50;
    public static final float BAD_ERROR = 8f, BAD_SHARE = 0.30f;

    private final boolean[] bad = new boolean[WINDOW];
    private int next, filled, badCount;
    private long total;
    private double errorSum;
    private boolean wrong;

    // error: how far off this chunk's surface was, in blocks. Returns true the moment the seed is declared wrong.
    public synchronized boolean add(float error) {
        total++;
        errorSum += error;
        if (filled == WINDOW && bad[next]) badCount--;
        boolean isBad = error > BAD_ERROR;
        bad[next] = isBad;
        if (isBad) badCount++;
        next = (next + 1) % WINDOW;
        if (filled < WINDOW) filled++;
        if (!wrong && filled >= MIN_CHUNKS && badCount > filled * BAD_SHARE) {
            wrong = true;
            return true;
        }
        return false;
    }

    public synchronized boolean wrong() {
        return wrong;
    }

    public synchronized long chunks() {
        return total;
    }

    public synchronized double meanError() {
        return total == 0 ? 0 : errorSum / total;
    }

    public synchronized float badShare() {
        return filled == 0 ? 0 : (float) badCount / filled;
    }

    // Median of up to a few sample errors, so one house or one cave mouth doesn't count against the seed.
    public static float median(float[] samples, int count) {
        if (count <= 0) return -1;
        float[] copy = java.util.Arrays.copyOf(samples, count);
        java.util.Arrays.sort(copy);
        return count % 2 == 1 ? copy[count / 2] : (copy[count / 2 - 1] + copy[count / 2]) / 2f;
    }
}
