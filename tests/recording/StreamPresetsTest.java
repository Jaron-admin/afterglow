package com.oldphone.ipcam;

public final class StreamPresetsTest {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check("1080p".equals(StreamPresets.label(1920, 1080)), "1080 label");
        check("720p".equals(StreamPresets.label(1280, 720)), "720 label");
        check("1440p".equals(StreamPresets.label(2560, 1440)), "1440 label");
        check(StreamPresets.indexOf(1920, 1080) == 1, "preset index");

        int[] followed = StreamPresets.recordPixels(true, 0, 0, 1920, 1080);
        check(followed[0] == 1920 && followed[1] == 1080, "follow uses the current stream size");
        check(StreamPresets.followEndsSegment(true, 1920, 1080, 1280, 720),
                "changing the stream size ends the open follow-stream segment");
        int[] next = StreamPresets.recordPixels(true, 0, 0, 1280, 720);
        check(next[0] == 1280 && next[1] == 720, "the next segment uses the new stream size");
        check(!StreamPresets.followEndsSegment(true, 1280, 720, 1280, 720),
                "the same size does not end a segment");

        int[] fixed = StreamPresets.recordPixels(false, 2560, 1440, 1280, 720);
        check(fixed[0] == 2560 && fixed[1] == 1440, "a fixed record size ignores the stream size");
        check(!StreamPresets.followEndsSegment(false, 1920, 1080, 1280, 720),
                "a fixed record size does not end because the stream changed");

        check("跟随推流 · 1080p".equals(StreamPresets.recordLabel(true, 0, 0, 1920, 1080)),
                "follow label");
        check("720p".equals(StreamPresets.recordLabel(false, 1280, 720, 1920, 1080)), "fixed label");
        check("推流切换后，下一段用新分辨率".equals(StreamPresets.recordCaption(true)), "follow caption");
        check("固定，不随推流变化".equals(StreamPresets.recordCaption(false)), "fixed caption");
        check(StreamPresets.bitrateIndex("标准") == 1, "standard bitrate");
        check(StreamPresets.bitrateIndex("高") == 2, "high bitrate stays the previous default");
        check(StreamPresets.bitrateIndex("超高") == 4, "max bitrate");
        check("高".equals(StreamPresets.qualityLabel(2)), "default quality label");
    }
}
