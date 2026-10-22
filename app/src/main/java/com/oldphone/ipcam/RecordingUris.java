package com.oldphone.ipcam;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * content URI path segments for a registered clip. The target id is hex-encoded
 * so filesystem separators stay inside one segment.
 */
public final class RecordingUris {
    public static final Pattern CLIP_ID = Pattern.compile(
            "[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static final class Parsed {
        public final String targetId, clipId;
        Parsed(String targetId, String clipId) {
            this.targetId = targetId;
            this.clipId = clipId;
        }
    }

    private RecordingUris() {}

    public static String encodeTarget(String targetId) {
        if (targetId == null || targetId.length() == 0)
            throw new IllegalArgumentException("缺少存储位置");
        byte[] bytes = targetId.getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    public static String decodeTarget(String hex) {
        if (hex == null || hex.length() == 0 || (hex.length() & 1) != 0)
            throw new IllegalArgumentException("存储位置无效");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("存储位置无效");
            bytes[i] = (byte) ((high << 4) + low);
        }
        String target = new String(bytes, StandardCharsets.UTF_8);
        if (target.length() == 0) throw new IllegalArgumentException("存储位置无效");
        return target;
    }

    public static Parsed parse(List<String> segments) {
        if (segments == null || segments.size() != 2)
            throw new IllegalArgumentException("录像地址无效");
        String target = decodeTarget(segments.get(0));
        String clip = segments.get(1);
        if (clip == null || !CLIP_ID.matcher(clip).matches())
            throw new IllegalArgumentException("录像地址无效");
        return new Parsed(target, clip);
    }
}
