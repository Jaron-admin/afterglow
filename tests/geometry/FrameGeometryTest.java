package com.oldphone.ipcam;

import java.util.Arrays;

public final class FrameGeometryTest {
    public static void main(String[] args) {
        int[] rear = {90, 180, 270, 0};
        int[] front = {270, 180, 90, 0};
        for (int i = 0; i < 4; i++) {
            equal(rear[i], FrameGeometry.rotation(90, false, i * 90, 0), "rear direction");
            equal(front[i], FrameGeometry.rotation(270, true, i * 90, 0), "front direction");
        }
        equal(0, FrameGeometry.rotation(270, true, 0, 90), "per-camera calibration");
        FrameGeometry portrait = FrameGeometry.create(640, 480, 90, 1280, 720, 1);
        equal(480, portrait.fullWidth, "portrait full width");
        equal(640, portrait.fullHeight, "portrait full height");
        equal(480, portrait.cropWidth, "full horizontal crop");
        equal(270, portrait.cropHeight, "16:9 crop in portrait input");
        equal(184, portrait.cropTop, "chroma-aligned centered crop");
        for (int rotation = 0; rotation < 360; rotation += 90) {
            FrameGeometry g = FrameGeometry.create(640, 480, rotation, 1280, 720, rotation);
            equal(1280, g.outputWidth, "output must remain landscape");
            equal(720, g.outputHeight, "output height must not follow device");
            if (g.cropLeft < 0 || g.cropTop < 0
                    || g.cropLeft + g.cropWidth > g.fullWidth
                    || g.cropTop + g.cropHeight > g.fullHeight) throw new AssertionError("crop outside frame");
            equal(0, (g.cropLeft | g.cropTop | g.cropWidth | g.cropHeight) & 1, "NV21 crop alignment");
        }
        testPixelsAndChroma();
        testCropExcludesMaskedPixels();
        testFusedRotatedCrop();
        compareFusedToFullFrame();
        testBoundedFullPreview();
        invalid(new Runnable() { public void run() { FrameGeometry.create(5, 4, 0, 4, 4, 0); }});
        invalid(new Runnable() { public void run() { FrameGeometry.create(4, 4, 45, 4, 4, 0); }});
        invalid(new Runnable() { public void run() { FrameGeometry.create(4, 4, 0, 4096, 4096, 0); }});
        System.out.println("PASS geometry: upright rotations, fixed output, crop boundaries, NV21 pixels/chroma, invalid sizes");
    }

    private static void testBoundedFullPreview() {
        for (int rotation = 0; rotation < 360; rotation += 90) {
            FrameGeometry g = FrameGeometry.preview(1920, 1080, rotation, 960, 72);
            equal(0, g.cropLeft, "preview keeps left edge");
            equal(0, g.cropTop, "preview keeps top edge");
            equal(g.fullWidth, g.cropWidth, "preview keeps complete width");
            equal(g.fullHeight, g.cropHeight, "preview keeps complete height");
            equal(rotation % 180 == 0 ? 960 : 540, g.outputWidth, "bounded preview width");
            equal(rotation % 180 == 0 ? 540 : 960, g.outputHeight, "bounded preview height");
            byte[] raw = new byte[1920 * 1080 * 3 / 2];
            for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i * 13);
            byte[] rotated = new byte[raw.length];
            Nv21Transform.rotateFull(raw, g, rotated);
            byte[] actual = new byte[g.outputWidth * g.outputHeight * 3 / 2];
            byte[] expected = new byte[actual.length];
            Nv21Transform.render(raw, g, actual);
            Nv21Transform.render(rotated, FrameGeometry.create(g.fullWidth, g.fullHeight,
                    0, g.outputWidth, g.outputHeight, 0), expected);
            if (!Arrays.equals(expected, actual)) throw new AssertionError("preview rotation/color " + rotation);
        }
        FrameGeometry small = FrameGeometry.preview(640, 480, 90, 960, 73);
        equal(480, small.outputWidth, "preview never upscales");
        equal(640, small.outputHeight, "preview never upscales height");
        FrameGeometry unusual = FrameGeometry.preview(1002, 666, 0, 960, 74);
        equal(1002, unusual.cropWidth, "rounded dimensions must not crop full field of view");
        equal(666, unusual.cropHeight, "rounded preview keeps all source rows");
        equal(0, (unusual.outputWidth | unusual.outputHeight) & 1, "preview NV21 alignment");
        invalid(new Runnable() { public void run() { FrameGeometry.preview(640, 480, 0, 1, 0); }});
    }

    private static void testPixelsAndChroma() {
        // Asymmetric 4x2 input; VU cells must rotate without swapping V and U.
        byte[] source = {1, 2, 3, 4, 5, 6, 7, 8, 91, 41, 92, 42};
        byte[][] expected = {
            {1, 2, 3, 4, 5, 6, 7, 8, 91, 41, 92, 42},
            {5, 1, 6, 2, 7, 3, 8, 4, 91, 41, 92, 42},
            {8, 7, 6, 5, 4, 3, 2, 1, 92, 42, 91, 41},
            {4, 8, 3, 7, 2, 6, 1, 5, 92, 42, 91, 41}
        };
        for (int i = 0; i < 4; i++) {
            int w = i % 2 == 0 ? 4 : 2, h = i % 2 == 0 ? 2 : 4;
            FrameGeometry g = FrameGeometry.create(4, 2, i * 90, w, h, i);
            byte[] full = new byte[12], output = new byte[12];
            Nv21Transform.rotateFull(source, g, full);
            Nv21Transform.render(source, g, output);
            if (!Arrays.equals(expected[i], full) || !Arrays.equals(expected[i], output))
                throw new AssertionError("wrong rotation/color order " + i * 90 + " " + Arrays.toString(output));
        }
    }

    private static void testCropExcludesMaskedPixels() {
        byte[] source = new byte[8 * 8 * 3 / 2];
        for (int y = 0; y < 8; y++) Arrays.fill(source, y * 8, y * 8 + 8, (byte) (y + 1));
        for (int i = 64; i < source.length; i += 2) { source[i] = 90; source[i + 1] = 40; }
        FrameGeometry g = FrameGeometry.create(8, 8, 0, 8, 4, 4);
        byte[] output = new byte[8 * 4 * 3 / 2];
        Nv21Transform.render(source, g, output);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++)
            equal(y + 3, output[y * 8 + x], "only unmasked middle rows reach stream");
        for (int i = 32; i < output.length; i += 2) {
            equal(90, output[i], "V preserved");
            equal(40, output[i + 1], "U preserved");
        }
        FrameGeometry downscale = FrameGeometry.create(8, 8, 90, 4, 2, 5);
        Nv21Transform.render(source, downscale, new byte[12]);
    }

    private static void testFusedRotatedCrop() {
        byte[] source = new byte[96];
        for (int i = 0; i < 64; i++) source[i] = (byte) i;
        for (int i = 0; i < 16; i++) {
            source[64 + i * 2] = (byte) (100 + i);
            source[65 + i * 2] = (byte) (40 + i);
        }
        int[][] expected = {
            {25, 27, 29, 31, 41, 43, 45, 47},
            {51, 35, 19, 3, 53, 37, 21, 5},
            {38, 36, 34, 32, 22, 20, 18, 16},
            {12, 28, 44, 60, 10, 26, 42, 58}
        };
        // Chroma is its own 4x4 grid: sample columns 1/3 and row 2,
        // then map those coordinates back through each rotation.
        int[][] expectedChroma = {{9, 11}, {10, 2}, {6, 4}, {5, 13}};
        for (int rotation = 0; rotation < 4; rotation++) {
            byte[] output = new byte[12];
            Nv21Transform.render(source, FrameGeometry.create(8, 8, rotation * 90, 4, 2, rotation), output);
            for (int i = 0; i < 8; i++) equal(expected[rotation][i], output[i], "fused rotated crop luma");
            for (int i = 0; i < 2; i++) {
                equal(100 + expectedChroma[rotation][i], output[8 + i * 2], "fused V");
                equal(40 + expectedChroma[rotation][i], output[9 + i * 2], "fused U");
            }
        }
    }

    private static void compareFusedToFullFrame() {
        java.util.Random random = new java.util.Random(42);
        int[][] sizes = {{8, 6}, {16, 24}, {32, 18}};
        int[][] outputs = {{4, 2}, {2, 4}, {8, 8}, {20, 12}, {6, 14}};
        for (int[] size : sizes) for (int[] outputSize : outputs) {
            byte[] source = new byte[size[0] * size[1] * 3 / 2];
            random.nextBytes(source);
            for (int r = 0; r < 360; r += 90) {
                FrameGeometry g = FrameGeometry.create(size[0], size[1], r, outputSize[0], outputSize[1], r);
                byte[] full = new byte[source.length];
                Nv21Transform.rotateFull(source, g, full);
                byte[] fused = new byte[g.outputWidth * g.outputHeight * 3 / 2];
                Nv21Transform.render(source, g, fused);
                for (int y = 0; y < g.outputHeight; y++) for (int x = 0; x < g.outputWidth; x++) {
                    int sx = g.cropLeft + (int) ((x + 0.5) * g.cropWidth / g.outputWidth);
                    int sy = g.cropTop + (int) ((y + 0.5) * g.cropHeight / g.outputHeight);
                    equal(full[sy * g.fullWidth + sx], fused[y * g.outputWidth + x], "fused/full luma match");
                }
                for (int y = 0; y < g.outputHeight / 2; y++) for (int x = 0; x < g.outputWidth / 2; x++) {
                    int sx = g.cropLeft / 2 + (int) ((x + 0.5) * g.cropWidth / g.outputWidth);
                    int sy = g.cropTop / 2 + (int) ((y + 0.5) * g.cropHeight / g.outputHeight);
                    int src = g.fullWidth * g.fullHeight + sy * g.fullWidth + sx * 2;
                    int dst = g.outputWidth * g.outputHeight + y * g.outputWidth + x * 2;
                    equal(full[src], fused[dst], "fused/full V match");
                    equal(full[src + 1], fused[dst + 1], "fused/full U match");
                }
            }
        }
    }

    private static void equal(int expected, int actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid geometry accepted");
    }
}
