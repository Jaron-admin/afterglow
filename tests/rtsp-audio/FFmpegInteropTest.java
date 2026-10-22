package com.oldphone.ipcam;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Optional real-player smoke test: synthetic media only, no device camera or microphone. */
public final class FFmpegInteropTest {
    static File output;
    static String ffmpeg, ffprobe;
    static final class Video {
        final byte[] bytes; final boolean key;
        Video(byte[] bytes, boolean key) { this.bytes = bytes; this.key = key; }
    }
    public static void main(String[] args) throws Exception {
        output = new File(args[0]); ffmpeg = args[1]; ffprobe = args[2];
        run("generate-video", ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=15",
                "-t", "8", "-an", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
                "-preset", "ultrafast", "-tune", "zerolatency",
                "-x264-params", "aud=1:keyint=15:min-keyint=15:scenecut=0:repeat-headers=1",
                "-f", "h264", file("synthetic.h264"));
        run("generate-audio", ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=16000",
                "-t", "8", "-ac", "1", "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "32k",
                "-f", "adts", file("synthetic.aac"));
        byte[] h264 = Files.readAllBytes(Paths.get(file("synthetic.h264")));
        List<int[]> nals = H264Encoder.splitAnnexB(h264, h264.length);
        byte[] sps = null, pps = null;
        List<Video> video = new ArrayList<Video>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        boolean key = false, hasPicture = false;
        for (int[] nal : nals) {
            int type = h264[nal[0]] & 31;
            if (type == 9 && current.size() > 0) {
                if (hasPicture) video.add(new Video(current.toByteArray(), key));
                current.reset(); key = false; hasPicture = false;
            }
            if (type == 7) sps = Arrays.copyOfRange(h264, nal[0], nal[0] + nal[1]);
            if (type == 8) pps = Arrays.copyOfRange(h264, nal[0], nal[0] + nal[1]);
            if (type == 5) key = true;
            hasPicture |= type == 1 || type == 5;
            current.write(new byte[]{0, 0, 0, 1}); current.write(h264, nal[0], nal[1]);
        }
        if (hasPicture) video.add(new Video(current.toByteArray(), key));
        check(sps != null && pps != null && video.size() == 120, "baseline synthetic video fixture");
        List<byte[]> audio = adts(Files.readAllBytes(Paths.get(file("synthetic.aac"))));
        check(audio.size() > 100, "synthetic AAC fixture");
        List<String> summaries = new ArrayList<String>();
        for (String transport : new String[]{"tcp", "udp"})
            summaries.add(testTransport(transport, video, audio, sps, pps));
        Files.write(Paths.get(file("RESULTS.txt")), summaries, StandardCharsets.UTF_8);
        for (String summary : summaries) System.out.println(summary);
        System.out.println("Synthetic fixtures, decoded outputs, ffprobe reports and logs: " + output.getAbsolutePath());
    }
    static String testTransport(String transport, List<Video> videos, List<byte[]> audios,
                                byte[] sps, byte[] pps) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        final RtspServer server = new RtspServer(port);
        server.setVideoInfo(320, 240, 300000);
        server.setCodecConfig(sps, pps);
        server.setAudioConfig(16000, 1, new byte[]{0x14, 0x08});
        AtomicBoolean publish = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        server.start();
        Thread producer = new Thread(() -> {
            try {
                long start = System.nanoTime() / 1000, v = 0, a = 0;
                while (publish.get()) {
                    long videoPts = start + v * 1000000L / 15;
                    long audioPts = start + a * 1024L * 1000000L / 16000;
                    boolean sendVideo = videoPts <= audioPts;
                    long due = sendVideo ? videoPts : audioPts;
                    long wait = due - System.nanoTime() / 1000;
                    if (wait > 0) { Thread.sleep(Math.max(1, wait / 1000)); continue; }
                    if (sendVideo) {
                        Video frame = videos.get((int)(v++ % videos.size()));
                        server.pushFrame(frame.bytes, videoPts, frame.key);
                    } else {
                        server.pushAudio(audios.get((int)(a++ % audios.size())), audioPts);
                        server.pushPcm(new short[320], 320, audioPts);
                    }
                }
            } catch (InterruptedException expected) {
                if (publish.get()) failure.set(expected);
            } catch (Throwable unexpected) { failure.set(unexpected); }
        }, "synthetic-av");
        producer.start();
        try {
            String decoded = file("decoded-" + transport + ".mkv");
            run("decode-" + transport, ffmpeg, "-hide_banner", "-loglevel", "warning", "-xerror", "-y",
                    "-rtsp_transport", transport, "-i", "rtsp://127.0.0.1:" + port + "/live",
                    "-t", "5", "-map", "0:v:0", "-map", "0:a:0",
                    "-c:v", "ffv1", "-c:a", "pcm_s16le", decoded);
            check(failure.get() == null, "producer failed: " + failure.get());
            String errors = new String(Files.readAllBytes(Paths.get(file("decode-" + transport + ".log"))), StandardCharsets.UTF_8).trim();
            check(errors.isEmpty(), "FFmpeg emitted warnings/errors: " + errors);
            String probe = run("probe-" + transport, ffprobe, "-v", "error", "-count_frames",
                    "-show_entries", "stream=codec_type,codec_name,nb_read_frames,sample_rate,channels,width,height",
                    "-of", "default", decoded);
            check(probe.contains("codec_type=video") && probe.contains("codec_type=audio"), "both output streams required");
            check(probe.contains("width=320") && probe.contains("height=240")
                    && probe.contains("sample_rate=16000") && probe.contains("channels=1"), "expected stream properties");
            List<Integer> counts = new ArrayList<Integer>();
            for (String line : probe.split("\\R")) if (line.startsWith("nb_read_frames="))
                counts.add(Integer.parseInt(line.substring("nb_read_frames=".length())));
            check(counts.size() == 2 && counts.get(0) >= 50 && counts.get(1) >= 50, "decoded audio/video frames: " + counts);
            String raw = file("decoded-" + transport + ".pcm");
            run("pcm-" + transport, ffmpeg, "-v", "error", "-y", "-i", decoded, "-map", "0:a:0", "-f", "s16le", raw);
            byte[] pcm = Files.readAllBytes(Paths.get(raw));
            boolean nonzero = false;
            for (byte value : pcm) if (value != 0) { nonzero = true; break; }
            check(pcm.length >= 128000 && nonzero, "decoded PCM must contain actual sound");
            return "PASS " + transport.toUpperCase(Locale.US) + ": 5 s, 320x240 video frames=" + counts.get(0)
                    + ", 16000 Hz mono audio frames=" + counts.get(1) + ", PCM bytes=" + pcm.length
                    + ", nonzero audio, no FFmpeg warnings/errors";
        } finally {
            publish.set(false); producer.interrupt(); producer.join(2000); server.stop();
        }
    }
    static List<byte[]> adts(byte[] data) {
        List<byte[]> frames = new ArrayList<byte[]>();
        for (int at = 0; at < data.length;) {
            check((data[at] & 255) == 255 && (data[at+1] & 0xf6) == 0xf0, "ADTS sync");
            int length = ((data[at+3] & 3) << 11) | ((data[at+4] & 255) << 3) | ((data[at+5] & 224) >> 5);
            int header = (data[at+1] & 1) == 1 ? 7 : 9;
            check(length > header && at + length <= data.length, "complete ADTS frame");
            frames.add(Arrays.copyOfRange(data, at + header, at + length)); at += length;
        }
        return frames;
    }
    static String run(String name, String... command) throws Exception {
        File log = new File(file(name + ".log"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor();
            throw new AssertionError(name + " exceeded 30 s; " + log);
        }
        String result = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        check(process.exitValue() == 0, name + " failed: " + result);
        return result;
    }
    static String file(String name) { return new File(output, name).getAbsolutePath(); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
