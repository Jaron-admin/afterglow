package com.oldphone.ipcam;

import java.util.Arrays;

public final class CameraSourceSizeTest {
    public static void main(String[] args) {
        CameraCatalog.Entry back = new CameraCatalog.Entry("camera2:0", "back", "0", null,
                0, 90, 4032, 3024, new int[][]{{640,480},{1280,960},{1920,1080},{2560,1920},
                    {4032,3024},{7680,4320},{5000,1000}}, false);
        size(back, 640, 480, 640, 480);
        size(back, 1280, 720, 1280, 960);
        size(back, 1920, 1080, 2560, 1920);
        size(back, 1080, 1920, 2560, 1920);
        size(back, 7680, 4320, 2560, 1920);
        CameraCatalog.Entry front = new CameraCatalog.Entry("legacy:1", "front", "1", null,
                1, 270, 640, 480, new int[][]{{640,480}}, true);
        if (CameraCatalog.find(Arrays.asList(back, front), "facing:front") != front
                || CameraCatalog.find(Arrays.asList(front, back), "auto") != back)
            throw new AssertionError("migration aliases must preserve facing and auto must prefer back");
        System.out.println("PASS CameraSourceSizeTest (native aspect, sufficient pixels, no upscale over-allocation, limits, facing)");
    }
    private static void size(CameraCatalog.Entry entry, int w, int h, int expectedW, int expectedH) {
        int[] size = CameraCatalog.chooseSourceSize(entry, w, h);
        if (size[0] != expectedW || size[1] != expectedH) throw new AssertionError(w + "x" + h
                + " expected " + expectedW + "x" + expectedH + " actual " + Arrays.toString(size));
    }
}
