package com.oldphone.ipcam;

import java.nio.ByteBuffer;

/** Converts YUV_420_888 planes to NV21 without changing their buffer positions. */
public final class Yuv420Converter {
    private Yuv420Converter() { }

    public static void toNv21(int width, int height,
            ByteBuffer y, int yRowStride, int yPixelStride,
            ByteBuffer u, int uRowStride, int uPixelStride,
            ByteBuffer v, int vRowStride, int vPixelStride, byte[] out) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0
                || pixels > Integer.MAX_VALUE * 2L / 3 || out == null
                || out.length < pixels * 3 / 2) {
            throw new IllegalArgumentException("Invalid NV21 frame dimensions or destination");
        }
        validate(y, width, height, yRowStride, yPixelStride);
        validate(u, width / 2, height / 2, uRowStride, uPixelStride);
        validate(v, width / 2, height / 2, vRowStride, vPixelStride);
        int offset = 0;
        int base = y.position();
        for (int row = 0; row < height; row++) {
            int start = base + row * yRowStride;
            for (int col = 0; col < width; col++) out[offset++] = y.get(start + col * yPixelStride);
        }
        int uBase = u.position(), vBase = v.position();
        for (int row = 0; row < height / 2; row++) {
            int uStart = uBase + row * uRowStride, vStart = vBase + row * vRowStride;
            for (int col = 0; col < width / 2; col++) {
                out[offset++] = v.get(vStart + col * vPixelStride);
                out[offset++] = u.get(uStart + col * uPixelStride);
            }
        }
    }

    private static void validate(ByteBuffer plane, int columns, int rows, int rowStride, int pixelStride) {
        if (plane == null || rowStride <= 0 || pixelStride <= 0
                || (long) (columns - 1) * pixelStride >= rowStride
                || (long) plane.position() + (long) (rows - 1) * rowStride
                    + (long) (columns - 1) * pixelStride >= plane.limit()) {
            throw new IllegalArgumentException("YUV plane is truncated or has invalid strides");
        }
    }
}
