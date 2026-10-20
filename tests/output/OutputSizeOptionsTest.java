package com.oldphone.ipcam;

import java.util.Arrays;
import java.util.List;

public final class OutputSizeOptionsTest {
    public static void main(String[] args) {
        // Dropping exact reduction or admitting mismatched saved sizes would mix
        // a selected 16:9 menu with other output ratios.
        int[][] camera = {{640,480}, {1280,720}, {1080,1920}, {1024,590},
                {1280,720}, {641,480}, {8192,4608}, null};
        List<OutputSizeOptions.Ratio> ratios = OutputSizeOptions.ratios(camera, 1000, 600);
        check(ratios.size() == 5, "common ratios, exact camera ratio and saved ratio only");
        ratio(ratios.get(0), 16, 9);
        ratio(ratios.get(1), 4, 3);
        ratio(ratios.get(2), 1, 1);
        ratio(ratios.get(3), 512, 295);
        ratio(ratios.get(4), 5, 3);
        check("9:16".equals(ratios.get(0).label(1)), "portrait ratio label");

        OutputSizeOptions.Ratio wide = OutputSizeOptions.ratioOf(1920, 1080);
        List<int[]> landscape = OutputSizeOptions.sizes(wide, 0, camera, 640, 480);
        check(contains(landscape, 1280, 720), "16:9 output exists with 4:3 capture input");
        check(!contains(landscape, 640, 480), "do not inject mismatched saved size");
        check(!contains(landscape, 1024, 590), "do not round unusual sensor ratio to 16:9");
        long previousArea = 0;
        int[] previous = null;
        for (int[] size : landscape) {
            check((long) size[0] * 9 == (long) size[1] * 16, "all outputs match selected ratio");
            check((long) size[0] * size[1] >= previousArea, "ascending pixel count");
            check(previous == null || previous[0] != size[0] || previous[1] != size[1],
                    "duplicate size removed");
            previousArea = (long) size[0] * size[1];
            previous = size;
        }
        List<int[]> onlyFourThree = OutputSizeOptions.sizes(wide, 0,
                new int[][]{{640,480}}, 640, 480);
        check(contains(onlyFourThree, 1280, 720), "output presets do not depend on source ratio");
        List<int[]> portrait = OutputSizeOptions.sizes(wide, 1, camera, 900, 1600);
        check(contains(portrait, 720, 1280) && contains(portrait, 900, 1600),
                "portrait presets and saved exact size");
        check(!contains(portrait, 1280, 720), "portrait menu contains only portrait dimensions");
        check(contains(OutputSizeOptions.sizes(OutputSizeOptions.ratioOf(1024,590),
                0, camera, 1280, 720), 1024, 590), "unusual exact camera ratio is selectable");

        List<int[]> candidates = Arrays.asList(new int[]{320,180}, new int[]{640,360},
                new int[]{1280,720}, new int[]{1920,1080});
        size(OutputSizeOptions.chooseSize(candidates, 640, 360), 640, 360);
        size(OutputSizeOptions.chooseSize(candidates, 800, 600), 640, 360);
        size(OutputSizeOptions.chooseSize(candidates, 160, 120), 320, 180);
        size(OutputSizeOptions.orient(1280,720,1), 720,1280);
        size(OutputSizeOptions.orient(720,1280,0), 1280,720);
        size(OutputSizeOptions.orient(720,1280,2), 720,1280);
        System.out.println("PASS OutputSizeOptionsTest (exact ratios, filtered sizes, source independence, orientation, selection)");
    }
    private static boolean contains(List<int[]> sizes, int width, int height) {
        for (int[] size : sizes) if (size[0] == width && size[1] == height) return true;
        return false;
    }
    private static void ratio(OutputSizeOptions.Ratio actual, int longer, int shorter) {
        check(actual.longSide == longer && actual.shortSide == shorter,
                "expected ratio " + longer + ":" + shorter);
    }
    private static void size(int[] actual, int width, int height) {
        check(actual[0] == width && actual[1] == height,
                "expected " + width + "x" + height + ", got " + Arrays.toString(actual));
    }
    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
