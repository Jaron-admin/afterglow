package com.oldphone.ipcam;

/**
 * Bounded, single-threaded change detector for the actual upright/cropped NV21 output.
 * This reports visual changes, never a person/pet classification. Uniform luminance shifts
 * are compensated before measuring differences; uneven lights and shadows can still trigger.
 */
public final class MotionDetector {
    private static final long INTERVAL_US = 200000;
    private static final long WARMUP_US = 800000;
    private static final long COOLDOWN_US = 2000000;
    private float[] background;
    private int[] previous;
    private int[] current;
    private int columns, rows, width, height;
    private long revision;
    private long firstPts = Long.MIN_VALUE;
    private long lastPts = Long.MIN_VALUE;
    private long activeSince = Long.MIN_VALUE;
    private long lastEvent = Long.MIN_VALUE;

    public boolean update(byte[] nv21, int width, int height, long ptsUs, long geometryRevision, int sensitivity) {
        if (nv21 == null || width < 2 || height < 2 || (long) width * height > nv21.length) {
            throw new IllegalArgumentException("Invalid luminance plane");
        }
        int level = Math.max(0, Math.min(2, sensitivity));
        if (background == null || this.width != width || this.height != height
                || revision != geometryRevision || ptsUs < lastPts
                || (lastPts != Long.MIN_VALUE && ptsUs - lastPts > 3000000)) {
            reset();
            this.width = width;
            this.height = height;
            revision = geometryRevision;
            columns = Math.min(64, width / 2);
            rows = Math.min(48, height / 2);
            background = new float[columns * rows];
            previous = new int[background.length];
            current = new int[background.length];
            sample(nv21);
            for (int i = 0; i < current.length; i++) background[i] = previous[i] = current[i];
            firstPts = lastPts = ptsUs;
            return false;
        }
        if (ptsUs - lastPts < INTERVAL_US) return false;
        lastPts = ptsUs;
        sample(nv21);
        if (ptsUs - firstPts < WARMUP_US) {
            for (int i = 0; i < current.length; i++) background[i] = previous[i] = current[i];
            return false;
        }
        double backgroundShift = 0, frameShift = 0;
        for (int i = 0; i < current.length; i++) {
            backgroundShift += current[i] - background[i];
            frameShift += current[i] - previous[i];
        }
        backgroundShift /= current.length;
        frameShift /= current.length;
        int pixelThreshold = level == 0 ? 28 : level == 1 ? 22 : 18;
        int backgroundChanged = 0, frameChanged = 0;
        for (int i = 0; i < current.length; i++) {
            double residual = current[i] - background[i] - backgroundShift;
            if (Math.abs(residual) >= pixelThreshold) backgroundChanged++;
            if (Math.abs(current[i] - previous[i] - frameShift) >= pixelThreshold) frameChanged++;
            background[i] += (float) (backgroundShift + 0.12 * residual);
            previous[i] = current[i];
        }
        double fraction = (double) Math.max(backgroundChanged, frameChanged) / current.length;
        boolean changed = fraction >= (level == 0 ? 0.12 : level == 1 ? 0.05 : 0.018);
        if (!changed) {
            activeSince = Long.MIN_VALUE;
            return false;
        }
        if (activeSince == Long.MIN_VALUE) activeSince = ptsUs;
        long sustainedUs = level == 0 ? 600000 : 400000;
        if (ptsUs - activeSince < sustainedUs
                || (lastEvent != Long.MIN_VALUE && ptsUs - lastEvent < COOLDOWN_US)) return false;
        lastEvent = ptsUs;
        return true;
    }

    private void sample(byte[] nv21) {
        int index = 0;
        for (int y = 0; y < rows; y++) {
            int sy = Math.min(height - 2, (int) (((long) (2 * y + 1) * height) / (2 * rows)));
            for (int x = 0; x < columns; x++) {
                int sx = Math.min(width - 2, (int) (((long) (2 * x + 1) * width) / (2 * columns)));
                int p = sy * width + sx;
                current[index++] = ((nv21[p] & 255) + (nv21[p + 1] & 255)
                        + (nv21[p + width] & 255) + (nv21[p + width + 1] & 255)) / 4;
            }
        }
    }

    public void reset() {
        background = null;
        previous = current = null;
        firstPts = lastPts = activeSince = lastEvent = Long.MIN_VALUE;
    }
}
