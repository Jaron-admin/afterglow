package com.oldphone.ipcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One PCM16 mono microphone feeds both detection and AAC-LC. Codec and recorder release
 * belong to the capture worker; close first stops the recorder to unblock its blocking read.
 * All callbacks run on this worker. The listener must not block on disk or network I/O.
 */
public final class AudioCapture {
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;
    private static final String MIME = "audio/mp4a-latm";
    private static final int SAMPLES_PER_READ = 320; // 20 ms, not tied to the 1024-sample AAC frame.
    /** Retained across service/AudioCapture replacements until the old worker finishes releasing. */
    private static Run deviceOwner;
    private Run current;

    public interface Listener {
        void onFormat(MediaFormat format);
        /** The listener owns this byte array. It is one raw AAC access unit, with no ADTS header. */
        void onEncodedFrame(byte[] rawAac, long ptsUs);
        /** Borrowed read buffer; consume synchronously and do not modify or retain it. */
        void onPcm(short[] samples, int count, long ptsUs);
        void onError(String message);
    }

    public static byte[] audioSpecificConfig() { return new byte[]{0x14, 0x08}; }

    public void start(Listener listener) throws IOException {
        if (listener == null) throw new IllegalArgumentException("listener");
        final Run run;
        synchronized (this) {
            if (current != null && current.finished.getCount() != 0) {
                throw new IOException("Previous microphone capture is still active");
            }
            run = new Run(listener);
            synchronized (AudioCapture.class) {
                if (deviceOwner != null) {
                    throw new IOException("Previous microphone capture is still running or releasing");
                }
                deviceOwner = run;
            }
            current = run;
            try { run.thread.start(); }
            catch (RuntimeException error) {
                synchronized (AudioCapture.class) {
                    if (deviceOwner == run) deviceOwner = null;
                }
                run.finished.countDown();
                throw new IOException("Could not start microphone worker", error);
            }
        }
        try {
            if (!run.startup.await(8, TimeUnit.SECONDS)) {
                run.requestStop();
                throw new IOException("Microphone startup timed out");
            }
        } catch (InterruptedException error) {
            run.requestStop();
            Thread.currentThread().interrupt();
            throw new IOException("Microphone startup interrupted", error);
        }
        if (run.startupFailure != null) throw run.startupFailure;
        if (run.stopping) throw new IOException("Microphone startup cancelled");
    }

    /**
     * Idempotent. When invoked from a callback it requests shutdown and returns; the same
     * worker then completes the drain/release. External callers wait for that completion.
     */
    public void close() {
        final Run run;
        synchronized (this) { run = current; }
        if (run == null) return;
        run.requestStop();
        if (Thread.currentThread() == run.thread) return;
        try {
            if (!run.finished.await(4, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Microphone is still releasing; cannot start another capture");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping microphone", error);
        }
    }

    private static final class Run implements Runnable {
        final Listener listener;
        final Thread thread;
        final CountDownLatch startup = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final Object recorderLock = new Object();
        volatile boolean stopping;
        volatile IOException startupFailure;
        AudioRecord recorder;
        MediaCodec codec;
        ByteBuffer[] inputBuffers, outputBuffers;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean codecStarted, captureStarted, outputFormatReady, outputEnded, eosRequested;
        long originUs, capturedSamples;

        Run(Listener listener) {
            this.listener = listener;
            thread = new Thread(this, "AfterglowAudio");
            thread.setDaemon(true);
        }

        void requestStop() {
            stopping = true;
            synchronized (recorderLock) {
                if (recorder != null) {
                    try { recorder.stop(); }
                    catch (IllegalStateException ignored) { /* not recording yet, or already stopped */ }
                }
            }
        }

        @Override public void run() {
            IOException failure = null;
            try {
                initialize();
                if (stopping) throw new IOException("Microphone startup cancelled");
                captureStarted = true;
                startup.countDown();
                short[] pcm = new short[SAMPLES_PER_READ];
                long lastDataNs = System.nanoTime();
                while (!stopping) {
                    int count;
                    try { count = recorder.read(pcm, 0, pcm.length); }
                    catch (IllegalStateException error) {
                        if (stopping) break;
                        throw error;
                    }
                    if (stopping) break;
                    if (count < 0) throw new IOException("Microphone read failed (" + count + ")");
                    if (count == 0) {
                        if (System.nanoTime() - lastDataNs > TimeUnit.SECONDS.toNanos(2)) {
                            throw new IOException("Microphone is not returning audio");
                        }
                        continue;
                    }
                    lastDataNs = System.nanoTime();
                    long ptsUs = samplePts(capturedSamples);
                    listener.onPcm(pcm, count, ptsUs);
                    if (stopping) break;
                    queuePcm(pcm, count);
                    capturedSamples += count;
                    drain(0);
                }
                finishEncoding();
            } catch (Exception error) {
                failure = error instanceof IOException ? (IOException) error
                        : new IOException("Microphone/AAC failure: " + error.getMessage(), error);
            } finally {
                // Do not release AudioRecord concurrently with close's unblock operation.
                synchronized (recorderLock) {
                    if (recorder != null) {
                        try { recorder.stop(); } catch (IllegalStateException ignored) { }
                        try { recorder.release(); }
                        catch (RuntimeException error) {
                            if (failure == null) failure = new IOException("Microphone release failed", error);
                        }
                        recorder = null;
                    }
                }
                if (codec != null) {
                    if (codecStarted) {
                        try { codec.stop(); }
                        catch (IllegalStateException error) {
                            if (failure == null) failure = new IOException("AAC encoder stop failed", error);
                        }
                    }
                    try { codec.release(); }
                    catch (IllegalStateException error) {
                        if (failure == null) failure = new IOException("AAC encoder release failed", error);
                    }
                    codec = null;
                }
                try {
                    if (!captureStarted) {
                        startupFailure = failure != null ? failure : new IOException("Microphone startup cancelled");
                    } else if (failure != null) {
                        try { listener.onError(failure.getMessage()); }
                        catch (RuntimeException ignored) { /* completion must survive a failed UI callback */ }
                    }
                } finally {
                    synchronized (AudioCapture.class) {
                        if (deviceOwner == this) deviceOwner = null;
                    }
                    finished.countDown();
                    startup.countDown();
                }
            }
        }

        private void initialize() throws IOException {
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("16 kHz mono microphone capture is unavailable");
            if (stopping) throw new IOException("Microphone startup cancelled");
            codec = MediaCodec.createEncoderByType(MIME);
            MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            codecStarted = true;
            // These APIs are present since API16 and still supported on new releases.
            inputBuffers = codec.getInputBuffers();
            outputBuffers = codec.getOutputBuffers();
            synchronized (recorderLock) {
                if (stopping) throw new IOException("Microphone startup cancelled");
                try {
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minimum * 2, SAMPLES_PER_READ * 2 * 4));
                } catch (SecurityException error) {
                    throw new IOException("麦克风权限被拒绝或已撤销", error);
                }
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Microphone could not be initialized");
            }
            // The origin is in the exact clock domain used by video; subsequent timestamps
            // advance by captured samples rather than by when a delayed read happened to finish.
            originUs = System.nanoTime() / 1000;
            try { recorder.startRecording(); }
            catch (SecurityException error) {
                throw new IOException("麦克风权限被拒绝或已撤销", error);
            }
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Microphone did not enter recording state");
            }
        }

        private long samplePts(long samples) {
            return originUs + samples * 1000000 / SAMPLE_RATE;
        }

        private void queuePcm(short[] pcm, int count) throws IOException {
            int consumed = 0;
            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (consumed < count) {
                int index = codec.dequeueInputBuffer(10000);
                if (index < 0) {
                    drain(0);
                    if (System.nanoTime() >= deadlineNs) throw new IOException("AAC input queue stalled");
                    continue;
                }
                ByteBuffer input = inputBuffers[index];
                if (input == null || input.capacity() < 2) throw new IOException("AAC input buffer is invalid");
                input.clear();
                input.order(ByteOrder.LITTLE_ENDIAN);
                int amount = Math.min(count - consumed, input.remaining() / 2);
                for (int i = 0; i < amount; i++) input.putShort(pcm[consumed + i]);
                codec.queueInputBuffer(index, 0, amount * 2,
                        samplePts(capturedSamples + consumed), 0);
                consumed += amount;
                drain(0);
            }
        }

        private void drain(long timeoutUs) throws IOException {
            while (!outputEnded) {
                int index = codec.dequeueOutputBuffer(info, timeoutUs);
                timeoutUs = 0;
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = codec.getOutputFormat();
                    validateFormat(actual);
                    if (outputFormatReady) throw new IOException("AAC format changed during capture");
                    outputFormatReady = true;
                    listener.onFormat(actual);
                    continue;
                }
                if (index < 0) continue;
                try {
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (info.size > 0 && !config) {
                        if (!outputFormatReady) throw new IOException("AAC sample arrived before its track format");
                        ByteBuffer output = outputBuffers[index];
                        if (output == null || info.offset < 0 || info.size > output.capacity() - info.offset) {
                            throw new IOException("AAC output buffer is invalid");
                        }
                        ByteBuffer view = output.duplicate();
                        view.clear();
                        view.position(info.offset);
                        view.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size];
                        view.get(bytes);
                        listener.onEncodedFrame(bytes, info.presentationTimeUs);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (outputEnded && !eosRequested) {
                        throw new IOException("AAC encoder ended before microphone capture stopped");
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false);
                }
            }
        }

        private void finishEncoding() throws IOException {
            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean eosQueued = false;
            while (!outputEnded && System.nanoTime() < deadlineNs) {
                if (!eosQueued) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, samplePts(capturedSamples),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosRequested = true;
                        eosQueued = true;
                    }
                }
                drain(10000);
            }
            if (!outputEnded) throw new IOException("AAC encoder could not finish its last samples");
        }

        private static void validateFormat(MediaFormat actual) throws IOException {
            if (!MIME.equals(actual.getString(MediaFormat.KEY_MIME))
                    || actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) != SAMPLE_RATE
                    || actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != CHANNELS) {
                throw new IOException("AAC encoder returned an unsupported audio format");
            }
            ByteBuffer csd = actual.getByteBuffer("csd-0");
            if (csd == null || csd.remaining() != 2) {
                throw new IOException("AAC encoder did not return the expected AAC-LC configuration");
            }
            ByteBuffer config = csd.duplicate();
            if ((config.get() & 255) != 0x14 || (config.get() & 255) != 0x08) {
                throw new IOException("AAC encoder configuration does not match 16 kHz mono AAC-LC");
            }
        }
    }
}
