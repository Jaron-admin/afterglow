package com.oldphone.ipcam;

/** Single-consumer MJPEG scheduling after the capture frame-rate gate. */
public final class MjpegCadence {
    private final FramePacer idle = new FramePacer(1);

    public MjpegCadence() { idle.setIntervalNanos(2_000_000_000L); }

    public boolean shouldPublish(long nowNanos, boolean hasClients) {
        if (hasClients) {
            // Capture has already selected the target FPS. A second gate anchored
            // at client join can drop half those frames due to different jitter phase.
            idle.reset();
            return true;
        }
        return idle.shouldRun(nowNanos);
    }

    /** New geometry or a new capture session requires a fresh first snapshot. */
    public void reset() { idle.reset(); }
}
