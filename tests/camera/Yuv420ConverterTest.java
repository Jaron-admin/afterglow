package com.oldphone.ipcam;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class Yuv420ConverterTest {
    public static void main(String[] args) {
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
        System.out.println("PASS Yuv420ConverterTest (planar, padded, interleaved, direct, read-only, truncated)");
    }
    private static void equal(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError(message
                + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }
}
