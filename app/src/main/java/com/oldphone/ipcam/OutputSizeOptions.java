package com.oldphone.ipcam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Output menus are independent of the camera's full-frame capture size. */
public final class OutputSizeOptions {
    private static final int[][] STANDARD_SIZES = {
            {320,180}, {640,360}, {960,540}, {1280,720}, {1920,1080}, {2560,1440}, {3840,2160},
            {320,240}, {640,480}, {800,600}, {1024,768}, {1280,960}, {1600,1200},
            {1920,1440}, {2560,1920},
            {320,320}, {480,480}, {640,640}, {720,720}, {960,960}, {1080,1080},
            {1440,1440}, {1920,1920}, {2160,2160}, {2560,2560}
    };
    private OutputSizeOptions() {}

    /** Reduced ratio, always with the longer side first, regardless of output direction. */
    public static final class Ratio {
        public final int longSide, shortSide;
        private Ratio(int longer, int shorter) { longSide = longer; shortSide = shorter; }
        public String label(int layout) {
            checkLayout(layout);
            return layout == 1 ? shortSide + ":" + longSide : longSide + ":" + shortSide;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Ratio)) return false;
            Ratio ratio = (Ratio) other;
            return longSide == ratio.longSide && shortSide == ratio.shortSide;
        }
        @Override public int hashCode() { return 31 * longSide + shortSide; }
        @Override public String toString() { return longSide + ":" + shortSide; }
    }

    public static Ratio ratioOf(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Dimensions must be positive");
        int longer = Math.max(width, height);
        int shorter = Math.min(width, height);
        int first = longer, second = shorter;
        while (second != 0) {
            int remainder = first % second;
            first = second;
            second = remainder;
        }
        return new Ratio(longer / first, shorter / first);
    }

    /** Common formats first, followed by exact ratios exposed by this camera or saved by the user. */
    public static List<Ratio> ratios(int[][] cameraSizes, int currentWidth, int currentHeight) {
        List<Ratio> result = new ArrayList<Ratio>();
        result.add(ratioOf(16, 9));
        result.add(ratioOf(4, 3));
        result.add(ratioOf(1, 1));
        if (cameraSizes != null) for (int[] size : cameraSizes) {
            if (valid(size)) addRatio(result, size[0], size[1]);
        }
        if (FrameGeometry.validOutputSize(currentWidth, currentHeight))
            addRatio(result, currentWidth, currentHeight);
        return result;
    }

    private static void addRatio(List<Ratio> ratios, int width, int height) {
        Ratio ratio = ratioOf(width, height);
        if (!ratios.contains(ratio)) ratios.add(ratio);
    }

    /**
     * Only dimensions matching the selected exact ratio enter the menu. Standard
     * cropped outputs remain available even when the camera exposes only 4:3 capture.
     */
    public static List<int[]> sizes(Ratio ratio, int layout, int[][] cameraSizes,
                                   int currentWidth, int currentHeight) {
        if (ratio == null) throw new IllegalArgumentException("Choose an output ratio");
        checkLayout(layout);
        List<int[]> result = new ArrayList<int[]>();
        for (int[] size : STANDARD_SIZES) addSize(result, ratio, layout, size);
        if (cameraSizes != null) for (int[] size : cameraSizes)
            addSize(result, ratio, layout, size);
        addSize(result, ratio, layout, new int[]{currentWidth, currentHeight});
        Collections.sort(result, new Comparator<int[]>() {
            @Override public int compare(int[] first, int[] second) {
                long a = area(first), b = area(second);
                return a < b ? -1 : a > b ? 1 : Integer.compare(first[0], second[0]);
            }
        });
        return result;
    }

    private static void addSize(List<int[]> sizes, Ratio ratio, int layout, int[] candidate) {
        if (!valid(candidate) || !ratio.equals(ratioOf(candidate[0], candidate[1]))) return;
        int[] oriented = orient(candidate[0], candidate[1], layout);
        for (int[] size : sizes)
            if (size[0] == oriented[0] && size[1] == oriented[1]) return;
        sizes.add(oriented);
    }

    /** Preserve an exact choice, otherwise prefer the largest option not exceeding its pixel count. */
    public static int[] chooseSize(List<int[]> sizes, int previousWidth, int previousHeight) {
        if (sizes == null) throw new IllegalArgumentException("No output sizes");
        long target = (long) previousWidth * previousHeight;
        int[] lower = null, smallest = null;
        for (int[] size : sizes) {
            if (!valid(size)) continue;
            if (size[0] == previousWidth && size[1] == previousHeight) return size.clone();
            if (smallest == null || area(size) < area(smallest)) smallest = size;
            if (area(size) <= target && (lower == null || area(size) > area(lower))) lower = size;
        }
        int[] result = lower != null ? lower : smallest;
        if (result == null) throw new IllegalArgumentException("No valid output sizes");
        return result.clone();
    }

    /** 0 = landscape, 1 = portrait, 2 = custom dimensions unchanged. */
    public static int[] orient(int width, int height, int layout) {
        checkLayout(layout);
        if (layout == 2) return new int[]{width, height};
        int longer = Math.max(width, height), shorter = Math.min(width, height);
        return layout == 0 ? new int[]{longer, shorter} : new int[]{shorter, longer};
    }

    private static long area(int[] size) { return (long) size[0] * size[1]; }
    private static boolean valid(int[] size) {
        return size != null && size.length >= 2 && FrameGeometry.validOutputSize(size[0], size[1]);
    }
    private static void checkLayout(int layout) {
        if (layout < 0 || layout > 2) throw new IllegalArgumentException("Invalid output direction");
    }
}
