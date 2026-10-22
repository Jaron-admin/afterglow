package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shared brightness slider. The track is the handoff gradient, the thumb is
 * the threshold, and the cyan bar is the current reading.
 */
final class BrightnessBar extends View {
    interface Listener {
        void onChanged(int threshold);
        void onFinished(int threshold);
    }

    int threshold = 30;
    int reading = -1;
    private Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    BrightnessBar(Context context) { super(context); }

    void setListener(Listener listener) { this.listener = listener; }

    void setThreshold(int value) {
        threshold = Math.max(0, Math.min(100, value));
        invalidate();
    }

    void setReading(int value) {
        reading = value;
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, dp(28));
    }

    @Override protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float track = 8f * density;
        float cy = getHeight() / 2f;
        float thumb = 11f * density;
        float left = thumb;
        float right = getWidth() - thumb;
        RectF bar = new RectF(0, cy - track / 2f, getWidth(), cy + track / 2f);
        paint.setShader(new LinearGradient(0, 0, getWidth(), 0,
                new int[]{0xFF2A2622, 0xFF7D6E5E, 0xFFFFE6C0},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(bar, 4f * density, 4f * density, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(getResources().getColor(R.color.outline));
        canvas.drawRoundRect(bar, 4f * density, 4f * density, paint);
        if (reading >= 0 && right > left) {
            float x = left + (right - left) * (reading / 100f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getResources().getColor(R.color.surface));
            canvas.drawRoundRect(new RectF(x - 3.5f * density, cy - 12f * density,
                    x + 3.5f * density, cy + 12f * density), 2f * density, 2f * density, paint);
            paint.setColor(getResources().getColor(R.color.secondary));
            canvas.drawRoundRect(new RectF(x - 1.5f * density, cy - 10f * density,
                    x + 1.5f * density, cy + 10f * density), 1.5f * density, 1.5f * density, paint);
        }
        float x = left + (right - left) * (threshold / 100f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, cy, thumb, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * density);
        paint.setColor(getResources().getColor(R.color.amber));
        canvas.drawCircle(x, cy, thumb - 1.5f * density, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float density = getResources().getDisplayMetrics().density;
        float thumb = 11f * density;
        float span = Math.max(1f, getWidth() - thumb * 2f);
        int value = Math.round(100f * (event.getX() - thumb) / span);
        value = Math.max(0, Math.min(100, value));
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            threshold = value;
            invalidate();
            if (listener != null) listener.onChanged(value);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            threshold = value;
            invalidate();
            if (listener != null) listener.onFinished(value);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
