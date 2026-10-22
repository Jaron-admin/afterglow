package com.oldphone.ipcam;

import android.graphics.Bitmap;

/** Optional still values for screenshots. Live UI ignores a field when it is unset. */
public final class ScreenSnapshot {
    public static int brightness = -1;
    public static String freeSpace;
    public static String usedSpace;
    public static int usedPercent = -1;
    public static int todayClips = -1;
    public static int totalClips = -1;
    public static String clock;
    /** Screenshot clock. Zero uses the live wall clock. */
    public static long epochMs;
    public static boolean freezeMotion;
    /** Draw the 28dp screenshot status bar and freeze the glow on the shot frame. */
    public static boolean composeChrome;
    /** Placeholder preview used by screenshots. Live camera frames replace it. */
    public static Bitmap preview;
    /** Transform behind {@link #preview}; without it the shot cannot show the crop band. */
    public static FrameGeometry previewGeometry;

    private ScreenSnapshot() {}

    public static void clear() {
        brightness = -1;
        freeSpace = null;
        usedSpace = null;
        usedPercent = -1;
        todayClips = -1;
        totalClips = -1;
        clock = null;
        epochMs = 0;
        freezeMotion = false;
        composeChrome = false;
        preview = null;
        previewGeometry = null;
    }
}
