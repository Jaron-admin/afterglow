package android.media;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
public final class MediaFormat {
    public static final String KEY_COLOR_FORMAT = "color-format", KEY_BIT_RATE = "bitrate",
            KEY_FRAME_RATE = "frame-rate", KEY_I_FRAME_INTERVAL = "i-frame-interval",
            KEY_MAX_B_FRAMES = "max-bframes";
    private final Map<String, Object> values = new HashMap<String, Object>();
    public static MediaFormat createVideoFormat(String mime, int width, int height) { return new MediaFormat(); }
    public void setInteger(String key, int value) { values.put(key, value); }
    public ByteBuffer getByteBuffer(String key) { return (ByteBuffer) values.get(key); }
    public void setByteBuffer(String key, ByteBuffer value) { values.put(key, value); }
}
