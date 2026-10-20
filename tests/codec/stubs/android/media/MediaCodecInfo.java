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
}
