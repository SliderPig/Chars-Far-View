package com.example.charlod.core;

// The six sides of what the camera can see, taken from a view-projection matrix (columns, like OpenGL) and tested against boxes.
public final class Frustum {
    private final float[] planes = new float[24];

    // m = 16 floats, column major. Positions are relative to the camera, so numbers stay small.
    public void set(float[] m) {
        plane(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]);
        plane(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]);
        plane(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]);
        plane(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]);
        plane(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]);
        plane(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]);
    }

    private void plane(int i, float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len < 1e-6f) len = 1f;
        planes[i * 4] = a / len;
        planes[i * 4 + 1] = b / len;
        planes[i * 4 + 2] = c / len;
        planes[i * 4 + 3] = d / len;
    }

    public boolean visible(float x0, float y0, float z0, float x1, float y1, float z1) {
        for (int i = 0; i < 6; i++) {
            float a = planes[i * 4], b = planes[i * 4 + 1], c = planes[i * 4 + 2], d = planes[i * 4 + 3];
            float px = a >= 0 ? x1 : x0, py = b >= 0 ? y1 : y0, pz = c >= 0 ? z1 : z0;
            if (a * px + b * py + c * pz + d < 0) return false;
        }
        return true;
    }
}
