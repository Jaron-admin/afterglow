package com.oldphone.ipcam;

/** Immutable, validated recording policy shared by settings, writer and cleanup. */
public final class RecordingOptions {
    public final int mode, segmentSeconds, preRollSeconds, postRollSeconds, retentionDays;
    public final long quotaBytes, reserveBytes, maxSegmentBytes;
    public final boolean loop, withAudio;

    public RecordingOptions(int mode, int segmentSeconds, int preRollSeconds,
            int postRollSeconds, int retentionDays, long quotaBytes, long reserveBytes,
            long maxSegmentBytes, boolean loop, boolean withAudio) {
        if (mode < 1 || mode > 2 || segmentSeconds < 10 || segmentSeconds > 3600
                || preRollSeconds < 0 || preRollSeconds > 30
                || postRollSeconds < 1 || postRollSeconds > 600
                || retentionDays < 0 || retentionDays > 3650
                || quotaBytes <= 0 || reserveBytes <= 0 || maxSegmentBytes <= 0
                || maxSegmentBytes > quotaBytes) {
            throw new IllegalArgumentException("录像参数超出范围，单段上限不能超过总容量");
        }
        this.mode = mode;
        this.segmentSeconds = segmentSeconds;
        this.preRollSeconds = preRollSeconds;
        this.postRollSeconds = postRollSeconds;
        this.retentionDays = retentionDays;
        this.quotaBytes = quotaBytes;
        this.reserveBytes = reserveBytes;
        this.maxSegmentBytes = maxSegmentBytes;
        this.loop = loop;
        this.withAudio = withAudio;
    }

    public static RecordingOptions defaults(int mode, boolean withAudio) {
        return new RecordingOptions(mode, 300, 5, 15, 0, 2L << 30, 256L << 20,
                256L << 20, true, withAudio);
    }
}
