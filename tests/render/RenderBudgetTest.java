package com.oldphone.ipcam;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Guards the draw path against the regression that made v2.2 unusable: a CSS-style
 * box-shadow rasterised per frame, a preview handed to {@code ImageView.setImageBitmap},
 * and a recording list rebound once per thumbnail.
 *
 * <p>These are source checks, not rendering checks. Robolectric screenshots freeze motion
 * and draw a single frame, so they can never see a per-frame cost; this test can.
 */
public final class RenderBudgetTest {
    private static final String SOURCE = "app/src/main/java/com/oldphone/ipcam/";

    public static void main(String[] args) throws Exception {
        String motion = read("Motion.java");

        // 1. A shadow is rasterised exactly once, inside render(), and nowhere near a draw.
        int created = occurrences(motion, "Bitmap.createBitmap");
        if (created != 1) {
            throw new AssertionError("Motion.java must rasterise shadows in one place, found "
                    + created + " calls to Bitmap.createBitmap");
        }
        int render = motion.indexOf("private static Shadow render(");
        if (render < 0 || motion.indexOf("Bitmap.createBitmap") < render) {
            throw new AssertionError("Bitmap.createBitmap must live inside render(), which is "
                    + "only reached on a cache miss");
        }

        // 2. onDraw never allocates and never touches the settings provider.
        for (String name : new String[] {"Plate", "Halo", "BreathDot", "Mark"}) {
            String type = method(motion, "class " + name + " extends View {", name);
            String draw = method(type, "protected void onDraw(Canvas canvas)", name + ".onDraw");
            reject(draw, name + ".onDraw", "Bitmap.createBitmap");
            reject(draw, name + ".onDraw", "new int[");
            reject(draw, name + ".onDraw", "Settings.");
        }

        // 3. reduced() is memoised: the binder read lives behind the cache, not in refresh.
        if (motion.indexOf("Boolean cached = reducedCache;") < 0) {
            throw new AssertionError("Motion.reduced must short-circuit on reducedCache; a "
                    + "Settings.Global read per frame is a binder call per frame");
        }

        // 4. The preview keeps one bitmap and invalidates; it must not re-layout per frame.
        String ui = read("V22Ui.java");
        if (ui.contains("setImageBitmap")) {
            throw new AssertionError("V22Ui must drive the preview through PreviewPane; "
                    + "setImageBitmap allocates a BitmapDrawable and requests layout per frame");
        }

        // 5. A thumbnail completing must not rebind the whole list.
        String recordings = read("RecordingsActivity.java");
        String request = method(recordings, "private void requestThumb(", "requestThumb");
        reject(request, "requestThumb", "notifyDataSetChanged");

        System.out.println("PASS render budget: shadow rasterised once, no allocation or "
                + "settings read in onDraw, preview via PreviewPane, per-row thumbnail updates");
    }

    private static void reject(String body, String where, String token) {
        if (body.contains(token)) {
            throw new AssertionError(where + " must not contain " + token);
        }
    }

    /** Extracts a method body by brace matching, starting at the first signature match. */
    private static String method(String source, String signature, String owner) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Missing " + signature + " (looked for in " + owner + ")");
        }
        int open = source.indexOf('{', start);
        if (open < 0) throw new AssertionError("No body for " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("Unbalanced body for " + signature);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        for (int at = source.indexOf(token); at >= 0; at = source.indexOf(token, at + 1)) count++;
        return count;
    }

    private static String read(String name) throws IOException {
        File file = new File(SOURCE + name);
        if (!file.isFile()) throw new AssertionError("Missing source file: " + file.getPath());
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }
}
