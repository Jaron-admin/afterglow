package com.oldphone.ipcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * Camera preview surface.
 *
 * <p>An {@code ImageView} is the wrong tool for a live frame stream: every
 * {@code setImageBitmap} allocates a fresh {@code BitmapDrawable} and calls
 * {@code requestLayout()}, so the whole screen re-measures once per frame. This view keeps
 * one bitmap and only invalidates, the way {@link CropPreviewView} already did for the
 * legacy screen.
 */
final class PreviewPane extends View {
    private Bitmap frame;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF target = new RectF();

    PreviewPane(Context context) {
        super(context);
        paint.setAntiAlias(true);
    }

    void setFrame(Bitmap bitmap) {
        if (frame == bitmap) return;
        frame = bitmap;
        invalidate();
    }

    void clearFrame() {
        if (frame == null) return;
        frame = null;
        invalidate();
    }

    Bitmap frame() { return frame; }

    @Override protected void onDraw(Canvas canvas) {
        Bitmap image = frame;
        if (image == null || image.isRecycled()) return;
        int width = getWidth();
        int height = getHeight();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return;
        // CENTER_CROP: fill the view, crop the overflow evenly on both axes.
        float scale = Math.max((float) width / imageWidth, (float) height / imageHeight);
        float drawnWidth = imageWidth * scale;
        float drawnHeight = imageHeight * scale;
        float left = (width - drawnWidth) / 2f;
        float top = (height - drawnHeight) / 2f;
        source.set(0, 0, imageWidth, imageHeight);
        target.set(left, top, left + drawnWidth, top + drawnHeight);
        canvas.drawBitmap(image, source, target, paint);
    }
}
