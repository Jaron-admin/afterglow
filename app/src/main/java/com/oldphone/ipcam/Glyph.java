package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

/**
 * Rounded line icons, 24-grid, round caps. Stroke 2 is in the 24-unit grid
 * (handoff §5 and §8). These are the prototype paths the screenshots are
 * compared with; §8 allows them in place of Material Symbols Rounded.
 */
final class Glyph {
    private static final Map<String, String> PATHS = new HashMap<String, String>();

    static {
        put("back", "<path d=\"M15 5l-7 7 7 7\"/>");
        put("chev", "<path d=\"M9 6l6 6-6 6\"/>");
        put("down", "<path d=\"M6 9l6 6 6-6\"/>");
        put("close", "<path d=\"M6 6l12 12M18 6L6 18\"/>");
        put("tune", "<path d=\"M4 7h8M17 7h3M4 17h3M12 17h8\"/><circle cx=\"14.5\" cy=\"7\" r=\"2.5\"/><circle cx=\"9.5\" cy=\"17\" r=\"2.5\"/>");
        put("film", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"3.5\"/><path d=\"M10 9.3v5.4l4.5-2.7z\" fill=\"currentColor\"/>");
        put("cast", "<circle cx=\"12\" cy=\"12\" r=\"2\"/><path d=\"M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19.1 4.9a10 10 0 0 1 0 14.2M4.9 19.1a10 10 0 0 1 0-14.2\"/>");
        put("mic", "<rect x=\"9\" y=\"3\" width=\"6\" height=\"11\" rx=\"3\"/><path d=\"M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21\"/>");
        put("motion", "<path d=\"M3 12h4l3-7 4 14 3-7h4\"/>");
        put("sound", "<path d=\"M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4\"/>");
        put("clock", "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M12 7.5V12l3 2\"/>");
        put("gauge", "<path d=\"M4 17a8 8 0 1 1 16 0\"/><path d=\"M12 17l4-5\"/>");
        put("timer", "<circle cx=\"12\" cy=\"13.5\" r=\"7.5\"/><path d=\"M12 10v3.5M9.5 2.5h5\"/>");
        put("hd", "<rect x=\"3\" y=\"5.5\" width=\"18\" height=\"13\" rx=\"3\"/><path d=\"M7.5 9.5v5M7.5 12h3M10.5 9.5v5M13.5 9.5v5h1.5a2.5 2.5 0 0 0 0-5z\"/>");
        put("folder", "<path d=\"M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5z\"/>");
        put("trash", "<path d=\"M4 7h16M9.5 7V4.5h5V7M6.5 7l.9 12.5h9.2L17.5 7M10 11v5M14 11v5\"/>");
        put("moon", "<path d=\"M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z\"/>");
        put("bolt", "<path d=\"M13 3L5 14h6l-1 7 8-11h-6z\"/>");
        put("aspect", "<rect x=\"3\" y=\"5\" width=\"18\" height=\"14\" rx=\"3\"/><path d=\"M7 12V9h3M17 12v3h-3\"/>");
        put("camera", "<path d=\"M4 8.5A1.5 1.5 0 0 1 5.5 7H8l1.6-2.2h4.8L16 7h2.5A1.5 1.5 0 0 1 20 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z\"/><circle cx=\"12\" cy=\"12.8\" r=\"3.3\"/>");
        put("person", "<circle cx=\"12\" cy=\"8\" r=\"3.2\"/><path d=\"M5.5 19.5c.8-3.2 3.2-5 6.5-5s5.7 1.8 6.5 5\"/>");
        put("users", "<circle cx=\"9\" cy=\"8\" r=\"3.5\"/><path d=\"M3 20a6 6 0 0 1 12 0M15.5 4.6a3.5 3.5 0 0 1 0 6.8M17.5 14.2A6 6 0 0 1 21 20\"/>");
        put("rotate", "<path d=\"M19.5 12a7.5 7.5 0 1 1-2.2-5.3\"/><path d=\"M19.5 4v4h-4\"/>");
        put("copy", "<rect x=\"9\" y=\"9\" width=\"11\" height=\"11\" rx=\"2.5\"/><path d=\"M5 15V6a2 2 0 0 1 2-2h8\"/>");
        put("home", "<path d=\"M3.5 11L12 4l8.5 7\"/><path d=\"M6 9.5V20h12V9.5\"/><circle cx=\"12\" cy=\"14.5\" r=\"2\"/>");
        put("text", "<path d=\"M5 6h14M12 6v13\"/>");
        put("check", "<path d=\"M5 12.5l4.5 4.5L19 7.5\"/>");
        put("export", "<path d=\"M12 15V3.5M7.5 8L12 3.5 16.5 8M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13\"/>");
        put("open", "<path d=\"M14 4h6v6M20 4l-8.5 8.5M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10\"/>");
        put("bell", "<path d=\"M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z\"/><path d=\"M10 21h4\"/>");
        put("link", "<path d=\"M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1\"/>");
        put("info", "<circle cx=\"12\" cy=\"12\" r=\"8.5\"/><path d=\"M12 11v5.5M12 7.8v.2\"/>");
        put("sun", "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4L6 18M18 6l1.4-1.4\"/>");
        put("wifi", "<path d=\"M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.6 15.8a5 5 0 0 1 6.8 0\"/><circle cx=\"12\" cy=\"19\" r=\"1\" fill=\"currentColor\"/>");
        put("select", "<rect x=\"4\" y=\"4\" width=\"16\" height=\"16\" rx=\"4\"/><path d=\"M8.5 12l2.5 2.5 4.5-5\"/>");
    }

    private Glyph() {}

    static View well(Context context, String name, int fill, int ink) {
        FrameLayout frame = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(fill);
        frame.setBackground(background);
        ImageView image = new ImageView(context);
        image.setImageDrawable(drawable(context, name, 20, ink, 2f));
        int size = dp(context, 20);
        frame.addView(image, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return frame;
    }

    static Drawable drawable(Context context, String name, int sizeDp, int color, float stroke) {
        Bitmap bitmap = bitmap(context, name, sizeDp, color, stroke);
        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return drawable;
    }

    static Bitmap bitmap(Context context, String name, int sizeDp, int color, float stroke) {
        int size = Math.max(1, dp(context, sizeDp));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        draw(canvas, name, size, color, stroke * context.getResources().getDisplayMetrics().density * sizeDp / 24f);
        return bitmap;
    }

    static void draw(Canvas canvas, String name, int size, int color, float strokePx) {
        String markup = PATHS.get(name);
        if (markup == null) return;
        float scale = size / 24f;
        canvas.save();
        canvas.scale(scale, scale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStrokeWidth(strokePx / scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int cursor = 0;
        while (cursor < markup.length()) {
            int start = markup.indexOf('<', cursor);
            if (start < 0) break;
            int end = markup.indexOf('>', start);
            if (end < 0) break;
            String tag = markup.substring(start + 1, end).replace("/", "").trim();
            cursor = end + 1;
            boolean fill = tag.contains("fill=\"currentColor\"");
            paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
            if (tag.startsWith("path")) {
                canvas.drawPath(parse(attr(tag, "d")), paint);
            } else if (tag.startsWith("circle")) {
                float radius = num(attr(tag, "r"));
                canvas.drawCircle(num(attr(tag, "cx")), num(attr(tag, "cy")), radius, paint);
            } else if (tag.startsWith("rect")) {
                float x = num(attr(tag, "x"));
                float y = num(attr(tag, "y"));
                float w = num(attr(tag, "width"));
                float h = num(attr(tag, "height"));
                float rx = num(attr(tag, "rx"));
                canvas.drawRoundRect(new RectF(x, y, x + w, y + h), rx, rx, paint);
            }
        }
        canvas.restore();
    }

    private static void put(String name, String markup) { PATHS.put(name, markup); }

    private static String attr(String tag, String key) {
        String needle = key + "=\"";
        int start = tag.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = tag.indexOf('"', start);
        return end < 0 ? "" : tag.substring(start, end);
    }

    private static float num(String value) {
        if (value == null || value.length() == 0) return 0f;
        return Float.parseFloat(value);
    }

    static Path parse(String data) {
        Path path = new Path();
        int index = 0;
        char command = 0;
        float cx = 0f;
        float cy = 0f;
        float sx = 0f;
        float sy = 0f;
        int length = data == null ? 0 : data.length();
        while (index < length) {
            index = skip(data, index);
            if (index >= length) break;
            char letter = data.charAt(index);
            if ((letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z')) {
                command = letter;
                index++;
            }
            if (command == 'Z' || command == 'z') {
                path.close();
                cx = sx;
                cy = sy;
                continue;
            }
            if (command == 'M' || command == 'm') {
                float x = read(data, index);
                index = nextIndex;
                float y = read(data, index);
                index = nextIndex;
                if (command == 'm') { x += cx; y += cy; }
                path.moveTo(x, y);
                cx = x;
                cy = y;
                sx = x;
                sy = y;
                command = command == 'm' ? 'l' : 'L';
                continue;
            }
            if (command == 'L' || command == 'l') {
                float x = read(data, index);
                index = nextIndex;
                float y = read(data, index);
                index = nextIndex;
                if (command == 'l') { x += cx; y += cy; }
                path.lineTo(x, y);
                cx = x;
                cy = y;
                continue;
            }
            if (command == 'H' || command == 'h') {
                float x = read(data, index);
                index = nextIndex;
                if (command == 'h') x += cx;
                path.lineTo(x, cy);
                cx = x;
                continue;
            }
            if (command == 'V' || command == 'v') {
                float y = read(data, index);
                index = nextIndex;
                if (command == 'v') y += cy;
                path.lineTo(cx, y);
                cy = y;
                continue;
            }
            if (command == 'A' || command == 'a') {
                float rx = read(data, index); index = nextIndex;
                float ry = read(data, index); index = nextIndex;
                float angle = read(data, index); index = nextIndex;
                float large = read(data, index); index = nextIndex;
                float sweep = read(data, index); index = nextIndex;
                float x = read(data, index); index = nextIndex;
                float y = read(data, index); index = nextIndex;
                if (command == 'a') { x += cx; y += cy; }
                arc(path, cx, cy, rx, ry, angle, large != 0f, sweep != 0f, x, y);
                cx = x;
                cy = y;
                continue;
            }
            break;
        }
        return path;
    }

    private static int nextIndex;

    private static int skip(String data, int index) {
        while (index < data.length()) {
            char c = data.charAt(index);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t') index++;
            else break;
        }
        return index;
    }

    private static float read(String data, int index) {
        index = skip(data, index);
        int start = index;
        if (index < data.length() && (data.charAt(index) == '+' || data.charAt(index) == '-')) index++;
        boolean dot = false;
        while (index < data.length()) {
            char c = data.charAt(index);
            if (c >= '0' && c <= '9') index++;
            else if (c == '.' && !dot) { dot = true; index++; }
            else break;
        }
        nextIndex = index;
        if (start == index) return 0f;
        return Float.parseFloat(data.substring(start, index));
    }

    private static void arc(Path path, float x0, float y0, float rx, float ry, float angle,
                            boolean large, boolean sweep, float x, float y) {
        if (rx == 0f || ry == 0f || (x0 == x && y0 == y)) {
            path.lineTo(x, y);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(angle);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x) / 2d;
        double dy = (y0 - y) / 2d;
        double x1 = cos * dx + sin * dy;
        double y1 = -sin * dx + cos * dy;
        double rx2 = rx * rx;
        double ry2 = ry * ry;
        double lam = x1 * x1 / rx2 + y1 * y1 / ry2;
        if (lam > 1) {
            double scale = Math.sqrt(lam);
            rx *= scale;
            ry *= scale;
            rx2 = rx * rx;
            ry2 = ry * ry;
        }
        double sign = large == sweep ? -1d : 1d;
        double num = rx2 * ry2 - rx2 * y1 * y1 - ry2 * x1 * x1;
        double den = rx2 * y1 * y1 + ry2 * x1 * x1;
        double coef = sign * Math.sqrt(Math.max(0d, num / den));
        double cx1 = coef * rx * y1 / ry;
        double cy1 = coef * -ry * x1 / rx;
        double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2d;
        double cy = sin * cx1 + cos * cy1 + (y0 + y) / 2d;
        double theta = angleBetween(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry);
        double delta = angleBetween((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry);
        if (!sweep && delta > 0) delta -= Math.PI * 2;
        if (sweep && delta < 0) delta += Math.PI * 2;
        RectF oval = new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry));
        path.arcTo(oval, (float) Math.toDegrees(theta), (float) Math.toDegrees(delta));
    }

    private static double angleBetween(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy);
        double angle = Math.acos(Math.max(-1d, Math.min(1d, dot / len)));
        if (ux * vy - uy * vx < 0) angle = -angle;
        return angle;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
