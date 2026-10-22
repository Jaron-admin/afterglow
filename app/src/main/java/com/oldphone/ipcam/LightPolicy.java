package com.oldphone.ipcam;

/** Shared brightness threshold. Bright enough means brightness ≥ threshold. */
public final class LightPolicy {
    public static final String FRAME = "frame";
    public static final String SENSOR = "sensor";

    private LightPolicy() {}

    public static String method(String stored) {
        return SENSOR.equals(stored) ? SENSOR : FRAME;
    }

    public static int clampPercent(int value) {
        if (value < 0) return 0;
        return value > 100 ? 100 : value;
    }

    /** Unknown readings are negative and do not count as dim. */
    public static boolean satisfied(int brightnessPercent, int thresholdPercent) {
        if (brightnessPercent < 0) return true;
        return brightnessPercent >= clampPercent(thresholdPercent);
    }

    public static boolean pauseRecording(boolean recordLightEnabled, int brightnessPercent, int thresholdPercent) {
        return recordLightEnabled && brightnessPercent >= 0 && !satisfied(brightnessPercent, thresholdPercent);
    }

    /** Stream pauses only for the ambient sensor, and only when that switch is on. */
    public static boolean pauseStream(String method, boolean streamLightEnabled,
                                      int brightnessPercent, int thresholdPercent) {
        return SENSOR.equals(method(method)) && streamLightEnabled
                && brightnessPercent >= 0 && !satisfied(brightnessPercent, thresholdPercent);
    }

    /** Sensor pause may release the camera. Frame mode has to keep it open to measure the picture. */
    public static boolean cameraMaySleep(String method, boolean streamLightEnabled,
                                         int brightnessPercent, int thresholdPercent) {
        return pauseStream(method, streamLightEnabled, brightnessPercent, thresholdPercent);
    }

    /** Average luma of an NV21 buffer, 0–100. Samples the Y plane. */
    public static int framePercent(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0) return -1;
        int plane = width * height;
        if (nv21.length < plane) return -1;
        int step = Math.max(1, Math.max(width, height) / 48);
        long sum = 0;
        int count = 0;
        for (int y = 0; y < height; y += step) {
            int row = y * width;
            for (int x = 0; x < width; x += step) {
                sum += nv21[row + x] & 0xff;
                count++;
            }
        }
        if (count == 0) return -1;
        return (int) (sum * 100L / (count * 255L));
    }

    /** Log map so indoor light lands in the middle of the slider. 0 lux is 0%. */
    public static int luxToPercent(float lux) {
        if (lux <= 0f || Float.isNaN(lux)) return 0;
        double percent = Math.log10(lux + 1d) / Math.log10(10001d) * 100d;
        if (percent < 0d) return 0;
        if (percent > 100d) return 100;
        return (int) Math.round(percent);
    }
}
