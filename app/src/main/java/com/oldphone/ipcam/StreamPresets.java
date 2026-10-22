package com.oldphone.ipcam;

/** Stream resolution presets and the recording size that follows them. */
public final class StreamPresets {
    public static final int[][] SIZES = {
            {1280, 720},
            {1920, 1080},
            {2560, 1440},
    };
    public static final String[] LABELS = {"720p", "1080p", "1440p"};

    private StreamPresets() {}

    public static int indexOf(int width, int height) {
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i][0] == width && SIZES[i][1] == height) return i;
        }
        return -1;
    }

    public static String label(int width, int height) {
        int index = indexOf(width, height);
        if (index >= 0) return LABELS[index];
        return width + "×" + height;
    }

    public static String pixels(int width, int height) {
        return width + "×" + height;
    }

    /** Follow-stream stores no fixed record size. The next segment uses the stream size. */
    public static int[] recordPixels(boolean followStream, int recordWidth, int recordHeight,
                                     int streamWidth, int streamHeight) {
        if (followStream || recordWidth < 16 || recordHeight < 16) {
            return new int[] {streamWidth, streamHeight};
        }
        return new int[] {recordWidth, recordHeight};
    }

    public static boolean followEndsSegment(boolean followStream, int previousWidth, int previousHeight,
                                            int nextWidth, int nextHeight) {
        return followStream && (previousWidth != nextWidth || previousHeight != nextHeight);
    }

    public static String recordLabel(boolean followStream, int recordWidth, int recordHeight,
                                     int streamWidth, int streamHeight) {
        if (followStream) return "跟随推流 · " + label(streamWidth, streamHeight);
        return label(recordWidth, recordHeight);
    }

    public static String recordCaption(boolean followStream) {
        return followStream ? "推流切换后，下一段用新分辨率" : "固定，不随推流变化";
    }

    public static String qualityLabel(int bitrateIndex) {
        if (bitrateIndex <= 1) return "标准";
        if (bitrateIndex == 2) return "高";
        return "超高";
    }

    /** Existing bitrate index. 标准 = 1 Mbps, 高 = 1.5 Mbps, 超高 = 4 Mbps. */
    public static int bitrateIndex(String quality) {
        if ("标准".equals(quality)) return 1;
        if ("超高".equals(quality)) return 4;
        return 2;
    }
}
