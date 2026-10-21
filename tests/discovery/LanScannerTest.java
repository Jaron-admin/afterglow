package com.oldphone.ipcam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** Exercises real scanner TCP requests; only the remote peer is substituted. */
public final class LanScannerTest {
    private static final String ID = "a50d3fb1-fafc-4670-b11c-f3c6f6b80455";
    public static void main(String[] args) throws Exception {
        infoSelectsRtspWithoutSendingCredentials();
        infoSelectsMjpegAndKeepsAdvertisedPorts();
        protectedHttpKeepsAvailableRtspPlayback();
        protectedMjpegOnlyCanShowAuthenticationHint();
        legacyStatusStillWorks();
        newStatusFallbackRespectsDisabledMjpeg();
        wrongIdentityAndUnauthorizedStatusAreNotDevices();
        redirectsAreNotFollowed();
        System.out.println("PASS LanScannerTest (public identity, RTSP-only capabilities, legacy status, no auth or redirects)");
    }

    private static void protectedHttpKeepsAvailableRtspPlayback() throws Exception {
        Peer peer = new Peer(response(200, info(true, true, 8080, 8554).replace(
                "\"recordings_enabled\":true", "\"recordings_enabled\":true,\"http_auth_required\":true")));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device != null && device.rtspOnly,
                "built-in viewer without HTTP credentials must keep using available RTSP for protected cameras");
    }

    private static void protectedMjpegOnlyCanShowAuthenticationHint() throws Exception {
        Peer peer = new Peer(response(200, info(false, true, 8080, 8554).replace(
                "\"recordings_enabled\":true", "\"recordings_enabled\":true,\"http_auth_required\":true")));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device != null && !device.rtspOnly, "MJPEG-only device must not invent an RTSP endpoint");
        try {
            check(LanScanner.Device.class.getField("httpAuthRequired").getBoolean(device),
                    "viewer must receive authentication requirement before trying unsupported anonymous playback");
        } catch (NoSuchFieldException error) { throw new AssertionError("Device must preserve HTTP authentication requirement"); }
    }

    private static void infoSelectsRtspWithoutSendingCredentials() throws Exception {
        Peer peer = new Peer(response(200, info(true, false, 8080, 8554)));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device != null && device.rtspOnly, "HTTP identity endpoint must not make an RTSP-only camera use MJPEG");
        check(peer.requests.size() == 1 && peer.requests.get(0).startsWith("GET /api/info HTTP/1.1\n"),
                "discovery must use the public identity endpoint first");
    }

    private static void infoSelectsMjpegAndKeepsAdvertisedPorts() throws Exception {
        Peer peer = new Peer(response(200, info(false, true, 8089, 8559)));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device != null && !device.rtspOnly, "MJPEG-capable camera remains viewable");
        check(numberField(device, "httpPort") == 8089 && numberField(device, "rtspPort") == 8559,
                "viewer must receive advertised ports");
        check(device.detail().contains("Afterglow-a50d3fb1"), "nearby camera details should include its stable name");
    }

    private static void legacyStatusStillWorks() throws Exception {
        Peer peer = new Peer(response(404, "missing"), response(200,
                "{\"running\":true,\"has_frame\":true,\"camera\":\"back\",\"width\":640,\"height\":480}"));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device != null && !device.rtspOnly && device.running && device.width == 640,
                "legacy /status cameras stay discoverable");
        check(peer.requests.get(1).startsWith("GET /status HTTP/1.1\n"), "unknown identity route falls back to /status");
    }

    private static void newStatusFallbackRespectsDisabledMjpeg() throws Exception {
        Peer peer = new Peer(response(404, "missing"), response(200,
                "{ \"running\" : true, \"has_frame\" : false, \"rtsp_enabled\" : true, \"mjpeg_enabled\" : false }"));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device != null && device.rtspOnly, "status fallback must also honor explicit capabilities");
    }

    private static void wrongIdentityAndUnauthorizedStatusAreNotDevices() throws Exception {
        Peer peer = new Peer(response(200, info(true, true, 8080, 8554).replace(ID, "not-a-device-id")),
                response(401, "{\"running\":true,\"has_frame\":true}"));
        LanScanner.Device device = probe(peer.port());
        peer.finish();
        check(device == null, "invalid identity or non-success status cannot fingerprint a camera");
    }

    private static void redirectsAreNotFollowed() throws Exception {
        Peer peer = new Peer("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/private\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                response(404, "missing"));
        check(probe(peer.port()) == null, "redirect must not be treated as discovery");
        peer.finish();
        check(peer.requests.size() == 2, "redirect fallback stays on the original host");
    }

    private static LanScanner.Device probe(int port) throws Exception {
        Method method;
        try { method = LanScanner.class.getDeclaredMethod("probeHttp", String.class, int.class); }
        catch (NoSuchMethodException e) { throw new AssertionError("Scanner must probe public identity with capability-aware discovery"); }
        method.setAccessible(true);
        try { return (LanScanner.Device) method.invoke(null, "127.0.0.1", port); }
        catch (InvocationTargetException e) { throw new AssertionError("Scanner probe failed", e.getCause()); }
    }
    private static int numberField(LanScanner.Device device, String name) throws Exception {
        try { return LanScanner.Device.class.getField(name).getInt(device); }
        catch (NoSuchFieldException e) { throw new AssertionError("Device must preserve advertised " + name); }
    }
    private static String info(boolean rtsp, boolean mjpeg, int httpPort, int rtspPort) {
        return "{\"api_version\":1,\"device_id\":\"" + ID + "\",\"name\":\"Afterglow-a50d3fb1\","
                + "\"http_port\":" + httpPort + ",\"rtsp_port\":" + rtspPort
                + ",\"rtsp_enabled\":" + rtsp + ",\"mjpeg_enabled\":" + mjpeg + ",\"recordings_enabled\":true}";
    }
    private static String response(int code, String body) throws Exception {
        return "HTTP/1.1 " + code + " Response\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n" + body;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Peer {
        final ServerSocket server;
        final List<String> requests = new ArrayList<String>();
        final Thread thread;
        volatile Throwable failure;
        Peer(final String... responses) throws Exception {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(3000);
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (String response : responses) {
                            try (Socket socket = server.accept()) {
                                socket.setSoTimeout(2000);
                                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                                StringBuilder request = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null && line.length() > 0) request.append(line).append('\n');
                                check(!request.toString().toLowerCase(java.util.Locale.US).contains("authorization:"),
                                        "discovery must never send credentials");
                                requests.add(request.toString());
                                OutputStream output = socket.getOutputStream();
                                output.write(response.getBytes("UTF-8"));
                                output.flush();
                            }
                        }
                    } catch (Throwable error) { failure = error; }
                    finally { try { server.close(); } catch (Exception ignored) { } }
                }
            }, "scanner-test-peer");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        void finish() throws Exception {
            thread.join(4000);
            server.close();
            check(!thread.isAlive(), "scanner peer did not finish");
            if (failure != null) throw new AssertionError("scanner peer failed", failure);
        }
    }
}
