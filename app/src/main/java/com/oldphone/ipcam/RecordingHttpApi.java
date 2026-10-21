package com.oldphone.ipcam;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Read-only HTTP boundary. The repository only resolves indexed, completed recordings. */
public final class RecordingHttpApi {
    public interface Repository {
        List<Clip> list() throws IOException;
        File file(String id) throws IOException;
    }
    public static final class Clip {
        public final String id, reasons;
        public final long startTimeMs, durationMs, bytes;
        public final boolean locked, withAudio;
        public Clip(String id, long startTimeMs, long durationMs, long bytes,
                    boolean locked, boolean withAudio, String reasons) {
            this.id = id; this.startTimeMs = startTimeMs; this.durationMs = durationMs;
            this.bytes = bytes; this.locked = locked; this.withAudio = withAudio; this.reasons = reasons;
        }
    }
    private final Repository repository;
    public RecordingHttpApi(Repository repository) { this.repository = repository; }

    public void serve(String method, String target, String range, String ifRange, OutputStream out) throws IOException {
        boolean head = "HEAD".equals(method);
        if (!head && !"GET".equals(method)) {
            send(out, "405 Method Not Allowed", "text/plain", "Read-only endpoint", false, "Allow: GET, HEAD\r\n");
            return;
        }
        int queryAt = target.indexOf('?');
        String path = queryAt < 0 ? target : target.substring(0, queryAt);
        if ("/api/recordings".equals(path)) {
            serveList(queryAt < 0 ? "" : target.substring(queryAt + 1), head, out);
        } else if (path.startsWith("/api/recordings/")) {
            serveFile(path.substring("/api/recordings/".length()), head, range, ifRange, out);
        } else send(out, "404 Not Found", "text/plain", "Not Found", head, "");
    }

    private void serveList(String query, boolean head, OutputStream out) throws IOException {
        int offset = 0, limit = 100;
        try {
            boolean haveOffset = false, haveLimit = false;
            if (!query.isEmpty()) for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", -1);
                if (pair.length != 2 || !pair[1].matches("[0-9]+")) throw new IllegalArgumentException();
                int value = Integer.parseInt(pair[1]);
                if ("offset".equals(pair[0]) && !haveOffset) { offset = value; haveOffset = true; }
                else if ("limit".equals(pair[0]) && !haveLimit) { limit = value; haveLimit = true; }
                else throw new IllegalArgumentException();
            }
            if (limit < 1 || limit > 200) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            send(out, "400 Bad Request", "text/plain", "Invalid pagination", head, ""); return;
        }
        final List<Clip> clips;
        try {
            if (repository == null) throw new IOException("No recording storage");
            clips = new ArrayList<Clip>(repository.list());
        } catch (IOException unavailable) {
            send(out, "503 Service Unavailable", "text/plain", "Recording storage unavailable", head, ""); return;
        }
        Collections.sort(clips, new Comparator<Clip>() {
            @Override public int compare(Clip a, Clip b) {
                int byTime = Long.compare(b.startTimeMs, a.startTimeMs);
                return byTime != 0 ? byTime : b.id.compareTo(a.id);
            }
        });
        StringBuilder json = new StringBuilder("{\"recordings\":[");
        int end = (int) Math.min(clips.size(), (long) offset + limit);
        for (int i = offset; i < end; i++) {
            Clip clip = clips.get(i);
            if (i != offset) json.append(',');
            json.append("{\"id\":").append(quote(clip.id))
                    .append(",\"start_time_ms\":").append(clip.startTimeMs)
                    .append(",\"duration_ms\":").append(clip.durationMs)
                    .append(",\"bytes\":").append(clip.bytes)
                    .append(",\"locked\":").append(clip.locked)
                    .append(",\"with_audio\":").append(clip.withAudio)
                    .append(",\"reasons\":").append(quote(clip.reasons)).append('}');
        }
        json.append("],\"total\":").append(clips.size()).append(",\"offset\":").append(offset)
                .append(",\"limit\":").append(limit).append('}');
        send(out, "200 OK", "application/json; charset=utf-8", json.toString(), head, "");
    }

    private void serveFile(String id, boolean head, String range, String ifRange, OutputStream out) throws IOException {
        if (!id.matches("[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            send(out, "404 Not Found", "text/plain", "Recording not found", head, ""); return;
        }
        final File file;
        final RandomAccessFile input;
        try {
            if (repository == null) throw new IOException("No recording storage");
            file = repository.file(id);
            input = new RandomAccessFile(file, "r");
        } catch (IOException missing) {
            send(out, "404 Not Found", "text/plain", "Recording unavailable", head, ""); return;
        }
        // Keep the file descriptor open while streaming; never read a full clip into memory.
        try {
            long length = input.length();
            String etag = "\"" + id + "-" + Long.toHexString(length) + "-" + Long.toHexString(file.lastModified()) + "\"";
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            String modified = format.format(new Date(file.lastModified()));
            boolean partial = !head && range != null && (ifRange == null || ifRange.equals(etag) || ifRange.equals(modified));
            long start = 0, end = length - 1;
            if (partial) {
                try {
                    if (!range.startsWith("bytes=")) throw new IllegalArgumentException();
                    String[] bounds = range.substring(6).split("-", -1);
                    if (bounds.length != 2 || length <= 0) throw new IllegalArgumentException();
                    if (bounds[0].isEmpty()) {
                        long suffix = unsigned(bounds[1]);
                        if (suffix == 0) throw new IllegalArgumentException();
                        start = Math.max(0, length - suffix);
                    } else {
                        start = unsigned(bounds[0]);
                        end = bounds[1].isEmpty() ? end : Math.min(end, unsigned(bounds[1]));
                    }
                    if (start >= length || start > end) throw new IllegalArgumentException();
                } catch (IllegalArgumentException invalid) {
                    send(out, "416 Range Not Satisfiable", "text/plain", "Invalid byte range", head,
                            "Content-Range: bytes */" + length + "\r\nAccept-Ranges: bytes\r\n"); return;
                }
            }
            long count = partial ? end - start + 1 : length;
            String headers = "Content-Type: video/mp4\r\nContent-Length: " + count
                    + "\r\nAccept-Ranges: bytes\r\nETag: " + etag + "\r\nLast-Modified: " + modified + "\r\n";
            if (partial) headers += "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n";
            head(out, partial ? "206 Partial Content" : "200 OK", headers);
            if (!head) {
                input.seek(start);
                byte[] buffer = new byte[64 * 1024];
                long remaining = count;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Recording truncated during playback");
                    out.write(buffer, 0, read); remaining -= read;
                }
            }
            out.flush();
        } finally { input.close(); }
    }
    private static long unsigned(String value) {
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        if (value != null) for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32) result.append(String.format(Locale.US, "\\u%04x", (int)c));
                    else result.append(c);
            }
        }
        return result.append('"').toString();
    }
    static void send(OutputStream out, String status, String type, String text, boolean onlyHead, String extra) throws IOException {
        byte[] body = text.getBytes("UTF-8");
        head(out, status, "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n" + extra);
        if (!onlyHead) out.write(body);
        out.flush();
    }
    static void head(OutputStream out, String status, String headers) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\n" + headers
                + "Cache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").getBytes("UTF-8"));
    }
}
