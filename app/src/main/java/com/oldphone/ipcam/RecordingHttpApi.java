package com.oldphone.ipcam;

import java.io.File;
import java.io.FileInputStream;
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
        default File thumbnail(String id) throws IOException {
            throw new java.io.FileNotFoundException();
        }
    }
    public static final class Clip {
        public final String id, reasons, triggers;
        public final long startTimeMs, durationMs, bytes;
        public final boolean locked, withAudio, thumbnail;
        public Clip(String id, long startTimeMs, long durationMs, long bytes,
                    boolean locked, boolean withAudio, String reasons) {
            this(id, startTimeMs, durationMs, bytes, locked, withAudio, reasons, false, "");
        }
        public Clip(String id, long startTimeMs, long durationMs, long bytes,
                    boolean locked, boolean withAudio, String reasons, boolean thumbnail, String triggers) {
            this.id = id; this.startTimeMs = startTimeMs; this.durationMs = durationMs;
            this.bytes = bytes; this.locked = locked; this.withAudio = withAudio; this.reasons = reasons;
            this.thumbnail = thumbnail; this.triggers = triggers == null ? "" : triggers;
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
        } else if ("/api/recordings/days".equals(path)) {
            serveDays(head, out);
        } else if (path.startsWith("/api/recordings/")) {
            String rest = path.substring("/api/recordings/".length());
            if (rest.endsWith("/thumbnail"))
                serveThumbnail(rest.substring(0, rest.length() - "/thumbnail".length()), head, out);
            else serveFile(rest, head, range, ifRange, out);
        } else send(out, "404 Not Found", "text/plain", "Not Found", head, "");
    }

    private void serveList(String query, boolean head, OutputStream out) throws IOException {
        int offset = 0, limit = 100;
        String day = null;
        try {
            boolean haveOffset = false, haveLimit = false, haveDay = false;
            if (!query.isEmpty()) for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", -1);
                if (pair.length != 2) throw new IllegalArgumentException();
                if ("day".equals(pair[0]) && !haveDay) {
                    if (!pair[1].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                    day = pair[1]; haveDay = true;
                } else if (pair[1].matches("[0-9]+") && "offset".equals(pair[0]) && !haveOffset) {
                    offset = Integer.parseInt(pair[1]); haveOffset = true;
                } else if (pair[1].matches("[0-9]+") && "limit".equals(pair[0]) && !haveLimit) {
                    limit = Integer.parseInt(pair[1]); haveLimit = true;
                } else throw new IllegalArgumentException();
            }
            if (limit < 1 || limit > 200) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            send(out, "400 Bad Request", "text/plain", "Invalid pagination", head, ""); return;
        }
        final List<Clip> all;
        try { all = loadSorted(); }
        catch (IOException unavailable) {
            send(out, "503 Service Unavailable", "text/plain", "Recording storage unavailable", head, ""); return;
        }
        List<Clip> clips = all;
        if (day != null) {
            clips = new ArrayList<Clip>();
            for (Clip clip : all) if (day.equals(dayKey(clip.startTimeMs))) clips.add(clip);
        }
        StringBuilder json = new StringBuilder("{\"recordings\":[");
        int end = (int) Math.min(clips.size(), (long) offset + limit);
        for (int i = offset; i < end; i++) {
            Clip clip = clips.get(i);
            if (i != offset) json.append(',');
            json.append(clipJson(clip));
        }
        json.append("],\"total\":").append(clips.size()).append(",\"offset\":").append(offset)
                .append(",\"limit\":").append(limit).append('}');
        send(out, "200 OK", "application/json; charset=utf-8", json.toString(), head, "");
    }

    private void serveDays(boolean head, OutputStream out) throws IOException {
        final List<Clip> clips;
        try { clips = loadSorted(); }
        catch (IOException unavailable) {
            send(out, "503 Service Unavailable", "text/plain", "Recording storage unavailable", head, ""); return;
        }
        StringBuilder json = new StringBuilder("{\"days\":[");
        String previous = null;
        int count = 0, groups = 0;
        for (int i = 0; i <= clips.size(); i++) {
            String day = i == clips.size() ? null : dayKey(clips.get(i).startTimeMs);
            if (previous != null && !previous.equals(day)) {
                if (groups++ > 0) json.append(',');
                json.append("{\"day\":").append(quote(previous)).append(",\"count\":").append(count).append('}');
                count = 0;
            }
            if (day != null) { previous = day; count++; }
        }
        json.append("]}");
        send(out, "200 OK", "application/json; charset=utf-8", json.toString(), head, "");
    }

    private List<Clip> loadSorted() throws IOException {
        if (repository == null) throw new IOException("No recording storage");
        List<Clip> clips = new ArrayList<Clip>(repository.list());
        Collections.sort(clips, new Comparator<Clip>() {
            @Override public int compare(Clip a, Clip b) {
                int byTime = Long.compare(b.startTimeMs, a.startTimeMs);
                return byTime != 0 ? byTime : b.id.compareTo(a.id);
            }
        });
        return clips;
    }

    private static String clipJson(Clip clip) {
        return new StringBuilder("{\"id\":").append(quote(clip.id))
                .append(",\"start_time_ms\":").append(clip.startTimeMs)
                .append(",\"duration_ms\":").append(clip.durationMs)
                .append(",\"bytes\":").append(clip.bytes)
                .append(",\"locked\":").append(clip.locked)
                .append(",\"with_audio\":").append(clip.withAudio)
                .append(",\"reasons\":").append(quote(clip.reasons))
                .append(",\"day\":").append(quote(dayKey(clip.startTimeMs)))
                .append(",\"local_time\":").append(quote(clockKey(clip.startTimeMs)))
                .append(",\"thumbnail\":").append(clip.thumbnail)
                .append(",\"triggers\":").append(triggerArray(clip)).append('}').toString();
    }

    private static String triggerArray(Clip clip) {
        String codes = clip.triggers == null || clip.triggers.length() == 0
                ? RecordingReasons.codes(clip.reasons, false) : clip.triggers;
        StringBuilder array = new StringBuilder("[");
        if (codes.length() > 0) {
            String[] parts = codes.split(",");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() == 0) continue;
                if (array.length() > 1) array.append(',');
                array.append(quote(parts[i]));
            }
        }
        return array.append(']').toString();
    }

    private void serveThumbnail(String id, boolean head, OutputStream out) throws IOException {
        if (!clipId(id)) {
            send(out, "404 Not Found", "text/plain", "Recording not found", head, ""); return;
        }
        final File file;
        try {
            if (repository == null) throw new IOException("No recording storage");
            file = repository.thumbnail(id);
        } catch (IOException missing) {
            send(out, "404 Not Found", "text/plain", "Thumbnail unavailable", head, ""); return;
        }
        byte[] body;
        try { body = readSmall(file, 512 * 1024); }
        catch (IOException missing) {
            send(out, "404 Not Found", "text/plain", "Thumbnail unavailable", head, ""); return;
        }
        head(out, "200 OK", "Content-Type: image/jpeg\r\nContent-Length: " + body.length
                + "\r\nCache-Control: private, max-age=86400\r\n", true);
        if (!head) out.write(body);
        out.flush();
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
        head(out, status, headers, false);
    }
    static void head(OutputStream out, String status, String headers, boolean cacheable) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\n" + headers
                + (cacheable ? "" : "Cache-Control: no-store\r\n")
                + "Connection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n").getBytes("UTF-8"));
    }
    private static boolean clipId(String id) {
        return id != null && id.matches("[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private static String dayKey(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMs));
    }
    private static String clockKey(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMs));
    }
    private static byte[] readSmall(File file, int limit) throws IOException {
        if (file.length() <= 0 || file.length() > limit) throw new IOException("thumbnail size");
        byte[] body = new byte[(int) file.length()];
        FileInputStream in = new FileInputStream(file);
        try {
            int at = 0;
            while (at < body.length) {
                int read = in.read(body, at, body.length - at);
                if (read < 0) throw new IOException("truncated thumbnail");
                at += read;
            }
        } finally { in.close(); }
        if ((body[0] & 255) != 0xFF || (body[1] & 255) != 0xD8) throw new IOException("not a jpeg");
        return body;
    }
}
