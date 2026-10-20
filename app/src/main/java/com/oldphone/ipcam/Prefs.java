package com.oldphone.ipcam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 极简配置存储。全部走 SharedPreferences，避免引入任何第三方依赖。
 */
public final class Prefs {

    private static final String FILE = "ipcam_prefs";

    public static final String KEY_RES = "res_index";
    public static final String KEY_FPS = "fps";
    public static final String KEY_BITRATE = "bitrate";
    public static final String KEY_MODE = "mode";
    public static final String KEY_BOOT = "boot";
    public static final String KEY_AUTH = "auth";
    public static final String KEY_USER = "user";
    public static final String KEY_PASS = "pass";
    public static final String KEY_CAMERA = "camera_id";
    public static final String KEY_ROTATION = "rotation";

    /** 画面旋转（顺时针），索引与 ROTATION_DEGREES 对应 */
    public static final int[] ROTATION_DEGREES = {0, 90, 180, 270};
    public static final String[] ROTATION_LABELS = {
            "0°",
            "90°",
            "180°",
            "270°",
    };

    /** 摄像头选择：自动（优先后置，失败自动换另一个） */
    public static final int CAM_AUTO = 0;
    /** 强制后置，打不开时自动回退前置 */
    public static final int CAM_BACK = 1;
    /** 强制前置，打不开时自动回退后置 */
    public static final int CAM_FRONT = 2;

    public static final String[] CAMERA_LABELS = {
            "自动",
            "后置",
            "前置",
    };

    /** 输出模式：同时开 RTSP 与 MJPEG */
    public static final int MODE_BOTH = 0;
    /** 仅 RTSP（H.264，HA / go2rtc / VLC 用） */
    public static final int MODE_RTSP = 1;
    /** 仅 MJPEG（浏览器直接看，兼容性最好） */
    public static final int MODE_MJPEG = 2;

    /** 可选分辨率，索引与界面下拉框一一对应 */
    public static final int[][] RESOLUTIONS = {
            {640, 480},
            {1280, 720},
            {320, 240},
            {800, 600},
            {1920, 1080},
    };
    public static final String[] RESOLUTION_LABELS = {
            "640 × 480",
            "1280 × 720",
            "320 × 240",
            "800 × 600",
            "1920 × 1080",
    };

    public static final int[] FPS_OPTIONS = {5, 10, 15, 20, 25, 30};
    public static final String[] FPS_LABELS = {"5 fps", "10 fps", "15 fps", "20 fps", "25 fps", "30 fps"};

    public static final int[] BITRATE_OPTIONS = {500_000, 1_000_000, 1_500_000, 2_500_000, 4_000_000};
    public static final String[] BITRATE_LABELS = {"500 kbps", "1 Mbps", "1.5 Mbps", "2.5 Mbps", "4 Mbps"};

    public static final String[] MODE_LABELS = {
            "RTSP + MJPEG",
            "RTSP",
            "MJPEG",
    };

    private static SharedPreferences sp;

    private Prefs() {
    }

    public static void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p() {
        return sp;
    }

    public static int getInt(String key, int def) {
        return p() == null ? def : p().getInt(key, def);
    }

    public static void setInt(String key, int v) {
        if (p() != null) {
            p().edit().putInt(key, v).apply();
        }
    }

    public static boolean getBool(String key, boolean def) {
        return p() == null ? def : p().getBoolean(key, def);
    }

    public static void setBool(String key, boolean v) {
        if (p() != null) {
            p().edit().putBoolean(key, v).apply();
        }
    }

    public static String getStr(String key, String def) {
        return p() == null ? def : p().getString(key, def);
    }

    public static void setStr(String key, String v) {
        if (p() != null) {
            p().edit().putString(key, v).apply();
        }
    }

    // ---- 便捷读取 ----

    public static int resIndex() {
        int i = getInt(KEY_RES, 0);
        return (i < 0 || i >= RESOLUTIONS.length) ? 0 : i;
    }

    public static int width() {
        return RESOLUTIONS[resIndex()][0];
    }

    public static int height() {
        return RESOLUTIONS[resIndex()][1];
    }

    public static int fps() {
        int i = getInt(KEY_FPS, 2);
        return FPS_OPTIONS[(i < 0 || i >= FPS_OPTIONS.length) ? 2 : i];
    }

    public static int bitrate() {
        int i = getInt(KEY_BITRATE, 2);
        return BITRATE_OPTIONS[(i < 0 || i >= BITRATE_OPTIONS.length) ? 2 : i];
    }

    public static int mode() {
        int m = getInt(KEY_MODE, MODE_BOTH);
        return (m < 0 || m > 2) ? MODE_BOTH : m;
    }

    public static int cameraChoice() {
        int c = getInt(KEY_CAMERA, CAM_AUTO);
        return (c < 0 || c > CAM_FRONT) ? CAM_AUTO : c;
    }

    public static int rotationIndex() {
        int r = getInt(KEY_ROTATION, 0);
        return (r < 0 || r >= ROTATION_DEGREES.length) ? 0 : r;
    }

    public static int rotationDegrees() {
        return ROTATION_DEGREES[rotationIndex()];
    }

    public static boolean needRtsp() {
        int m = mode();
        return m == MODE_BOTH || m == MODE_RTSP;
    }

    public static boolean needMjpeg() {
        int m = mode();
        return m == MODE_BOTH || m == MODE_MJPEG;
    }
}
