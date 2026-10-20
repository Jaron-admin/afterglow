package com.oldphone.ipcam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production HTTP server/client against loopback, without Android runtime dependencies. */
public final class ViewerSocketTest {
    public static void main(String[] args) throws Exception {
        int failures = 0;
        try { restartDropsOldFrames(args[0]); } catch (AssertionError e) { failures++; System.err.println(e); }
        try { clientCanRestartAfterRemoteEof(); } catch (AssertionError e) { failures++; System.err.println(e); }
        try { clientCanReconnectFromErrorCallback(); } catch (AssertionError e) { failures++; System.err.println(e); }
        try { oversizedHeadersAreRejected(); } catch (AssertionError e) { failures++; System.err.println(e); }
        try { streamUsesDeclaredMultipartBoundary(); } catch (AssertionError e) { failures++; System.err.println(e); }
        try { invalidAuthenticationCannotMakeProtectedServerPublic(); } catch (AssertionError e) { failures++; System.err.println(e); }
        if (failures != 0) throw new AssertionError(failures + " socket tests failed");
        System.out.println("PASS: server restart clears snapshots; stopped connections close; client restarts after EOF and inside error callback; oversized headers rejected; valid multipart boundaries and JPEG bytes; invalid auth cannot remove protection");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static void restartDropsOldFrames(String pagePath) throws Exception {
        int port = freePort();
        MjpegServer server = new MjpegServer(port);
        server.start();
        try {
            String page = request(port, "/");
            Files.write(Paths.get(pagePath), page.substring(page.indexOf("\r\n\r\n") + 4).getBytes(StandardCharsets.UTF_8));
            String status = request(port, "/status");
            Files.write(Paths.get(pagePath + ".status.json"), status.substring(status.indexOf("\r\n\r\n") + 4).getBytes(StandardCharsets.UTF_8));
            server.pushJpeg(new byte[] { (byte) 255, (byte) 216, 1, (byte) 255, (byte) 217 });
            check(request(port, "/snapshot.jpg").startsWith("HTTP/1.1 200"), "initial snapshot must exist");
            try (Socket pending = new Socket("127.0.0.1", port)) {
                pending.setSoTimeout(2000);
                pending.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
                server.stop();
                try {
                    check(pending.getInputStream().read() == -1, "stop must close clients still sending headers");
                } catch (java.net.SocketException closed) {
                    // An accepted TCP connection may be reset while its request is incomplete.
                }
            }
            server.start();
            check(request(port, "/snapshot.jpg").startsWith("HTTP/1.1 503"), "restarted server must not expose an old camera frame");
        } finally { server.stop(); }
    }

    private static String request(int port, String path) throws Exception {
        return request(port, path, null);
    }

    private static String request(int port, String path, String authorization) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            String auth = authorization == null ? "" : "Authorization: " + authorization + "\r\n";
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n" + auth + "\r\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = socket.getInputStream().read(buffer)) != -1) body.write(buffer, 0, count);
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void invalidAuthenticationCannotMakeProtectedServerPublic() throws Exception {
        int port = freePort();
        MjpegServer server = new MjpegServer(port);
        server.start();
        try {
            check(request(port, "/status").startsWith("HTTP/1.1 200"), "unconfigured authentication must retain the default public behavior");
            server.setBasicAuth("admin", "secret");
            for (String user : new String[] {null, "", " \t "}) {
                boolean rejected = false;
                try { server.setBasicAuth(user, "replacement"); } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "explicit empty authentication user must be rejected");
                check(request(port, "/status").startsWith("HTTP/1.1 401"), "invalid credentials must not make protected HTTP endpoints public");
                check(request(port, "/status", "Basic YWRtaW46c2VjcmV0").startsWith("HTTP/1.1 200"), "rejected authentication edits must retain previous valid credentials");
            }
        } finally { server.stop(); }
    }

    private static void clientCanRestartAfterRemoteEof() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(2000);
            MjpegClient client = new MjpegClient("127.0.0.1", server.getLocalPort(), "/video");
            AtomicInteger frames = new AtomicInteger();
            try {
                for (int i = 0; i < 2; i++) {
                    CountDownLatch ended = new CountDownLatch(1);
                    client.start(new MjpegClient.Listener() {
                        public void onFrame(byte[] jpeg) { frames.incrementAndGet(); }
                        public void onError(String message) { ended.countDown(); }
                    });
                    try (Socket peer = server.accept()) {
                        readRequest(peer);
                        peer.getOutputStream().write(new byte[] {72,84,84,80,47,49,46,49,32,50,48,48,32,79,75,13,10,13,10, (byte)255,(byte)216,1,(byte)255,(byte)217});
                    }
                    check(ended.await(2, TimeUnit.SECONDS), "remote EOF must notify the viewer");
                }
                check(frames.get() == 2, "client restart must deliver a new frame after remote EOF");
            } catch (java.net.SocketTimeoutException e) {
                throw new AssertionError("client failed to restart after remote EOF", e);
            } finally { client.stop(); }
        }
    }

    private static void oversizedHeadersAreRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            MjpegClient client = new MjpegClient("127.0.0.1", server.getLocalPort(), "/video");
            AtomicInteger frames = new AtomicInteger();
            CountDownLatch ended = new CountDownLatch(1);
            client.start(new MjpegClient.Listener() {
                public void onFrame(byte[] jpeg) { frames.incrementAndGet(); }
                public void onError(String message) { ended.countDown(); }
            });
            try {
                try (Socket peer = server.accept()) {
                    readRequest(peer);
                    ByteArrayOutputStream response = new ByteArrayOutputStream();
                    response.write("HTTP/1.1 200 OK\r\nX-Oversized: ".getBytes(StandardCharsets.UTF_8));
                    for (int i = 0; i < 5000; i++) response.write('x');
                    response.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    response.write(new byte[] {(byte)255,(byte)216,1,(byte)255,(byte)217});
                    peer.getOutputStream().write(response.toByteArray());
                }
                check(ended.await(2, TimeUnit.SECONDS), "oversized HTTP headers must end the connection");
                check(frames.get() == 0, "JPEG data following rejected oversized headers must never reach the viewer");
            } finally { client.stop(); }
        }
    }

    private static void clientCanReconnectFromErrorCallback() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(2000);
            MjpegClient client = new MjpegClient("127.0.0.1", server.getLocalPort(), "/video");
            CountDownLatch reconnected = new CountDownLatch(1);
            MjpegClient.Listener next = new MjpegClient.Listener() {
                public void onFrame(byte[] jpeg) { reconnected.countDown(); }
                public void onError(String message) { }
            };
            client.start(new MjpegClient.Listener() {
                public void onFrame(byte[] jpeg) { }
                public void onError(String message) { client.start(next); }
            });
            try {
                try (Socket peer = server.accept()) {
                    readRequest(peer);
                    peer.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                }
                try (Socket peer = server.accept()) {
                    readRequest(peer);
                    peer.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    peer.getOutputStream().write(new byte[] {(byte)255,(byte)216,2,(byte)255,(byte)217});
                    check(reconnected.await(2, TimeUnit.SECONDS), "error callback must be able to start the next connection");
                }
            } catch (java.net.SocketTimeoutException e) {
                throw new AssertionError("error callback could not reconnect", e);
            } finally { client.stop(); }
        }
    }

    private static void streamUsesDeclaredMultipartBoundary() throws Exception {
        int port = freePort();
        MjpegServer server = new MjpegServer(port);
        server.start();
        byte[] jpeg = {(byte)255, (byte)216, 7, (byte)255, (byte)217};
        server.pushJpeg(jpeg);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET /video HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            InputStream input = socket.getInputStream();
            check(line(input).startsWith("HTTP/1.1 200"), "stream must return HTTP success");
            String boundary = null;
            for (String head; !(head = line(input)).isEmpty();) {
                if (head.startsWith("Content-Type:")) boundary = head.substring(head.indexOf("boundary=") + 9);
            }
            check(boundary != null, "stream must advertise its multipart boundary");
            check(("--" + boundary).equals(line(input)), "stream delimiter must match the declared multipart boundary");
            check("Content-Type: image/jpeg".equals(line(input)), "part must advertise JPEG content");
            check("Content-Length: 5".equals(line(input)), "part must advertise actual JPEG length");
            check(line(input).isEmpty(), "part headers must end before image bytes");
            for (byte next : jpeg) check(input.read() == (next & 255), "stream must preserve JPEG bytes");
        } finally { server.stop(); }
    }

    private static String line(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') if (next != '\r') bytes.write(next);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void readRequest(Socket peer) throws Exception {
        peer.setSoTimeout(2000);
        int last = 0;
        for (int next; (next = peer.getInputStream().read()) != -1;) {
            last = (last << 8) | next;
            if (last == 0x0d0a0d0a) return;
        }
        throw new AssertionError("client closed before sending its request");
    }
}
