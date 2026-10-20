package com.oldphone.ipcam;

/**
 * Single-threaded relative PCM energy detector, calibrated only against the recent ambient
 * signal, not dB SPL. Samples are borrowed PCM16 mono at 16 kHz and are never modified.
 */
public final class SoundDetector {
    private static final int SAMPLE_RATE = 16000;
    private static final long WARMUP_US = 1000000;
    private static final long COOLDOWN_US = 2000000;
    private long firstPts = Long.MIN_VALUE;
    private long previousEnd = Long.MIN_VALUE;
    private long activeSince = Long.MIN_VALUE;
    private long lastEvent = Long.MIN_VALUE;
    private double ambient;
    private long ambientSamples;

    public boolean update(short[] samples, int count, long ptsUs, int sensitivity) {
        if (samples == null || count < 0 || count > samples.length) {
            throw new IllegalArgumentException("Invalid PCM samples");
        }
        if (count == 0) return false;
        int level = Math.max(0, Math.min(2, sensitivity));
        long durationUs = (long) count * 1000000 / SAMPLE_RATE;
        if (firstPts == Long.MIN_VALUE || ptsUs + durationUs < previousEnd
                || ptsUs - previousEnd > 500000) {
            reset();
            firstPts = ptsUs;
        }
        previousEnd = ptsUs + durationUs;
        double sum = 0, squares = 0;
        for (int i = 0; i < count; i++) {
            double value = samples[i];
            sum += value;
            squares += value * value;
        }
        double mean = sum / count;
        double rms = Math.sqrt(Math.max(0, squares / count - mean * mean));
        if (ptsUs - firstPts < WARMUP_US) {
            ambient = (ambient * ambientSamples + rms * count) / (ambientSamples + count);
            ambientSamples += count;
            return false;
        }
        double absolute = level == 0 ? 1800 : level == 1 ? 800 : 350;
        double multiplier = level == 0 ? 4.0 : level == 1 ? 3.0 : 2.0;
        boolean louder = rms >= absolute && rms >= Math.max(50, ambient) * multiplier;
        // Small time-based updates keep different read buffer sizes equivalent. Active sounds
        // do not immediately become the baseline, so continued noise can extend an event.
        double alpha = Math.min(louder ? 0.002 : 0.25,
                durationUs / (louder ? 120000000.0 : 2000000.0));
        ambient += alpha * (rms - ambient);
        if (!louder) {
            activeSince = Long.MIN_VALUE;
            return false;
        }
        if (activeSince == Long.MIN_VALUE) activeSince = ptsUs;
        long sustainedUs = level == 0 ? 400000 : level == 1 ? 260000 : 160000;
        if (previousEnd - activeSince < sustainedUs
                || (lastEvent != Long.MIN_VALUE && ptsUs - lastEvent < COOLDOWN_US)) return false;
        lastEvent = ptsUs;
        return true;
    }

    public void reset() {
        firstPts = previousEnd = activeSince = lastEvent = Long.MIN_VALUE;
        ambient = 0;
        ambientSamples = 0;
    }
}
