package android.media;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
public final class MediaFormat {
    public static final String KEY_MIME = "mime", KEY_SAMPLE_RATE = "sample-rate",
            KEY_CHANNEL_COUNT = "channel-count", KEY_AAC_PROFILE = "aac-profile",
            KEY_BIT_RATE = "bitrate", KEY_MAX_INPUT_SIZE = "max-input-size";
    private final Map<String,Object> values = new HashMap<String,Object>();
    public static MediaFormat createAudioFormat(String mime, int rate, int channels) {
        MediaFormat format = new MediaFormat();
        format.values.put(KEY_MIME, mime);
        format.setInteger(KEY_SAMPLE_RATE, rate);
        format.setInteger(KEY_CHANNEL_COUNT, channels);
        return format;
    }
    public void setInteger(String key, int value) { values.put(key, value); }
    public int getInteger(String key) { return (Integer) values.get(key); }
    public String getString(String key) { return (String) values.get(key); }
    public ByteBuffer getByteBuffer(String key) { return (ByteBuffer) values.get(key); }
    public void setByteBuffer(String key, ByteBuffer value) { values.put(key, value); }
    public boolean containsKey(String key) { return values.containsKey(key); }
}
