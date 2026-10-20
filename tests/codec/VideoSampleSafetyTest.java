package com.oldphone.ipcam;

public final class VideoSampleSafetyTest {
    public static void main(String[] args) {
        H264Encoder.validateVideoSample(slice(5, 0, 2), 1000, Long.MIN_VALUE, true);
        H264Encoder.validateVideoSample(slice(1, 0, 0), 2000, 1000, true);
        H264Encoder.validateVideoSample(slice(1, 0, 5), 3000, 2000, true);
        rejects(slice(1, 0, 1), 3000, 2000, true, "B slices cannot reach pre-API25 MP4");
        rejects(slice(1, 0, 6), 3000, 2000, true, "all-B slice_type also rejected");
        // A large first_mb_in_slice crosses byte boundaries and exercises Exp-Golomb parsing.
        rejects(slice(1, 65535, 1), 3000, 2000, true, "B slice after a large macroblock number");
        H264Encoder.validateVideoSample(slice(1, 0, 1), 3000, 2000, false);
        rejects(slice(1, 0, 0), 1000, 2000, false, "reordered timestamps cannot reach strict MP4 timeline");
        rejects(new byte[]{0, 0, 1, 0x41, 0}, 3000, 2000, true, "truncated slice header");
        H264Encoder.validateVideoSample(new byte[]{0, 0, 1, 0x67, 0x42, 0, 0x1f}, 3000, 2000, true);
        byte[] idr = slice(5, 0, 2), b = slice(1, 0, 1);
        byte[] multiple = new byte[idr.length + b.length];
        System.arraycopy(idr, 0, multiple, 0, idr.length);
        System.arraycopy(b, 0, multiple, idr.length, b.length);
        rejects(multiple, 3000, 2000, true, "every NAL in the access unit must be examined");
        System.out.println("PASS VideoSampleSafetyTest: I/P/B NALs, old muxer restriction, timestamps, truncated headers");
    }

    private static byte[] slice(int type, int firstMacroblock, int sliceType) {
        String bits = ue(firstMacroblock) + ue(sliceType) + "1";
        while (bits.length() % 8 != 0) bits += "0";
        byte[] raw = new byte[bits.length() / 8 + 5];
        raw[3] = 1;
        raw[4] = (byte) (0x60 | type);
        for (int i = 0; i < bits.length() / 8; i++) {
            raw[i + 5] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        // Insert emulation-prevention bytes like an actual encoded NAL payload.
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        encoded.write(raw, 0, 5);
        int zeros = 0;
        for (int i = 5; i < raw.length; i++) {
            int value = raw[i] & 255;
            if (zeros >= 2 && value <= 3) { encoded.write(3); zeros = 0; }
            encoded.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return encoded.toByteArray();
    }

    private static String ue(int value) {
        String binary = Integer.toBinaryString(value + 1);
        String prefix = "";
        for (int i = 1; i < binary.length(); i++) prefix += "0";
        return prefix + binary;
    }

    private static void rejects(byte[] sample, long pts, long previous, boolean old, String message) {
        try { H264Encoder.validateVideoSample(sample, pts, previous, old); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
}
