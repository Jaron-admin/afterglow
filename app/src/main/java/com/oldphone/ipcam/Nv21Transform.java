package com.oldphone.ipcam;

public final class Nv21Transform {
    private Nv21Transform() {}
    /** Fused rotation, centered crop and nearest-neighbor scale, without per-frame allocation. */
    public static void render(byte[] source, FrameGeometry g, byte[] output) {
        checkBuffers(source, g.rawWidth * g.rawHeight * 3 / 2,
                output, g.outputWidth * g.outputHeight * 3 / 2);
        if (g.cropWidth == g.fullWidth && g.cropHeight == g.fullHeight
                && g.outputWidth == g.fullWidth && g.outputHeight == g.fullHeight) {
            rotateFull(source, g, output);
            return;
        }
        int destination = 0;
        for (int y = 0; y < g.outputHeight; y++) {
            int row = g.yRows[y];
            for (int x = 0; x < g.outputWidth; x++) output[destination++] = source[row + g.yColumns[x]];
        }
        int chromaBase = g.rawWidth * g.rawHeight;
        for (int y = 0; y < g.outputHeight / 2; y++) {
            int row = chromaBase + g.uvRows[y];
            for (int x = 0; x < g.outputWidth / 2; x++) {
                int offset = row + g.uvColumns[x];
                output[destination++] = source[offset];
                output[destination++] = source[offset + 1];
            }
        }
    }
    public static void rotateFull(byte[] source, FrameGeometry g, byte[] output) {
        int bytes = g.rawWidth * g.rawHeight * 3 / 2;
        checkBuffers(source, bytes, output, bytes);
        if (g.rotation == 0) System.arraycopy(source, 0, output, 0, bytes);
        else YuvRotator.rotate(source, output, g.rawWidth, g.rawHeight, g.rotation);
    }
    private static void checkBuffers(byte[] source, int neededSource, byte[] output, int neededOutput) {
        if (source == null || output == null || source.length < neededSource || output.length < neededOutput
                || source == output) throw new IllegalArgumentException("Invalid or overlapping NV21 buffers.");
    }
}
