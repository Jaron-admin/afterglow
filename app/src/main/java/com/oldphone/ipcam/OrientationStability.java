package com.oldphone.ipcam;

/** Sensor-independent hysteresis; the caller schedules commit after a stable interval. */
public final class OrientationStability {
    private int current, pending;
    public OrientationStability(int initial) {
        current = pending = ((initial + 45) / 90 * 90) % 360;
    }
    public int current() { return current; }
    public int pending() { return pending; }
    public void cancelPending() { pending = current; }
    public void observe(int angle) {
        if (angle < 0) { pending = current; return; }
        int delta = Math.abs(angle - current);
        delta = Math.min(delta, 360 - delta);
        // 5 degrees beyond the 45-degree boundary prevents rapid alternation.
        pending = delta < 50 ? current : ((angle + 45) / 90 * 90) % 360;
    }
    public boolean commit() {
        if (pending == current) return false;
        current = pending;
        return true;
    }
}
