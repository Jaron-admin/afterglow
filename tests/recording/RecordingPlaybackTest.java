package com.oldphone.ipcam;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Faststart, cached thumbnails, day index and the first-load byte counts. */
public final class RecordingPlaybackTest {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        reasons();
        faststart();
        thumbnailAndTriggers();
        firstBrowsePayload();
        System.out.println("PASS: recording reasons, faststart, thumbnail and day index");
    }

    static void reasons() {
        check("motion,sound".equals(RecordingReasons.codes("画面变动 · 1.2 秒\n声响 · 0.4 秒", false)),
                "motion and sound codes");
        check("schedule".equals(RecordingReasons.codes("", true)), "daily window is 定时");
        check("motion".equals(RecordingReasons.codes("motion", false)), "english motion token");
        check("".equals(RecordingReasons.codes("commotion", false)), "motion is not a substring match");
        check(G711.alaw((short) 0) == (byte) 0xD5, "A-law silence");
    }

    static void faststart() throws Exception {
        File dir = Files.createTempDirectory(new File("build").toPath(), "afterglow-faststart").toFile();
        int payload = 256 * 1024;
        byte[] ftyp = box("ftyp", new byte[16]);
        byte[] media = new byte[payload];
        java.util.Arrays.fill(media, (byte) 'M');
        long sampleAt = ftyp.length + 8;
        byte[] stco = box("stco", concat(u32(0), u32(1), u32(sampleAt)));
        byte[] moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stco)))));
        byte[] mdat = box("mdat", media);
        File file = new File(dir, "clip.mp4");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ftyp); out.write(mdat); out.write(moov);
        }
        long before = file.length();
        check(moovOffset(file) == ftyp.length + mdat.length, "fixture keeps moov at the end");
        Mp4FastStart.Placement placement = Mp4FastStart.relocate(file);
        check(placement.relocated, "moov moved ahead of mdat");
        check(placement.bytesToHeaderBefore == before, "before, the header is the whole file");
        check(placement.bytesToHeaderAfter == ftyp.length + moov.length, "after, playback starts at ftyp+moov");
        check(placement.bytesToHeaderAfter < placement.bytesToHeaderBefore / 100, "header is far smaller than the clip");
        check(moovOffset(file) == ftyp.length, "moov now follows ftyp");
        long patched = readU32(file, findStcoValue(file));
        check(patched == sampleAt + moov.length, "chunk offset follows the shifted mdat: " + patched);
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(patched);
            check(in.read() == 'M', "media bytes survive the move");
        }
        Mp4FastStart.Placement again = Mp4FastStart.relocate(file);
        check(!again.relocated, "a second pass leaves a faststart file alone");
        System.out.println("MEASURE faststart bytes_to_header before=" + placement.bytesToHeaderBefore
                + " after=" + placement.bytesToHeaderAfter
                + " payload=" + payload
                + " method=synthetic ftyp+mdat+moov, count bytes that must be read before moov is complete");
    }

    static void thumbnailAndTriggers() throws Exception {
        File root = Files.createTempDirectory(new File("build").toPath(), "afterglow-thumb").toFile();
        RecordingStore store = new RecordingStore(root, 10_000_000, 1);
        RecordingStore.Pending pending = store.begin(1_700_000_000_000L, true);
        try (FileOutputStream out = new FileOutputStream(pending.file)) { out.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}); }
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        store.writeThumbnail(pending, jpeg);
        pending.properties.setProperty("triggers", RecordingReasons.codes("画面变动 · 1.0 秒", true));
        try { Mp4FastStart.relocate(pending.file); }
        catch (Exception unexpected) { throw new AssertionError("non-mp4 faststart must not throw", unexpected); }
        RecordingStore.Clip clip = store.complete(pending, 1500, "画面变动 · 1.0 秒");
        check(clip.hasThumbnail, "thumbnail recorded when the segment completes");
        check("motion,schedule".equals(clip.triggers), "stored triggers");
        check(java.util.Arrays.equals(jpeg, Files.readAllBytes(store.thumbnail(clip.id).toPath())), "cached jpeg bytes");
        store.delete(clip.id);
        check(!new File(root, clip.id + ".jpg").exists(), "delete removes the thumbnail");
    }

    static void firstBrowsePayload() throws Exception {
        final List<RecordingHttpApi.Clip> clips = new ArrayList<RecordingHttpApi.Clip>();
        long noon = 1_700_000_000_000L;
        for (int day = 0; day < 10; day++) for (int n = 0; n < 20; n++) {
            long start = noon - day * 86_400_000L - n * 1000L;
            String id = String.format(Locale.US, "%d-%08d-1234-1234-1234-%012d", start, day, n);
            clips.add(new RecordingHttpApi.Clip(id, start, 5000, 1000, false, true, "画面变动 · 1.2 秒"));
        }
        RecordingHttpApi api = new RecordingHttpApi(new RecordingHttpApi.Repository() {
            public List<RecordingHttpApi.Clip> list() { return clips; }
            public File file(String id) throws java.io.IOException { throw new java.io.FileNotFoundException(); }
        });
        String list = body(api, "GET", "/api/recordings?offset=0&limit=100");
        String days = body(api, "GET", "/api/recordings/days");
        check(list.contains("\"total\":200") && list.contains("\"limit\":100"), "old first page is 100 of 200");
        check(days.contains("\"count\":20") && count(days, "\"day\"") == 10, "ten day folders");
        check(days.getBytes(StandardCharsets.UTF_8).length * 10 < list.getBytes(StandardCharsets.UTF_8).length,
                "day index is much smaller than the first clip page");
        String oneDay = body(api, "GET", "/api/recordings?day=" + dayOf(list) + "&offset=0&limit=100");
        check(oneDay.contains("\"total\":20") && oneDay.contains("\"triggers\":[\"motion\"]"), "day filter and derived trigger");
        check(bodyStatus(api, "GET", "/api/recordings?day=nope") == 400, "bad day rejected");
        System.out.println("MEASURE browse first_response_bytes before=" + list.getBytes(StandardCharsets.UTF_8).length
                + " after=" + days.getBytes(StandardCharsets.UTF_8).length
                + " clips=200 days=10 page=100"
                + " method=HTTP body size of GET /api/recordings?limit=100 versus GET /api/recordings/days");
    }

    static String dayOf(String json) {
        int at = json.indexOf("\"day\":\"");
        return json.substring(at + 7, at + 17);
    }
    static int count(String text, String token) {
        int found = 0, from = 0;
        while ((from = text.indexOf(token, from)) >= 0) { found++; from += token.length(); }
        return found;
    }
    static String body(RecordingHttpApi api, String method, String target) throws Exception {
        String response = response(api, method, target);
        int split = response.indexOf("\r\n\r\n");
        check(response.startsWith("HTTP/1.1 200"), response.substring(0, Math.min(response.length(), 40)));
        return response.substring(split + 4);
    }
    static int bodyStatus(RecordingHttpApi api, String method, String target) throws Exception {
        String response = response(api, method, target);
        return Integer.parseInt(response.substring(9, 12));
    }
    static String response(RecordingHttpApi api, String method, String target) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        api.serve(method, target, null, null, out);
        return out.toString("UTF-8");
    }
    static long moovOffset(File file) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long position = 0;
            while (position + 8 <= file.length()) {
                in.seek(position);
                long size = Integer.toUnsignedLong(in.readInt());
                byte[] name = new byte[4];
                in.readFully(name);
                if ("moov".equals(new String(name, StandardCharsets.ISO_8859_1))) return position;
                if (size < 8) break;
                position += size;
            }
        }
        return -1;
    }
    static int findStcoValue(File file) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        byte[] marker = new byte[] {'s', 't', 'c', 'o'};
        for (int i = 0; i + 16 < data.length; i++) {
            if (data[i] == marker[0] && data[i + 1] == marker[1] && data[i + 2] == marker[2] && data[i + 3] == marker[3])
                return i + 12;
        }
        throw new AssertionError("stco missing");
    }
    static long readU32(File file, int offset) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(offset);
            return Integer.toUnsignedLong(in.readInt());
        }
    }
    static byte[] box(String type, byte[] payload) throws Exception {
        byte[] out = new byte[8 + payload.length];
        int size = out.length;
        out[0] = (byte) (size >>> 24); out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8); out[3] = (byte) size;
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, out, 4, 4);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }
    static byte[] u32(long value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }
    static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, out, at, part.length); at += part.length; }
        return out;
    }
}
