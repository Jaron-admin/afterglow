package android.media;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Codec boundary double with delayed output, EOS, and explicit native-buffer release accounting. */
public final class MediaCodec {
    public static final int CONFIGURE_FLAG_ENCODE = 1, BUFFER_FLAG_KEY_FRAME = 1,
            BUFFER_FLAG_CODEC_CONFIG = 2, BUFFER_FLAG_END_OF_STREAM = 4,
            INFO_TRY_AGAIN_LATER = -1, INFO_OUTPUT_FORMAT_CHANGED = -2, INFO_OUTPUT_BUFFERS_CHANGED = -3;
    public static volatile MediaCodec last;
    public static boolean delayUntilEos, failOutputBuffer, neverFinish;
    public static final class BufferInfo { public int offset, size, flags; public long presentationTimeUs; }
    private static final class Frame { long pts; int flags; byte[] data; }
    private final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();
    private final ByteBuffer input = ByteBuffer.allocate(1024), output = ByteBuffer.allocate(1024);
    private boolean eosQueued, formatSent;
    public int eosCount, releasedBuffers, releaseCount;
    public boolean released;
    public static void reset() { last = null; delayUntilEos = failOutputBuffer = neverFinish = false; }
    public static MediaCodec createByCodecName(String name) throws IOException { return last = new MediaCodec(); }
    public void configure(MediaFormat format, android.view.Surface surface, MediaCrypto crypto, int flags) { }
    public void start() { }
    public ByteBuffer[] getInputBuffers() { return new ByteBuffer[]{input}; }
    public ByteBuffer[] getOutputBuffers() { return new ByteBuffer[]{output}; }
    public ByteBuffer getInputBuffer(int index) { return input; }
    public ByteBuffer getOutputBuffer(int index) {
        if (failOutputBuffer) throw new IllegalStateException("output buffer acquisition failed");
        return output;
    }
    public int dequeueInputBuffer(long timeout) { return 0; }
    public void queueInputBuffer(int index, int offset, int size, long pts, int flags) {
        Frame frame = new Frame();
        frame.pts = pts;
        frame.flags = flags;
        frame.data = size > 0 ? new byte[]{0, 0, 0, 1, 0x65, (byte) 0xb8} : new byte[0];
        if ((flags & BUFFER_FLAG_END_OF_STREAM) != 0) { eosCount++; eosQueued = true; }
        frames.add(frame);
    }
    public int dequeueOutputBuffer(BufferInfo info, long timeout) {
        if (neverFinish || (delayUntilEos && !eosQueued) || frames.isEmpty()) return INFO_TRY_AGAIN_LATER;
        if (!formatSent) { formatSent = true; return INFO_OUTPUT_FORMAT_CHANGED; }
        Frame frame = frames.poll();
        output.clear();
        output.put(frame.data);
        info.offset = 0; info.size = frame.data.length; info.flags = frame.flags;
        info.presentationTimeUs = frame.pts;
        return 0;
    }
    public MediaFormat getOutputFormat() {
        MediaFormat format = new MediaFormat();
        format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 0x67, 0x42}));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 0x68, 0x10}));
        return format;
    }
    public void releaseOutputBuffer(int index, boolean render) { releasedBuffers++; }
    public void stop() { }
    public void release() { released = true; releaseCount++; }
}
