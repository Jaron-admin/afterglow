package com.oldphone.ipcam;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 极简配置存储。全部走 SharedPreferences，避免引入任何第三方依赖。
 */
public final class Prefs {

    private static final String FILE = "ipcam_prefs";
    private static final String KEY_DEVICE_ID = "device_id";

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
    public static final String KEY_RECORDING = "recording_mode";
    public static final String KEY_STORAGE = "recording_target";
    public static final String KEY_QUOTA = "recording_quota_gib";
    public static final String KEY_MICROPHONE = "microphone";
    public static final String KEY_MOTION = "motion_detection";
    public static final String KEY_SOUND = "sound_detection";
    public static final String KEY_SENSITIVITY = "detection_sensitivity";
    public static final String KEY_SEGMENT = "recording_segment_seconds";
    public static final String KEY_PRE_ROLL = "recording_pre_seconds";
    public static final String KEY_POST_ROLL = "recording_post_seconds";
    public static final String KEY_RETENTION = "recording_retention_days";
    public static final String KEY_RESERVE = "recording_reserve_mib";
    public static final String KEY_SEGMENT_SIZE = "recording_segment_mib";
    public static final String KEY_LOOP = "recording_loop";
    public static final String KEY_RECORD_AUDIO = "recording_audio";
    public static final String KEY_SOUND_SENSITIVITY = "sound_sensitivity";
    public static final String KEY_SCHEDULE = "recording_schedule";
    public static final String KEY_SCHEDULE_START = "recording_schedule_start";
    public static final String KEY_SCHEDULE_END = "recording_schedule_end";
    private static final String KEY_CAMERA_KEY = "camera_key_v2";
    private static final String KEY_OUTPUT_WIDTH = "output_width";
    private static final String KEY_OUTPUT_HEIGHT = "output_height";
    private static final String KEY_RECORD_WIDTH = "record_width";
    private static final String KEY_RECORD_HEIGHT = "record_height";
    private static final String KEY_OUTPUT_LAYOUT = "output_layout";
    private static final String KEY_DIRECTION_LOCK = "direction_lock";
    public static final String KEY_MATCH_ALL = "recording_match_all";
    public static final String KEY_LIGHT_THRESHOLD = "light.threshold";
    public static final String KEY_LIGHT_METHOD = "light.method";
    public static final String KEY_RECORD_LIGHT = "record.light.enabled";
    public static final String KEY_STREAM_LIGHT = "stream.light.enabled";
    public static final String KEY_SENSITIVITY_PERCENT = "detection_sensitivity_percent";
    public static final String KEY_WATERMARK = "time_watermark";
    public static final String KEY_HA = "ha.enabled";
    public static final String KEY_HA_AUDIO = "ha.audio";
    public static final String KEY_HA_NOTIFY = "ha.notify";
    private static final String KEY_LOCK_DEVICE = "locked_device_angle";
    private static final String KEY_LOCK_DISPLAY = "locked_display_rotation";

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
    public static final int MODE_LOCAL = 3;

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

    public static final int[] FPS_OPTIONS = {5, 10, 15, 20, 25, 30, 24};
    public static final String[] FPS_LABELS = {"5 fps", "10 fps", "15 fps", "20 fps", "25 fps", "30 fps", "24 fps"};

    public static final int[] BITRATE_OPTIONS = {500_000, 1_000_000, 1_500_000, 2_500_000, 4_000_000};
    public static final String[] BITRATE_LABELS = {"500 kbps", "1 Mbps", "1.5 Mbps", "2.5 Mbps", "4 Mbps"};

    public static final String[] MODE_LABELS = {
            "RTSP + MJPEG",
            "RTSP",
            "MJPEG",
            "仅本地录像",
    };

    private static SharedPreferences sp;

    private Prefs() {
    }

    public static synchronized void init(Context ctx) {
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

    /** Installation identity, independent of DHCP addresses, stream settings and restarts. */
    public static synchronized String deviceId() {
        if (p() == null) throw new IllegalStateException("Prefs.init must run before device discovery");
        String stored = p().getString(KEY_DEVICE_ID, null);
        if (stored != null) {
            try {
                if (UUID.fromString(stored).toString().equals(stored)) return stored;
            } catch (IllegalArgumentException ignored) { }
        }
        String id = UUID.randomUUID().toString();
        // Publishing an apply() write could expose an identity lost by immediate power loss.
        if (!p().edit().putString(KEY_DEVICE_ID, id).commit()) {
            // SharedPreferences changes its memory map even when the disk write fails.
            p().edit().remove(KEY_DEVICE_ID).apply();
            throw new IllegalStateException("Unable to persist device identity");
        }
        return id;
    }

    public static String deviceName() {
        return "Afterglow-" + deviceId().substring(0, 8);
    }

    public static int resIndex() {
        int i = getInt(KEY_RES, 0);
        return (i < 0 || i >= RESOLUTIONS.length) ? 0 : i;
    }

    public static int width() {
        return outputWidth();
    }

    public static int height() {
        return outputHeight();
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
        return (m < 0 || m > MODE_LOCAL) ? MODE_BOTH : m;
    }

    public static int cameraChoice() {
        int c = getInt(KEY_CAMERA, CAM_AUTO);
        return (c < 0 || c > CAM_FRONT) ? CAM_AUTO : c;
    }

    public static int rotationIndex() {
        return manualRotationDegrees() / 90;
    }

    public static int rotationDegrees() {
        return manualRotationDegrees();
    }

    public static String cameraKey() {
        String legacy = cameraChoice() == CAM_FRONT ? "facing:front"
                : cameraChoice() == CAM_BACK ? "facing:back" : "auto";
        return getStr(KEY_CAMERA_KEY, legacy);
    }
    public static void setCameraKey(String key) {
        if (key == null || key.length() == 0) throw new IllegalArgumentException("请选择摄像头");
        setStr(KEY_CAMERA_KEY, key);
    }
    public static int outputWidth() {
        return getInt(KEY_OUTPUT_WIDTH, p() != null && p().contains(KEY_RES)
                ? RESOLUTIONS[resIndex()][0] : 1280);
    }
    public static int outputHeight() {
        return getInt(KEY_OUTPUT_HEIGHT, p() != null && p().contains(KEY_RES)
                ? RESOLUTIONS[resIndex()][1] : 720);
    }
    public static void setOutputSize(int width, int height) {
        if (!FrameGeometry.validOutputSize(width, height))
            throw new IllegalArgumentException("宽高需为 16–4096 的偶数，总像素不超过 829 万");
        if (p() != null) p().edit().putInt(KEY_OUTPUT_WIDTH, width)
                .putInt(KEY_OUTPUT_HEIGHT, height).apply();
    }

    /** 0x0 means recording follows the stream size (legacy behaviour). */
    public static int recordWidth() { return getInt(KEY_RECORD_WIDTH, 0); }
    public static int recordHeight() { return getInt(KEY_RECORD_HEIGHT, 0); }
    public static boolean hasRecordSize() {
        return recordWidth() >= 16 && recordHeight() >= 16;
    }
    public static void setRecordSize(int width, int height) {
        if (width == 0 && height == 0) {
            if (p() != null) p().edit().remove(KEY_RECORD_WIDTH)
                    .remove(KEY_RECORD_HEIGHT).apply();
            return;
        }
        if (!FrameGeometry.validOutputSize(width, height))
            throw new IllegalArgumentException("宽高需为 16–4096 的偶数，总像素不超过 829 万");
        if (p() != null) p().edit().putInt(KEY_RECORD_WIDTH, width)
                .putInt(KEY_RECORD_HEIGHT, height).apply();
    }
    public static int outputLayout() {
        int layout = getInt(KEY_OUTPUT_LAYOUT, outputWidth() >= outputHeight() ? 0 : 1);
        return layout >= 0 && layout <= 2 ? layout : 0;
    }    public static void setOutputLayout(int layout) {
        if (layout < 0 || layout > 2) throw new IllegalArgumentException("Invalid output layout");
        setInt(KEY_OUTPUT_LAYOUT, layout);
    }
    public static int manualRotationDegrees() { return manualRotationDegrees(cameraKey()); }
    public static int manualRotationDegrees(String cameraKey) {
        // v1 stored an absolute raw-frame rotation. It cannot be reused as an
        // offset on top of sensor/device correction without rotating twice.
        int value = getInt("calibration." + cameraKey, 0);
        return value == 90 || value == 180 || value == 270 ? value : 0;
    }
    public static void setManualRotationDegrees(int degrees) {
        setManualRotationDegrees(cameraKey(), degrees);
    }
    public static void setManualRotationDegrees(String cameraKey, int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270)
            throw new IllegalArgumentException("校准角度需为 0°、90°、180° 或 270°");
        setInt("calibration." + cameraKey, degrees);
    }
    public static boolean orientationLocked() { return getBool(KEY_DIRECTION_LOCK, false); }
    public static int lockedDeviceOrientation() { return getInt(KEY_LOCK_DEVICE, 0); }
    public static int lockedDisplayRotation() { return getInt(KEY_LOCK_DISPLAY, 0); }
    public static void setOrientationLock(boolean locked, int deviceDegrees, int displayRotation) {
        if (p() != null) p().edit().putBoolean(KEY_DIRECTION_LOCK, locked)
                .putInt(KEY_LOCK_DEVICE, ((deviceDegrees + 45) / 90 * 90) % 360)
                .putInt(KEY_LOCK_DISPLAY, displayRotation & 3).apply();
    }

    public static boolean needRtsp() {
        int m = mode();
        return m == MODE_BOTH || m == MODE_RTSP;
    }

    public static boolean needMjpeg() {
        int m = mode();
        return m == MODE_BOTH || m == MODE_MJPEG;
    }

    public static int recordingMode() {
        int mode = getInt(KEY_RECORDING, 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }
    public static boolean microphoneEnabled() { return getBool(KEY_MICROPHONE, false); }
    public static boolean motionEnabled() { return getBool(KEY_MOTION, true); }
    public static boolean soundSelected() { return getBool(KEY_SOUND, true); }
    public static boolean soundEnabled() {
        return microphoneEnabled() && soundSelected();
    }
    public static boolean matchAll() { return getBool(KEY_MATCH_ALL, false); }
    public static int lightThreshold() {
        return LightPolicy.clampPercent(getInt(KEY_LIGHT_THRESHOLD, 30));
    }
    public static String lightMethod() { return LightPolicy.method(getStr(KEY_LIGHT_METHOD, LightPolicy.FRAME)); }
    public static boolean recordLightEnabled() { return getBool(KEY_RECORD_LIGHT, false); }
    public static boolean streamLightEnabled() { return getBool(KEY_STREAM_LIGHT, false); }
    public static int sensitivityPercent() {
        if (p() != null && p().contains(KEY_SENSITIVITY_PERCENT)) {
            return LightPolicy.clampPercent(getInt(KEY_SENSITIVITY_PERCENT, 60));
        }
        int level = sensitivity();
        return level <= 0 ? 20 : level >= 2 ? 80 : 60;
    }
    public static void setSensitivityPercent(int percent) {
        int clamped = LightPolicy.clampPercent(percent);
        int level = clamped >= 70 ? 2 : clamped >= 40 ? 1 : 0;
        if (p() != null) p().edit().putInt(KEY_SENSITIVITY_PERCENT, clamped)
                .putInt(KEY_SENSITIVITY, level).apply();
    }
    public static boolean watermarkEnabled() { return getBool(KEY_WATERMARK, true); }
    public static boolean haEnabled() { return getBool(KEY_HA, true); }
    public static boolean haAudioEnabled() { return getBool(KEY_HA_AUDIO, true); }
    public static boolean haNotifyEnabled() { return getBool(KEY_HA_NOTIFY, true); }
    public static int sensitivity() {
        return DetectionLevels.clamp(getInt(KEY_SENSITIVITY, 1));
    }
    /** A missing sound level keeps the current motion sensitivity. */
    public static int soundSensitivity() {
        boolean hasOwn = p() != null && p().contains(KEY_SOUND_SENSITIVITY);
        return DetectionLevels.soundOrMotion(hasOwn, getInt(KEY_SOUND_SENSITIVITY, sensitivity()), sensitivity());
    }
    public static RecordingSchedule recordingSchedule() {
        if (!getBool(KEY_SCHEDULE, false)) return RecordingSchedule.allDay();
        try {
            return RecordingSchedule.daily(getInt(KEY_SCHEDULE_START, 0), getInt(KEY_SCHEDULE_END, 0));
        } catch (IllegalArgumentException invalid) {
            return RecordingSchedule.allDay();
        }
    }
    public static String recordingTarget() { return getStr(KEY_STORAGE, ""); }
    public static long recordingQuota() {
        return Math.max(1, Math.min(1024, getInt(KEY_QUOTA, 2))) * 1024L * 1024L * 1024L;
    }
    public static RecordingOptions recordingOptions() {
        return new RecordingOptions(Math.max(1, recordingMode()),
                getInt(KEY_SEGMENT, 300), getInt(KEY_PRE_ROLL, 5),
                getInt(KEY_POST_ROLL, 15), getInt(KEY_RETENTION, 0), recordingQuota(),
                getInt(KEY_RESERVE, 256) * 1024L * 1024L,
                getInt(KEY_SEGMENT_SIZE, 256) * 1024L * 1024L,
                getBool(KEY_LOOP, true),
                RecordingAudio.fileAudio(microphoneEnabled(), getBool(KEY_RECORD_AUDIO, true)));
    }
}
