package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Full oriented camera frame, with the exact stream crop supplied by that frame. */
public final class CropPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final RectF crop = new RectF();
    private Bitmap bitmap;
    private FrameGeometry geometry;

    public CropPreviewView(Context context) { this(context, null); }
    public CropPreviewView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setFrame(Bitmap frame, FrameGeometry frameGeometry) {
        bitmap = frame;
        geometry = frameGeometry;
        invalidate();
    }

    public void clearFrame() {
        bitmap = null;
        geometry = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap image = bitmap;
        FrameGeometry frame = geometry;
        if (image == null || image.isRecycled() || frame == null
                || frame.fullWidth <= 0 || frame.fullHeight <= 0) return;
        float scale = Math.min((float) getWidth() / frame.fullWidth,
                (float) getHeight() / frame.fullHeight);
        float width = frame.fullWidth * scale;
        float height = frame.fullHeight * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        destination.set(left, top, left + width, top + height);
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(image, null, destination, paint);
        crop.set(left + frame.cropLeft * scale, top + frame.cropTop * scale,
                left + (frame.cropLeft + frame.cropWidth) * scale,
                top + (frame.cropTop + frame.cropHeight) * scale);
        paint.setColor(0x99060a10);
        canvas.drawRect(destination.left, destination.top, destination.right, crop.top, paint);
        canvas.drawRect(destination.left, crop.bottom, destination.right, destination.bottom, paint);
        canvas.drawRect(destination.left, crop.top, crop.left, crop.bottom, paint);
        canvas.drawRect(crop.right, crop.top, destination.right, crop.bottom, paint);
    }
}
