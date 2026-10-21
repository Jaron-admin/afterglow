package com.oldphone.ipcam;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Real HTTP sockets exercise authentication, clip isolation, ranges and lifecycle. */
public final class RecordingHttpTest {
    private static final String ID = "1700000000000-12345678-1234-1234-1234-123456789abc";
    private static final String DEVICE = "12345678-1234-1234-1234-123456789abc";
    private static final String AUTH = "Authorization: Basic YWRtaW46c2VjcmV0\r\n";
    public static void main(String[] args) throws Exception {
        MjpegServer server = new MjpegServer(freePort());
        File file = File.createTempFile("afterglow-http", ".mp4");
        Files.write(file.toPath(), "0123456789".getBytes(StandardCharsets.UTF_8));
        final boolean[] unavailable = { false };
        server.setDeviceInfo(DEVICE, "Afterglow-test", true, false);
        server.setRecordings(new RecordingHttpApi(new RecordingHttpApi.Repository() {
            public List<RecordingHttpApi.Clip> list() throws IOException {
                if (unavailable[0]) throw new IOException("storage removed");
                return Arrays.asList(new RecordingHttpApi.Clip(ID, 1000, 5000, 10, true, false, "motion\n\"test\""),
                        new RecordingHttpApi.Clip("1700000000001-12345678-1234-1234-1234-123456789abc", 2000, 6000, 20, false, true, "sound"));
            }
            public File file(String id) throws IOException {
                if (!ID.equals(id) || !file.exists()) throw new FileNotFoundException();
                return file;
            }
        }));
        server.setBasicAuth("admin", "secret");
        server.start();
        try {
            Response info = request(server, "GET", "/api/info", "");
            check(info.status == 200 && info.body.contains(DEVICE), "public identity enables rediscovery before credential use");
            check(info.body.contains("\"mjpeg_enabled\":false"), "RTSP-only capability must be explicit");
            check(info.body.contains("\"http_auth_required\":true"), "discovery needs authentication requirement but no credentials");
            check(!info.body.contains("secret") && !info.body.contains("YWRtaW4"), "public identity contains no credentials");
            check(request(server, "GET", "/api/recordings", "").status == 401, "list must require configured authentication");
            check(request(server, "GET", "/api/recordings/" + ID, "").status == 401, "clip must require authentication");
            check(request(server, "GET", "/video", AUTH).status == 404, "RTSP-only must not expose MJPEG");
            Response list = request(server, "GET", "/api/recordings?offset=1&limit=1", AUTH);
            check(list.status == 200 && list.body.contains("\"total\":2") && list.body.contains("\"offset\":1"), "list pagination metadata");
            check(list.body.contains(ID) && !list.body.contains("1700000000001-"), "clips sorted newest first before pagination");
            check(list.body.contains("motion\\n\\\"test\\\""), "JSON must escape metadata");
            check(request(server, "GET", "/api/recordings?offset=-1", AUTH).status == 400, "negative offset rejected");
            check(request(server, "GET", "/api/recordings?limit=201", AUTH).status == 400, "oversized page rejected");
            check(request(server, "GET", "/api/recordings?limit=x", AUTH).status == 400, "malformed page rejected");
            check(request(server, "DELETE", "/api/recordings/" + ID, AUTH).status == 405, "remote mutations rejected");
            for (String id : new String[] {"../../secret", "%2e%2e%2fsecret", ID + "/extra", ID + ".partial", "unknown"})
                check(request(server, "GET", "/api/recordings/" + id, AUTH).status == 404, "unregistered path rejected: " + id);
            Response full = request(server, "GET", "/api/recordings/" + ID, AUTH);
            check(full.status == 200 && full.body.equals("0123456789"), "complete clip bytes");
            check("bytes".equals(full.header("accept-ranges")), "seek advertised");
            Response range = request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: bytes=2-5\r\n");
            check(range.status == 206 && range.body.equals("2345") && "bytes 2-5/10".equals(range.header("content-range")), "inclusive range bytes");
            check(request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: bytes=-3\r\n").body.equals("789"), "suffix range");
            check(request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: bytes=7-\r\n").body.equals("789"), "open ended range");
            check(request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: bytes=7-999\r\n").body.equals("789"), "range end is clipped");
            for (String bad : new String[]{"bytes=10-", "bytes=5-2", "bytes=-0", "bytes=0-1,4-5", "bytes=999999999999999999999-"}) {
                Response response = request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: " + bad + "\r\n");
                check(response.status == 416 && "bytes */10".equals(response.header("content-range")), "invalid range: " + bad);
            }
            Response head = request(server, "HEAD", "/api/recordings/" + ID, AUTH + "Range: bytes=2-5\r\n");
            check(head.status == 200 && head.body.isEmpty() && "10".equals(head.header("content-length")), "HEAD has full length and no bytes");
            Response changed = request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: bytes=2-5\r\nIf-Range: \"old\"\r\n");
            check(changed.status == 200 && changed.body.equals("0123456789"), "If-Range mismatch returns whole clip");
            Response same = request(server, "GET", "/api/recordings/" + ID, AUTH + "Range: bytes=2-5\r\nIf-Range: " + full.header("etag") + "\r\n");
            check(same.status == 206, "matching If-Range permits seek");
            unavailable[0] = true;
            check(request(server, "GET", "/api/recordings", AUTH).status == 503, "storage failure is not an empty successful library");
            check(file.delete(), "fixture removal");
            check(request(server, "GET", "/api/recordings/" + ID, AUTH).status == 404, "deleted clip returns404");
            try (Socket idle = new Socket("127.0.0.1", server.getPort())) {
                idle.setSoTimeout(2000);
                idle.getOutputStream().write("GET /api/recordings HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
                server.stop();
                try { check(idle.getInputStream().read() == -1, "stop closes pending requests"); }
                catch (SocketException expected) { }
            }
        } finally { server.stop(); file.delete(); }
        System.out.println("PASS: recording HTTP auth, public identity, mode isolation, pagination, JSON, read-only paths, range/HEAD/If-Range, unavailable/deleted files and shutdown");
    }
    private static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static Response request(MjpegServer server, String method, String path, String headers) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n" + headers + "\r\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = socket.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new Response(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    private static final class Response {
        final int status; final String body; final Map<String,String> headers = new HashMap<String,String>();
        Response(String response) {
            int end = response.indexOf("\r\n\r\n"); check(end >= 0, "complete HTTP header: " + response);
            String[] lines = response.substring(0, end).split("\r\n"); status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) { int colon = lines[i].indexOf(':'); headers.put(lines[i].substring(0,colon).toLowerCase(Locale.US), lines[i].substring(colon+1).trim()); }
            body = response.substring(end+4);
        }
        String header(String name) { return headers.get(name); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
