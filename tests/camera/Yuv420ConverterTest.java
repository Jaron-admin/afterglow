package com.oldphone.ipcam;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class Yuv420ConverterTest {
    public static void main(String[] args) {
        lumaBufferVariantsPreservePixelsAndPositions();
        truncatedLumaDoesNotChangeOutput();
        byte[] out = new byte[12];
        Yuv420Converter.toNv21(4, 2,
                ByteBuffer.wrap(new byte[]{1,2,3,4,5,6,7,8}), 4, 1,
                ByteBuffer.wrap(new byte[]{9,10}), 2, 1,
                ByteBuffer.wrap(new byte[]{11,12}), 2, 1, out);
        equal(new byte[]{1,2,3,4,5,6,7,8,11,9,12,10}, out,
                "planar YUV must become Y followed by VU, not UV");
        ByteBuffer y = ByteBuffer.wrap(new byte[]{99,1,2,3,4,88,88,5,6,7,8});
        y.position(1);
        ByteBuffer u = ByteBuffer.wrap(new byte[]{99,9,77,10});
        u.position(1);
        ByteBuffer v = ByteBuffer.wrap(new byte[]{99,11,77,12});
        v.position(1);
        Yuv420Converter.toNv21(4, 2, y, 6, 1, u, 4, 2, v, 4, 2, out);
        equal(new byte[]{1,2,3,4,5,6,7,8,11,9,12,10}, out,
                "padded rows and pixel-stride 2 must ignore padding and plane offsets");
        if (y.position() != 1 || u.position() != 1 || v.position() != 1)
            throw new AssertionError("plane positions must remain unchanged");
        byte[] tall = new byte[24];
        ByteBuffer ys = ByteBuffer.wrap(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}).asReadOnlyBuffer();
        ByteBuffer us = ByteBuffer.wrap(new byte[]{99,9,0,10,0,88,88,11,0,12});
        us.position(1);
        ByteBuffer vs = ByteBuffer.allocateDirect(10);
        vs.put(new byte[]{99,21,0,22,0,88,88,23,0,24}); vs.position(1);
        Yuv420Converter.toNv21(4, 4, ys, 4, 1, us, 6, 2, vs.asReadOnlyBuffer(), 6, 2, tall);
        equal(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,21,9,22,10,23,11,24,12}, tall,
                "direct/read-only planes must honor multiple chroma rows without requiring final-row padding");
        Arrays.fill(out, (byte) 55);
        try {
            Yuv420Converter.toNv21(4, 2, y, 6, 1, u, 4, 2,
                    ByteBuffer.wrap(new byte[]{11}), 4, 2, out);
            throw new AssertionError("truncated V plane must be rejected");
        } catch (IllegalArgumentException expected) { }
        equal(new byte[]{55,55,55,55,55,55,55,55,55,55,55,55}, out,
                "bad plane validation must occur before overwriting a borrowed destination");
        System.out.println("PASS Yuv420ConverterTest (planar, padded, interleaved, direct, read-only, truncated, positioned luma)");
    }

    private static void lumaBufferVariantsPreservePixelsAndPositions() {
        byte[] expected = new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,
                21,31,22,32,23,33,24,34};
        for (boolean direct : new boolean[]{false, true}) {
            for (boolean readOnly : new boolean[]{false, true}) {
                for (int stride : new int[]{1, 2}) {
                    for (int padding : new int[]{0, 3}) {
                        int rowStride = 4 * stride + padding;
                        // The final row ends at its final pixel, without trailing padding.
                        ByteBuffer y = buffer(direct, 3 + 3 * rowStride + 3 * stride + 1);
                        for (int row = 0; row < 4; row++) {
                            for (int col = 0; col < 4; col++) {
                                y.put(3 + row * rowStride + col * stride, (byte) (1 + row * 4 + col));
                            }
                        }
                        y.position(3);
                        if (readOnly) y = y.asReadOnlyBuffer();
                        ByteBuffer u = ByteBuffer.wrap(new byte[]{99,31,32,33,34});
                        ByteBuffer v = ByteBuffer.wrap(new byte[]{99,21,22,23,24});
                        u.position(1); v.position(1);
                        int limit = y.limit();
                        y.mark(); u.mark(); v.mark();
                        byte[] out = new byte[24];
                        Yuv420Converter.toNv21(4, 4, y, rowStride, stride, u, 2, 1, v, 2, 1, out);
                        equal(expected, out, "positioned luma direct=" + direct + " readOnly=" + readOnly
                                + " pixelStride=" + stride + " padding=" + padding);
                        if (y.position() != 3 || y.limit() != limit || u.position() != 1 || v.position() != 1)
                            throw new AssertionError("conversion must preserve all plane positions and limits");
                        y.reset(); u.reset(); v.reset(); // Original marks must also survive bulk reads.
                    }
                }
            }
        }
    }

    private static void truncatedLumaDoesNotChangeOutput() {
        ByteBuffer y = ByteBuffer.allocateDirect(14);
        y.position(3);
        y.limit(12); // Two rows of four pixels at stride 6 require limit 13.
        byte[] out = new byte[12];
        Arrays.fill(out, (byte) 55);
        try {
            Yuv420Converter.toNv21(4, 2, y.asReadOnlyBuffer(), 6, 1,
                    ByteBuffer.wrap(new byte[]{9,10}), 2, 1,
                    ByteBuffer.wrap(new byte[]{11,12}), 2, 1, out);
            throw new AssertionError("truncated positioned luma must be rejected");
        } catch (IllegalArgumentException expected) { }
        equal(new byte[]{55,55,55,55,55,55,55,55,55,55,55,55}, out,
                "truncated luma must not partially overwrite destination");
        if (y.position() != 3 || y.limit() != 12) throw new AssertionError("invalid input position changed");
    }

    private static ByteBuffer buffer(boolean direct, int capacity) {
        return direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
    }
    private static void equal(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError(message
                + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }
}
