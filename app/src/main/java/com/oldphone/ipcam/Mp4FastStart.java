package com.oldphone.ipcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MediaMuxer writes moov after mdat. Players then download the whole clip before
 * they can start or seek. This moves moov forward and patches chunk offsets.
 */
public final class Mp4FastStart {
    private static final Set<String> CONTAINERS = new HashSet<String>(Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex"));
    private static final int MAX_MOOV = 32 * 1024 * 1024;

    private Mp4FastStart() {}

    public static final class Placement {
        public final boolean relocated;
        public final long moovOffsetBefore;
        public final long bytesToHeaderBefore;
        public final long bytesToHeaderAfter;
        public final long bytes;
        Placement(boolean relocated, long moovOffsetBefore, long bytesToHeaderBefore,
                  long bytesToHeaderAfter, long bytes) {
            this.relocated = relocated;
            this.moovOffsetBefore = moovOffsetBefore;
            this.bytesToHeaderBefore = bytesToHeaderBefore;
            this.bytesToHeaderAfter = bytesToHeaderAfter;
            this.bytes = bytes;
        }
    }

    /** No-op when the file is not a movable MP4 or moov is already ahead of mdat. */
    public static Placement relocate(File file) throws IOException {
        long length = file.length();
        if (length < 8) return new Placement(false, -1, length, length, length);
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            long position = 0;
            long insertion = 0;
            long moovAt = -1, moovSize = 0, mdatAt = -1;
            boolean first = true;
            while (position + 8 <= length) {
                input.seek(position);
                long size = Integer.toUnsignedLong(input.readInt());
                byte[] name = new byte[4];
                input.readFully(name);
                String type = new String(name, "ISO-8859-1");
                int header = 8;
                if (size == 1) {
                    if (position + 16 > length) break;
                    size = input.readLong();
                    header = 16;
                    if (size < 0) break;
                } else if (size == 0) size = length - position;
                if (size < header || position + size > length) break;
                if (first && "ftyp".equals(type)) insertion = size;
                first = false;
                if ("moov".equals(type) && moovAt < 0) { moovAt = position; moovSize = size; }
                if ("mdat".equals(type) && mdatAt < 0) mdatAt = position;
                position += size;
            }
            if (moovAt < 0 || mdatAt < 0 || moovSize > MAX_MOOV || moovAt <= mdatAt)
                return new Placement(false, moovAt, moovAt < 0 ? length : length, moovAt < 0 ? length : moovAt + moovSize, length);
            byte[] moov = new byte[(int) moovSize];
            input.seek(moovAt);
            input.readFully(moov);
            try {
                patchTree(moov, 0, moov.length, moovSize, insertion, moovAt);
            } catch (IllegalStateException compressed) {
                return new Placement(false, moovAt, length, length, length);
            }
            File temporary = new File(file.getParentFile(), file.getName() + ".faststart");
            FileOutputStream output = new FileOutputStream(temporary);
            byte[] buffer = new byte[64 * 1024];
            try {
                copy(input, output, 0, insertion, buffer);
                output.write(moov);
                copy(input, output, insertion, moovAt, buffer);
                copy(input, output, moovAt + moovSize, length, buffer);
                output.getFD().sync();
            } catch (IOException error) {
                output.close();
                temporary.delete();
                throw error;
            }
            output.close();
            if (!temporary.renameTo(file)) {
                if (file.exists() && !file.delete()) {
                    temporary.delete();
                    throw new IOException("无法替换已整理的录像");
                }
                if (!temporary.renameTo(file)) {
                    temporary.delete();
                    throw new IOException("无法替换已整理的录像");
                }
            }
            long after = insertion + moovSize;
            return new Placement(true, moovAt, length, after, file.length());
        } finally {
            input.close();
        }
    }

    private static void patchTree(byte[] data, int start, int limit, long shift,
                                  long regionStart, long regionEnd) {
        if (start + 8 > limit) return;
        long size = u32(data, start);
        int header = 8;
        if (size == 1) {
            if (start + 16 > limit) return;
            size = u64(data, start + 8);
            header = 16;
        }
        if (size < header || start + size > limit) return;
        int end = start + (int) size;
        String type = type(data, start + 4);
        if ("cmov".equals(type)) throw new IllegalStateException("compressed moov");
        int payload = start + header;
        if ("stco".equals(type)) { patchEntries(data, payload, end, 4, shift, regionStart, regionEnd); return; }
        if ("co64".equals(type)) { patchEntries(data, payload, end, 8, shift, regionStart, regionEnd); return; }
        if (!CONTAINERS.contains(type)) return;
        int child = payload;
        while (child + 8 <= end) {
            long childSize = u32(data, child);
            int childHeader = 8;
            if (childSize == 1) {
                if (child + 16 > end) return;
                childSize = u64(data, child + 8);
                childHeader = 16;
            }
            if (childSize < childHeader || child + childSize > end) return;
            patchTree(data, child, end, shift, regionStart, regionEnd);
            child += (int) childSize;
        }
    }

    private static void patchEntries(byte[] data, int payload, int end, int width, long shift,
                                     long regionStart, long regionEnd) {
        if (payload + 8 > end) return;
        long count = u32(data, payload + 4);
        int entry = payload + 8;
        for (long i = 0; i < count; i++) {
            if (entry + width > end) return;
            long value = width == 8 ? u64(data, entry) : u32(data, entry);
            long adjusted = value;
            if (value >= regionStart && value < regionEnd) adjusted = value + shift;
            else if (value >= regionEnd + shift) adjusted = value - shift;
            if (width == 8) put64(data, entry, adjusted);
            else put32(data, entry, adjusted);
            entry += width;
        }
    }

    private static void copy(RandomAccessFile input, FileOutputStream output, long start, long end,
                             byte[] buffer) throws IOException {
        if (end < start) throw new IOException("录像结构损坏");
        input.seek(start);
        long left = end - start;
        while (left > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) throw new IOException("录像文件在整理时被截断");
            output.write(buffer, 0, read);
            left -= read;
        }
    }

    private static String type(byte[] data, int offset) {
        return new String(data, offset, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    private static long u32(byte[] data, int offset) {
        return ((long) data[offset] & 255) << 24 | (data[offset + 1] & 255) << 16
                | (data[offset + 2] & 255) << 8 | (data[offset + 3] & 255);
    }
    private static long u64(byte[] data, int offset) {
        return (u32(data, offset) << 32) | u32(data, offset + 4);
    }
    private static void put32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >> 24);
        data[offset + 1] = (byte) (value >> 16);
        data[offset + 2] = (byte) (value >> 8);
        data[offset + 3] = (byte) value;
    }
    private static void put64(byte[] data, int offset, long value) {
        put32(data, offset, value >>> 32);
        put32(data, offset + 4, value);
    }
}
