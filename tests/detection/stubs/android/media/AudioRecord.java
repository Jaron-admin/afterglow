package android.media;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Native capture boundary double: reads block until explicit samples arrive or stop unblocks them. */
public final class AudioRecord {
    public static final int STATE_INITIALIZED = 1, RECORDSTATE_RECORDING = 3, ERROR_INVALID_OPERATION = -3;
    public static final LinkedBlockingQueue<short[]> SAMPLES = new LinkedBlockingQueue<short[]>();
    public static volatile AudioRecord last;
    public static volatile boolean denyPermission, badState, failRead, failRelease;
    public static volatile CountDownLatch startGate;
    public final CountDownLatch readEntered = new CountDownLatch(1);
    public volatile boolean recording, released;
    public volatile int stopCount, releaseCount;
    public volatile Thread releaseThread;
    public static void reset() {
        SAMPLES.clear();
        last = null;
        denyPermission = badState = failRead = failRelease = false;
        startGate = null;
    }
    public static int getMinBufferSize(int sampleRate, int channels, int encoding) { return 640; }
    public AudioRecord(int source, int sampleRate, int channels, int encoding, int bufferSize) {
        if (denyPermission) throw new SecurityException("microphone permission denied");
        last = this;
    }
    public int getState() { return badState ? 0 : STATE_INITIALIZED; }
    public void startRecording() {
        if (startGate != null) {
            try { startGate.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        recording = true;
    }
    public int getRecordingState() { return recording ? RECORDSTATE_RECORDING : 1; }
    public int read(short[] buffer, int offset, int size) {
        readEntered.countDown();
        while (recording && !released) {
            if (failRead) return ERROR_INVALID_OPERATION;
            try {
                short[] next = SAMPLES.poll(20, TimeUnit.MILLISECONDS);
                if (next != null) {
                    int count = Math.min(size, next.length);
                    System.arraycopy(next, 0, buffer, offset, count);
                    return count;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ERROR_INVALID_OPERATION;
            }
        }
        return ERROR_INVALID_OPERATION;
    }
    public void stop() { recording = false; stopCount++; }
    public void release() {
        released = true; recording = false; releaseCount++; releaseThread = Thread.currentThread();
        if (failRelease) throw new IllegalStateException("recorder release failed");
    }
}
