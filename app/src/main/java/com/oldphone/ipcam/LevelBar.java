package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Thick orange track with a 22dp white thumb. Used by the sensitivity slider. */
final class LevelBar extends View {
    interface Listener {
        void onChanged(int value);
        void onFinished(int value);
    }

    int value = 60;
    private Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LevelBar(Context context) { super(context); }

    void setListener(Listener listener) { this.listener = listener; }

    void setValue(int next) {
        value = Math.max(0, Math.min(100, next));
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(28));
    }

    @Override protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float track = 8f * density;
        float cy = getHeight() / 2f;
        float thumb = 11f * density;
        float left = thumb;
        float right = Math.max(left + 1f, getWidth() - thumb);
        float x = left + (right - left) * (value / 100f);
        float rad = 4f * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getResources().getColor(R.color.surface_3));
        canvas.drawRoundRect(new RectF(0, cy - track / 2f, getWidth(), cy + track / 2f), rad, rad, paint);
        paint.setColor(getResources().getColor(R.color.primary));
        canvas.drawRoundRect(new RectF(0, cy - track / 2f, x, cy + track / 2f), rad, rad, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, cy, thumb, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * density);
        paint.setColor(getResources().getColor(R.color.primary));
        canvas.drawCircle(x, cy, thumb - 1.5f * density, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float density = getResources().getDisplayMetrics().density;
        float thumb = 11f * density;
        float span = Math.max(1f, getWidth() - thumb * 2f);
        int next = Math.round(100f * (event.getX() - thumb) / span);
        next = Math.max(0, Math.min(100, next));
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            value = next;
            invalidate();
            if (listener != null) listener.onChanged(next);
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            value = next;
            invalidate();
            if (listener != null) listener.onFinished(next);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private int dp(int amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }
}
