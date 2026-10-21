package com.oldphone.ipcam;

/** Presets shown on the recording screen. Any other saved value stays as 自定义. */
public final class RecordingChoices {
    public static final int[] SEGMENT_SECONDS = {60, 300, 600, 1800, 3600};
    public static final int[] PRE_ROLL_SECONDS = {0, 3, 5, 10, 15, 30};
    public static final int[] POST_ROLL_SECONDS = {10, 15, 30, 60, 120, 300};
    public static final int[] QUOTA_GIB = {1, 2, 5, 10, 32};
    public static final int[] RETENTION_DAYS = {0, 3, 7, 14, 30};
    public static final int[] SEGMENT_MIB = {128, 256, 512, 1024};

    private RecordingChoices() {}

    /** @return preset index, or -1 when the saved number must be shown as 自定义. */
    public static int indexOf(int[] presets, int value) {
        if (presets == null) return -1;
        for (int i = 0; i < presets.length; i++) if (presets[i] == value) return i;
        return -1;
    }
}
