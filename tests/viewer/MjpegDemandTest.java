package com.oldphone.ipcam;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Uses real HTTP sockets: only accepted live-stream requests should drive JPEG production. */
public final class MjpegDemandTest {
    private static final String AUTH = "Authorization: Basic YWRtaW46c2VjcmV0\r\n";
    private static final String ID = "1700000000000-12345678-1234-1234-1234-123456789abc";

    public static void main(String[] args) throws Exception {
        onlyAuthenticatedStreamsCreateDemand();
        pendingRecordingDoesNotCreateDemand();
        System.out.println("PASS: MJPEG demand excludes idle/incomplete HTTP, unauthorized video and pending recordings; authenticated video/stream and stop lifecycle");
    }

    private static void onlyAuthenticatedStreamsCreateDemand() throws Exception {
        MjpegServer server = new MjpegServer(freePort());
        server.setBasicAuth("admin", "secret");
        server.start();
        try (Socket idle = connect(server)) {
            awaitClients(server, 1);
            check(server.hasClients() && server.clientCount() == 1, "general HTTP counts still include idle sockets");
            check(!server.hasStreamClients(), "idle HTTP sockets must not request JPEG production");
            send(idle, "GET /video HTTP/1.1\r\nHost: localhost\r\n" + AUTH);
            noDemandDuring(server, 50);
            try (Socket unauthorized = connect(server)) {
                send(unauthorized, "GET /video HTTP/1.1\r\nHost: localhost\r\n\r\n");
                check(headers(unauthorized).startsWith("HTTP/1.1 401"), "video must reject missing credentials");
                check(!server.hasStreamClients(), "unauthorized video must not request JPEG production");
            }
            try (Socket video = connect(server)) {
                send(video, "GET /video HTTP/1.1\r\nHost: localhost\r\n" + AUTH + "\r\n");
                check(headers(video).contains("multipart/x-mixed-replace"), "authorized video should begin streaming");
                // No frame has been pushed: demand must be visible before the first JPEG exists.
                check(server.hasStreamClients(), "authenticated video must request the first JPEG");
                server.stop();
                check(!server.hasStreamClients() && !server.hasClients(), "stop must clear live and general HTTP clients");
            }
        } finally { server.stop(); }

        server.start();
        try (Socket stream = connect(server)) {
            check(!server.hasStreamClients(), "restart must not retain demand from the previous session");
            send(stream, "GET /stream HTTP/1.1\r\nHost: localhost\r\n" + AUTH + "\r\n");
            check(headers(stream).startsWith("HTTP/1.1 200"), "stream alias should be accepted");
            check(server.hasStreamClients(), "authenticated stream alias must create demand");
        } finally { server.stop(); }
        check(!server.hasStreamClients(), "stopped stream alias must leave no demand");
    }

    private static void pendingRecordingDoesNotCreateDemand() throws Exception {
        final CountDownLatch resolving = new CountDownLatch(1), release = new CountDownLatch(1);
        final File clip = File.createTempFile("afterglow-demand-", ".mp4");
        Files.write(clip.toPath(), new byte[]{1,2,3});
        MjpegServer server = new MjpegServer(freePort());
        server.setBasicAuth("admin", "secret");
        server.setRecordings(new RecordingHttpApi(new RecordingHttpApi.Repository() {
            public List<RecordingHttpApi.Clip> list() { return Collections.emptyList(); }
            public File file(String id) throws IOException {
                resolving.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test storage timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException(interrupted);
                }
                return clip;
            }
        }));
        server.start();
        try (Socket recording = connect(server)) {
            send(recording, "GET /api/recordings/" + ID + " HTTP/1.1\r\nHost: localhost\r\n" + AUTH + "\r\n");
            check(resolving.await(2, TimeUnit.SECONDS), "real recording request must reach storage");
            check(server.hasClients() && server.clientCount() == 1, "pending recording remains a general HTTP client");
            check(!server.hasStreamClients(), "recording playback must not request live JPEG production");
            release.countDown();
            check(headers(recording).startsWith("HTTP/1.1 200"), "recording continues normally after storage resumes");
            InputStream body = recording.getInputStream();
            check(body.read() == 1 && body.read() == 2 && body.read() == 3 && body.read() == -1,
                    "recording response must preserve completed clip bytes");
        } finally { release.countDown(); server.stop(); clip.delete(); }
    }

    private static Socket connect(MjpegServer server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(2000);
        return socket;
    }
    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private static String headers(Socket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int suffix = 0;
        for (int next; (next = socket.getInputStream().read()) != -1;) {
            bytes.write(next); suffix = (suffix << 8) | next;
            if (suffix == 0x0d0a0d0a) return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        throw new AssertionError("HTTP response ended before its headers");
    }
    private static void awaitClients(MjpegServer server, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.clientCount() != expected && System.nanoTime() < deadline) Thread.sleep(2);
        check(server.clientCount() == expected, "expected accepted HTTP clients: " + expected);
    }
    private static void noDemandDuring(MjpegServer server, long milliseconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        do {
            check(!server.hasStreamClients(), "incomplete HTTP request must not create stream demand");
            Thread.sleep(2);
        } while (System.nanoTime() < deadline);
    }
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
