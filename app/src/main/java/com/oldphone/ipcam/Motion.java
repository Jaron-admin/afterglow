package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Handoff §6. Glow cycles in 3.2s. Dots and rings cycle in 1.6s.
 * Screenshots freeze on the prototype's shot frame. Reduced motion is a static 2dp stroke.
 */
final class Motion {
    static final int ORANGE = 0xFFFF7A3D;
    static final int RED = 0xFFF0445A;

    private Motion() {}

    static boolean reduced(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** 0 at the dim end, 1 at the bright middle of the 3.2s glow. */
    static float glow(Context context) {
        if (reduced(context)) return 0f;
        return sine(clock(context, 3.2f, 1.6f));
    }

    /** 0 full size, 1 the small dim end of the 1.6s dot. */
    static float breath(Context context) {
        if (reduced(context)) return 0f;
        return sine(clock(context, 1.6f, 0.2f));
    }

    /** Ring progress 0..1, or -1 when the ring is hidden. */
    static float ring(Context context) {
        if (reduced(context)) return -1f;
        return clock(context, 1.6f, 0.5f);
    }

    private static float clock(Context context, float period, float shotElapsed) {
        if (ScreenSnapshot.freezeMotion) return shotElapsed / period;
        long now = SystemClock.uptimeMillis();
        return (now % (long) (period * 1000f)) / (period * 1000f);
    }

    private static float sine(float fraction) {
        return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * fraction));
    }

    static float easeRing(float t) {
        float u = t;
        for (int i = 0; i < 6; i++) {
            float x = cubic(u, 0.2f, 0.4f);
            float dx = cubicDerivative(u, 0.2f, 0.4f);
            if (Math.abs(dx) < 1e-3f) break;
            u = Math.max(0f, Math.min(1f, u - (x - t) / dx));
        }
        return cubic(u, 0f, 1f);
    }

    private static float cubic(float u, float a, float b) {
        float c = 1f - u;
        return 3f * c * c * u * a + 3f * c * u * u * b + u * u * u;
    }

    private static float cubicDerivative(float u, float a, float b) {
        float c = 1f - u;
        return 3f * c * c * a + 6f * c * u * (b - a) + 3f * u * u * (1f - b);
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static float px(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    /** Outer reach of the preview glow: spread 6dp + blur 26dp. */
    static int glowPad(Context context) { return dp(context, 32); }

    /** Preview halo. The view is larger than the preview by {@link #glowPad}. */
    static final class Plate extends View {
        boolean live;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Plate(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!live) return;
            float density = getResources().getDisplayMetrics().density;
            float pad = 32f * density;
            RectF box = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
            if (box.width() <= 0f || box.height() <= 0f) return;
            boolean still = reduced(getContext());
            float peak = still ? 0f : glow(getContext());
            float radius = 20f * density;
            if (!still) {
                // Light pages need the CSS-sized blur (sigma ≈ blur/2) or the halo
                // disappears into the pale background. Dark already matches the shot,
                // so its kernel stays tight. Both fade before the screen edge.
                boolean night = (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                float pagePad = 16f * density;
                float screenLeft = pad - pagePad;
                float screenRight = Math.max(screenLeft + 1f, getWidth() - screenLeft);
                drawShadow(canvas, box, radius, ORANGE,
                        lerp(14f, 26f, peak) * density, lerp(2f, 6f, peak) * density,
                        lerp(0.22f, 0.50f, peak), 0f, false,
                        night ? 3f : 2f, screenLeft, screenRight, 10f * density);
                // Dark inner glow stays on the tighter kernel. Light uses the CSS
                // inset blur (sigma ≈ blur/2) from handoff §6.
                if (night) {
                    drawShadow(canvas, box, radius, 0xFFFF8C50,
                            lerp(18f, 26f, peak) * density, lerp(2f, 3f, peak) * density,
                            lerp(0.18f, 0.30f, peak), 0f, true);
                } else {
                    drawShadow(canvas, box, radius, 0xFFFF8C50,
                            lerp(18f, 26f, peak) * density, lerp(2f, 3f, peak) * density,
                            lerp(0.18f, 0.30f, peak), 0f, true, 2f, 0f, 0f, 0f);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((still ? 2f : lerp(2f, 3f, peak)) * density);
            paint.setColor(withAlpha(ORANGE, still ? 1f : lerp(0.55f, 0.95f, peak)));
            canvas.drawRoundRect(box, radius, radius, paint);
            if (!still && !ScreenSnapshot.freezeMotion) postInvalidateOnAnimation();
        }
    }

    /** Stop-stream or stop-record shadow, drawn behind a child button. */
    static final class Halo extends View {
        static final int NONE = 0;
        static final int ORANGE = 1;
        static final int RED = 2;
        static final int STATIC = 3;
        static final int SHADOW = 4;
        int mode = NONE;
        int inset;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Halo(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            if (mode == NONE || getWidth() == 0) return;
            float density = getResources().getDisplayMetrics().density;
            float pad = inset;
            RectF box = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
            if (box.width() <= 0f || box.height() <= 0f) return;
            float radius = box.height() / 2f;
            boolean still = mode == STATIC || reduced(getContext());
            float peak = still ? 0f : glow(getContext());
            // Halo.ORANGE is the mode, not the paint color.
            int ink = mode == RED ? Motion.RED : Motion.ORANGE;
            if (!still && mode == ORANGE) {
                // Outer glow only. The 2dp stroke is inset on the button face.
                drawShadow(canvas, box, radius, ink,
                        lerp(12f, 26f, peak) * density, 0f,
                        lerp(0.18f, 0.40f, peak), lerp(4f, 8f, peak) * density, false);
            } else if (!still && mode == RED) {
                drawShadow(canvas, box, radius, ink,
                        lerp(16f, 30f, peak) * density, 0f,
                        lerp(0.30f, 0.55f, peak), lerp(6f, 10f, peak) * density, false);
            } else if (mode == SHADOW) {
                drawShadow(canvas, box, radius, Motion.ORANGE, 22f * density, 0f, 0.42f, 8f * density, false);
            } else if (mode != RED) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * density);
                paint.setColor(withAlpha(ink, mode == STATIC ? 0.4f : 1f));
                canvas.drawRoundRect(box, radius, radius, paint);
            }
            if (!still && !ScreenSnapshot.freezeMotion && mode != NONE) postInvalidateOnAnimation();
        }
    }

    static final class BreathDot extends View {
        int color = ORANGE;
        boolean breathe;
        float radiusDp = 5f;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BreathDot(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float amount = breathe ? breath(getContext()) : 0f;
            float radius = radiusDp * density * lerp(1f, 0.82f, amount);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, breathe ? lerp(1f, 0.4f, amount) : 1f));
            canvas.drawCircle(cx, cy, radius, paint);
            float ring = breathe ? ring(getContext()) : -1f;
            if (ring >= 0f) {
                float eased = easeRing(ring);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f * density);
                paint.setColor(withAlpha(color, lerp(0.7f, 0f, eased)));
                canvas.drawCircle(cx, cy, radiusDp * density * lerp(0.6f, 1.6f, eased), paint);
            }
            if (breathe && !ScreenSnapshot.freezeMotion && !reduced(getContext())) postInvalidateOnAnimation();
        }
    }

    /** 22dp ring with a dot, or a rounded square while recording or streaming. */
    static final class Mark extends View {
        boolean square;
        int color = Color.WHITE;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(Context context) { super(context); }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = dp(getContext(), 22);
            setMeasuredDimension(size, size);
        }

        @Override protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * density);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, 9f * density, paint);
            paint.setStyle(Paint.Style.FILL);
            if (square) {
                float half = 4f * density;
                canvas.drawRoundRect(new RectF(cx - half, cy - half, cx + half, cy + half),
                        2f * density, 2f * density, paint);
            } else {
                canvas.drawCircle(cx, cy, 4.5f * density, paint);
            }
        }
    }

    static final class StatusPill extends LinearLayout {
        final BreathDot dot;
        final TextView label;

        StatusPill(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(context, 8), 0, dp(context, 10), 0);
            dot = new BreathDot(context);
            label = new TextView(context);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label.setTextColor(Color.WHITE);
            setClipChildren(false);
            addView(dot, new LayoutParams(dp(context, 22), dp(context, 22)));
            LayoutParams text = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            text.leftMargin = dp(context, 6);
            addView(label, text);
        }

        void show(String text, int background, int dotColor, boolean breathe) {
            label.setText(text);
            dot.color = dotColor;
            dot.breathe = breathe;
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setCornerRadius(px(getContext(), 15));
            shape.setColor(background);
            setBackground(shape);
            setVisibility(VISIBLE);
            dot.invalidate();
        }
    }

    /** Handoff card shadow, light theme only: 0 1px 2px /5% and 0 4px 16px /5%. */
    static void lightCardShadow(Canvas canvas, RectF box, float radius, float density) {
        int ink = 0xFF3C2814;
        drawShadow(canvas, box, radius, ink, 2f * density, 0f, 0.05f, 1f * density, false, 2f, 0f, 0f, 0f);
        drawShadow(canvas, box, radius, ink, 16f * density, 0f, 0.05f, 4f * density, false, 2f, 0f, 0f, 0f);
    }

    /** Soft disc glow. Blur is in pixels; the kernel matches a CSS box-shadow. */
    static void discGlow(Canvas canvas, RectF box, float radius, int color, float blur, float alpha) {
        drawShadow(canvas, box, radius, color, blur, 0f, alpha, 0f, false, 2f, 0f, 0f, 0f);
    }

    /**
     * Soft shadow that follows the rounded shape. A blurred mask fades to nothing,
     * so the fringe is not a stack of hard-edged strokes.
     * The offset is applied to the shadow shape before the blur. Punching the
     * original box afterwards keeps the face clear without leaving a page-colored
     * slab where a shifted hole used to sit.
     */
    private static void drawShadow(Canvas canvas, RectF box, float radius, int color,
                                   float blur, float spread, float alpha, float dy, boolean inset) {
        drawShadow(canvas, box, radius, color, blur, spread, alpha, dy, inset, 3f, 0f, 0f, 0f);
    }

    private static void drawShadow(Canvas canvas, RectF box, float radius, int color,
                                   float blur, float spread, float alpha, float dy, boolean inset,
                                   float kernelDivisor, float fadeLeft, float fadeRight, float fadeSpan) {
        if (alpha <= 0.004f || blur < 1f) return;
        int pass = Math.max(1, Math.round(blur / Math.max(1f, kernelDivisor)));
        float reach = Math.max(0f, spread) + Math.abs(dy) + pass * 3f + 8f;
        int left = (int) Math.floor(box.left - reach);
        int top = (int) Math.floor(box.top - reach);
        int width = (int) Math.ceil(box.right + reach) - left;
        int height = (int) Math.ceil(box.bottom + reach) - top;
        if (width < 4 || height < 4 || width * height > 2_500_000) return;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas mask = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        float rad = Math.min(radius + Math.max(0f, spread), Math.min(box.width(), box.height()) / 2f + spread);
        RectF shape = new RectF(box);
        shape.inset(-Math.max(0f, spread), -Math.max(0f, spread));
        shape.offset(0f, dy);
        shape.offset(-left, -top);
        mask.drawRoundRect(shape, Math.max(1f, rad), Math.max(1f, rad), paint);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] channel = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) channel[i] = pixels[i] >>> 24;
        boxBlur(channel, width, height, pass);
        boxBlur(channel, width, height, pass);
        boxBlur(channel, width, height, pass);
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        float limit = Math.min(radius, Math.min(box.width(), box.height()) / 2f);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float px = left + x + 0.5f;
                float py = top + y + 0.5f;
                boolean inside = containsRound(px, py, box, limit);
                int maskAlpha = channel[i];
                int fringe = inset ? (inside ? 255 - maskAlpha : 0) : (inside ? 0 : maskAlpha);
                int out = Math.round(fringe * alpha * edgeFade(px, fadeLeft, fadeRight, fadeSpan));
                if (out < 2) {
                    pixels[i] = 0;
                } else {
                    pixels[i] = (Math.min(255, out) << 24) | (red << 16) | (green << 8) | blue;
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        canvas.drawBitmap(bitmap, left, top, null);
        bitmap.recycle();
    }

    /** 0 at the screen edge, 1 once {@code span} inside it. */
    private static float edgeFade(float x, float left, float right, float span) {
        if (span <= 0f || right <= left) return 1f;
        float ramp = 1f;
        if (x < left + span) ramp = Math.min(ramp, (x - left) / span);
        if (x > right - span) ramp = Math.min(ramp, (right - x) / span);
        if (ramp <= 0f) return 0f;
        if (ramp >= 1f) return 1f;
        return ramp * ramp * (3f - 2f * ramp);
    }

    private static boolean containsRound(float x, float y, RectF box, float radius) {
        if (x < box.left || x > box.right || y < box.top || y > box.bottom) return false;
        float dx = 0f;
        float dy = 0f;
        if (x < box.left + radius) dx = box.left + radius - x;
        else if (x > box.right - radius) dx = x - (box.right - radius);
        if (y < box.top + radius) dy = box.top + radius - y;
        else if (y > box.bottom - radius) dy = y - (box.bottom - radius);
        return dx * dx + dy * dy <= radius * radius;
    }

    private static void boxBlur(int[] alpha, int width, int height, int radius) {
        if (radius < 1) return;
        int[] tmp = new int[alpha.length];
        int window = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sum = 0;
            for (int i = -radius; i <= radius; i++) sum += alpha[row + clamp(i, 0, width - 1)];
            for (int x = 0; x < width; x++) {
                tmp[row + x] = sum / window;
                sum += alpha[row + clamp(x + radius + 1, 0, width - 1)];
                sum -= alpha[row + clamp(x - radius, 0, width - 1)];
            }
        }
        for (int x = 0; x < width; x++) {
            int sum = 0;
            for (int i = -radius; i <= radius; i++) sum += tmp[clamp(i, 0, height - 1) * width + x];
            for (int y = 0; y < height; y++) {
                alpha[y * width + x] = sum / window;
                sum += tmp[clamp(y + radius + 1, 0, height - 1) * width + x];
                sum -= tmp[clamp(y - radius, 0, height - 1) * width + x];
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
