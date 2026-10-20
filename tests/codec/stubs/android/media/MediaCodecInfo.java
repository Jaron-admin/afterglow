package android.media;
public final class MediaCodecInfo {
    private final String name;
    private final boolean encoder;
    private final String[] types;
    public MediaCodecInfo(String name, boolean encoder, String... types) {
        this.name = name; this.encoder = encoder; this.types = types;
    }
    public String getName() { return name; }
    public boolean isEncoder() { return encoder; }
    public String[] getSupportedTypes() { return types; }
    public CodecCapabilities getCapabilitiesForType(String type) { return new CodecCapabilities(); }
    public static final class CodecCapabilities {
        public static final int COLOR_FormatYUV420SemiPlanar = 21, COLOR_FormatYUV420Planar = 19,
                COLOR_FormatYUV420PackedSemiPlanar = 39, COLOR_FormatYUV420PackedPlanar = 20;
        public int[] colorFormats = new int[]{COLOR_FormatYUV420SemiPlanar};
    }
}
