package com.oldphone.ipcam;

/** One immutable transform shared by the preview mask and all network outputs. */
public final class FrameGeometry {
    public final int rawWidth, rawHeight, rotation, fullWidth, fullHeight;
    public final int cropLeft, cropTop, cropWidth, cropHeight, outputWidth, outputHeight;
    public final long revision;
    final int[] yColumns, yRows, uvColumns, uvRows;
    private FrameGeometry(int w, int h, int r, int ow, int oh, long revision, boolean fullView) {
        checkSize(w, h);
        checkSize(ow, oh);
        if (r != 0 && r != 90 && r != 180 && r != 270)
            throw new IllegalArgumentException("Rotation must be 0, 90, 180 or 270.");
        rawWidth = w; rawHeight = h; rotation = r;
        fullWidth = r % 180 == 0 ? w : h;
        fullHeight = r % 180 == 0 ? h : w;
        outputWidth = ow; outputHeight = oh; this.revision = revision;
        if (fullView) {
            cropWidth = fullWidth;
            cropHeight = fullHeight;
        } else if ((long) fullWidth * oh > (long) fullHeight * ow) {
            cropHeight = fullHeight;
            cropWidth = Math.max(2, ((int) ((long) fullHeight * ow / oh)) & ~1);
        } else {
            cropWidth = fullWidth;
            cropHeight = Math.max(2, ((int) ((long) fullWidth * oh / ow)) & ~1);
        }
        // Chroma samples represent 2x2 luma blocks. Keep every boundary even;
        // centering can consequently differ by one luma pixel.
        cropLeft = ((fullWidth - cropWidth) / 2) & ~1;
        cropTop = ((fullHeight - cropHeight) / 2) & ~1;
        yColumns = columns(ow, cropLeft, cropWidth, w, h, 1);
        yRows = rows(oh, cropTop, cropHeight, w, h, 1);
        uvColumns = columns(ow / 2, cropLeft / 2, cropWidth / 2, w / 2, h / 2, 2);
        uvRows = rows(oh / 2, cropTop / 2, cropHeight / 2, w / 2, h / 2, 2);
    }
    public static FrameGeometry create(int w, int h, int r, int ow, int oh, long revision) {
        return new FrameGeometry(w, h, r, ow, oh, revision, false);
    }
    /** Bounded local display buffer; rounding to even sizes must never crop the view. */
    public static FrameGeometry preview(int w, int h, int r, int maxEdge, long revision) {
        checkSize(w, h);
        if (maxEdge < 2 || maxEdge > 4096) throw new IllegalArgumentException("Invalid preview bound");
        int fullW = r % 180 == 0 ? w : h;
        int fullH = r % 180 == 0 ? h : w;
        int longest = Math.max(fullW, fullH);
        int edge = Math.min(longest, maxEdge & ~1);
        int ow = Math.max(2, (int) ((long) fullW * edge / longest) & ~1);
        int oh = Math.max(2, (int) ((long) fullH * edge / longest) & ~1);
        return new FrameGeometry(w, h, r, ow, oh, revision, true);
    }
    /** O is the clockwise physical device angle reported by OrientationEventListener. */
    public static int rotation(int sensor, boolean front, int device, int calibration) {
        return ((sensor + (front ? -device : device) + calibration) % 360 + 360) % 360;
    }
    public static boolean validOutputSize(int width, int height) {
        return width >= 16 && height >= 16 && width <= 4096 && height <= 4096
                && ((width | height) & 1) == 0 && (long) width * height <= 8_294_400L;
    }
    public static String ratio(int width, int height) {
        int a = width, b = height;
        while (b != 0) { int t = a % b; a = b; b = t; }
        return a <= 0 ? "—" : (width / a) + ":" + (height / a);
    }
    private static void checkSize(int w, int h) {
        if (w < 2 || h < 2 || w > 4096 || h > 4096 || ((w | h) & 1) != 0
                || (long) w * h > 8_294_400L)
            throw new IllegalArgumentException("Unsupported NV21 frame dimensions: " + w + "×" + h);
    }
    private int[] columns(int count, int start, int extent, int w, int h, int bytes) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int x = start + (int) (((2L * i + 1) * extent) / (2L * count));
            switch (rotation) {
                case 90: result[i] = (h - 1 - x) * w * bytes; break;
                case 180: result[i] = (w - 1 - x) * bytes; break;
                case 270: result[i] = x * w * bytes; break;
                default: result[i] = x * bytes;
            }
        }
        return result;
    }
    private int[] rows(int count, int start, int extent, int w, int h, int bytes) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int y = start + (int) (((2L * i + 1) * extent) / (2L * count));
            switch (rotation) {
                case 90: result[i] = y * bytes; break;
                case 180: result[i] = (h - 1 - y) * w * bytes; break;
                case 270: result[i] = (w - 1 - y) * bytes; break;
                default: result[i] = y * w * bytes;
            }
        }
        return result;
    }
}
