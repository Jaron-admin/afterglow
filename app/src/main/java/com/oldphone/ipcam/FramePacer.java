package com.oldphone.ipcam;

/**
 * Single-consumer monotonic frame pacing. Deadlines retain their phase across minor
 * capture jitter instead of starting another complete interval after every frame.
 */
public final class FramePacer {
    private static final long MAX_EARLY_NANOS = 5_000_000L;
    private long intervalNanos;
    private long earlyToleranceNanos;
    private long nextDeadline;
    private long lastAccepted;
    private boolean initialized;

    public FramePacer(int fps) { setFps(fps); }

    public void setFps(int fps) {
        if (fps < 1 || fps > 1_000_000_000) throw new IllegalArgumentException("Invalid frame rate");
        setIntervalNanos(1_000_000_000L / fps);
    }

    /** Also supports slower-than-one-FPS work such as idle snapshot refreshes. */
    public void setIntervalNanos(long interval) {
        if (interval <= 0) throw new IllegalArgumentException("Frame interval must be positive");
        if (intervalNanos == interval) return;
        intervalNanos = interval;
        earlyToleranceNanos = Math.min(interval / 10, MAX_EARLY_NANOS);
        reset();
    }

    public void reset() { initialized = false; }

    public boolean shouldRun(long nowNanos) {
        if (!initialized) {
            initialized = true;
            nextDeadline = nowNanos + intervalNanos;
        } else {
            // nanoTime has an arbitrary, possibly negative origin and may wrap.
            // Subtraction is safe for the normal (< 292-year) elapsed intervals.
            if (nowNanos - lastAccepted <= 0 || nowNanos - nextDeadline < -earlyToleranceNanos) return false;
            nextDeadline += intervalNanos;
            // A stall must never accumulate a backlog of frames to replay. Minor
            // lateness retains phase; missing whole intervals starts a fresh cadence.
            if (nowNanos - nextDeadline >= 0) nextDeadline = nowNanos + intervalNanos;
        }
        lastAccepted = nowNanos;
        return true;
    }
}
