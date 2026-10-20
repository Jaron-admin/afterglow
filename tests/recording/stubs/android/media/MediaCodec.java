package android.media;
public class MediaCodec {
    public static final int BUFFER_FLAG_KEY_FRAME = 1, BUFFER_FLAG_CODEC_CONFIG = 2;
    public static class BufferInfo {
        public int offset, size, flags; public long presentationTimeUs;
        public void set(int offset, int size, long pts, int flags) {
            this.offset = offset; this.size = size; this.presentationTimeUs = pts; this.flags = flags;
        }
    }
}
