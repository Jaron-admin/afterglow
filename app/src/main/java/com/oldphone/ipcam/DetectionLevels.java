package com.oldphone.ipcam;

/** Sensitivity is 0 low, 1 medium, 2 high. Sound falls back to the motion level. */
public final class DetectionLevels {
    private DetectionLevels() {}

    public static int clamp(int level) {
        return Math.max(0, Math.min(2, level));
    }

    public static int soundOrMotion(boolean hasSoundLevel, int soundLevel, int motionLevel) {
        return clamp(hasSoundLevel ? soundLevel : motionLevel);
    }
}
