package com.oldphone.ipcam;

import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RecordingManagerTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static File root() throws Exception {
        return Files.createTempDirectory(new File("build").toPath(), "afterglow-manager-test").toFile();
    }
    static RecordingManager manager(File root, boolean audio, RecordingManager.Listener listener) {
        return new RecordingManager(new Context(root), "", RecordingOptions.defaults(1, audio), listener);
    }
    static int count(File root) throws Exception {
        return RecordingStore.open(new Context(root), "", RecordingStore.DEFAULT_QUOTA).list().size();
    }
    static void formats(RecordingManager manager, boolean audio) {
        manager.setVideoFormat(new MediaFormat());
        if (audio) manager.setAudioFormat(new MediaFormat());
    }
    public static void main(String[] args) throws Exception {
        MediaMuxer.reset();
        File tracksRoot = root();
        CountDownLatch entered = new CountDownLatch(1);
        MediaMuxer.enteredWrite = entered;
        RecordingManager tracks = manager(tracksRoot, true, null);
        tracks.setVideoFormat(new MediaFormat());
        tracks.onVideo(new byte[20], 1_000_000, 1);
        tracks.onAudio(new byte[5], 1_010_000, 0);
        Thread.sleep(40);
        check(MediaMuxer.instances.isEmpty(), "must not begin before required audio format");
        tracks.setAudioFormat(new MediaFormat());
        check(entered.await(2, TimeUnit.SECONDS), "writer failed to start");
        tracks.close();
        MediaMuxer av = MediaMuxer.instances.get(0);
        check(av.tracks == 2 && av.samples.size() == 2, "two tracks and all queued samples flushed");
        check(av.samples.get(0).ptsUs == 0 && av.samples.get(1).ptsUs == 10_000,
                "audio/video use a common segment origin");
        check(av.stopped && av.released && count(tracksRoot) == 1 && !tracks.hasFailed(),
                "normal close commits only after native muxer stop");

        MediaMuxer.reset();
        File silentRoot = root();
        RecordingManager silent = manager(silentRoot, false, null);
        formats(silent, true);
        silent.onAudio(new byte[8], 999_000, 0);
        silent.onVideo(new byte[20], 1_000_000, 1);
        silent.onVideo(new byte[20], 1_100_000, 0);
        silent.close();
        check(MediaMuxer.instances.get(0).tracks == 1 && MediaMuxer.instances.get(0).samples.size() == 2,
                "disabled recording audio must ignore formats and samples");

        for (int failure = 0; failure < 3; failure++) {
            MediaMuxer.reset();
            MediaMuxer.failConstruct = failure == 0; MediaMuxer.failWrite = failure == 1; MediaMuxer.failStop = failure == 2;
            File failedRoot = root();
            RecordingManager failing = manager(failedRoot, false, null);
            formats(failing, false);
            // Native/storage exceptions must not escape to encoder/RTSP callback callers.
            failing.onVideo(new byte[20], 1_000_000, 1);
            failing.close();
            check(failing.hasFailed() && count(failedRoot) == 0,
                    "native failure must remain explicit and never publish a playable clip: " + failure);
            if (!MediaMuxer.instances.isEmpty())
                check(MediaMuxer.instances.get(0).released, "native resource release after failure");
        }
        MediaMuxer.reset();
        File slowRoot = root();
        MediaMuxer.enteredWrite = new CountDownLatch(1);
        MediaMuxer.blockWrite = new CountDownLatch(1);
        RecordingManager slow = manager(slowRoot, false, null);
        formats(slow, false); slow.onVideo(new byte[20], 1_000_000, 1);
        check(MediaMuxer.enteredWrite.await(2, TimeUnit.SECONDS), "slow writer was not reached");
        long before = System.nanoTime();
        for (int i = 0; i < 600; i++) slow.onVideo(new byte[32 * 1024], 1_100_000 + i * 1000L, 0);
        check((System.nanoTime() - before) / 1_000_000 < 1000 && slow.hasFailed(),
                "slow disk must fail admission promptly rather than block capture");
        MediaMuxer.blockWrite.countDown(); slow.close();
        check(count(slowRoot) == 0, "overflow must not commit a clip with missing dependencies");
        Context removableContext = new Context(root(), new File[] {root()});
        String sdTarget = RecordingStore.targets(removableContext).get(1).id;
        RecordingStore sd = RecordingStore.open(removableContext, sdTarget, RecordingStore.DEFAULT_QUOTA);
        RecordingStore.Pending p = sd.begin(1, false);
        try (FileOutputStream out = new FileOutputStream(p.file)) { out.write(new byte[] {7, 8, 9}); }
        RecordingStore.Clip saved = sd.complete(p, 1000, "motion");
        Environment.state = Environment.MEDIA_MOUNTED_READ_ONLY;
        try {
            check(RecordingStore.targets(removableContext).get(1).id.equals(sdTarget),
                    "mounted_ro remains visible in actual target inventory");
            RecordingStore readonly = RecordingStore.open(removableContext, sdTarget, RecordingStore.DEFAULT_QUOTA);
            check(readonly.list().size() == 1, "PlatformDisk allows read-only mounted listing");
            try (FileInputStream input = new FileInputStream(readonly.registeredFile(saved.id))) {
                check(input.read() == 7, "PlatformDisk allows read-only source for playback/export");
            }
            try {
                readonly.delete(saved.id);
                throw new AssertionError("PlatformDisk did not reject read-only deletion");
            } catch (IOException expected) {
                check(expected.getMessage().contains("只读"), "PlatformDisk reports explicit read-only cause");
            }
            RecordingManager readonlyWriter = new RecordingManager(removableContext, sdTarget,
                    RecordingOptions.defaults(1, false), null);
            readonlyWriter.close();
            check(readonlyWriter.hasFailed() && readonlyWriter.status().contains("只读")
                    && readonly.list().size() == 1, "read-only recording fails independently and preserves saved clips");
        } finally { Environment.state = Environment.MEDIA_MOUNTED; }
        System.out.println("RecordingManagerTest PASS: real writer/queue/store with native muxer boundary substituted");
    }
}
