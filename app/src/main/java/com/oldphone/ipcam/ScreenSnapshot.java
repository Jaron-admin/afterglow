package com.oldphone.ipcam;

/** Optional still values for screenshots. Live UI ignores a field when it is unset. */
public final class ScreenSnapshot {
    public static int brightness = -1;
    public static String freeSpace;
    public static String usedSpace;
    public static int usedPercent = -1;
    public static int todayClips = -1;
    public static int totalClips = -1;
    public static String clock;
    public static boolean freezeMotion;

    private ScreenSnapshot() {}

    public static void clear() {
        brightness = -1;
        freeSpace = null;
        usedSpace = null;
        usedPercent = -1;
        todayClips = -1;
        totalClips = -1;
        clock = null;
        freezeMotion = false;
    }
}
