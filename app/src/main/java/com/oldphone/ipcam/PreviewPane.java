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
 *
 * <p>The whole sensor frame is drawn, fitted inside the view; the band the encoder throws
 * away for the selected stream size is covered by a scrim, so the picture still reads as
 * "this is what the camera sees, and this is what leaves the phone".
 */
final class PreviewPane extends View {
    /** Same scrim the legacy preview used, so the two screens agree on the tone. */
    private static final int SCRIM = 0x99060a10;

    private Bitmap frame;
    private boolean maskSet;
    private float maskLeft, maskTop, maskRight, maskBottom;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final RectF target = new RectF();
    private final RectF crop = new RectF();

    PreviewPane(Context context) {
        super(context);
        paint.setAntiAlias(true);
        scrim.setColor(SCRIM);
        scrim.setStyle(Paint.Style.FILL);
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

    /**
     * Marks the part of the frame that actually leaves the encoder, in full-frame pixels.
     * A geometry whose crop already covers the whole frame clears the scrim instead of
     * drawing an empty one.
     */
    void setMask(FrameGeometry geometry) {
        if (geometry == null || geometry.fullWidth <= 0 || geometry.fullHeight <= 0) {
            clearMask();
            return;
        }
        float width = geometry.fullWidth;
        float height = geometry.fullHeight;
        setMask(geometry.cropLeft / width, geometry.cropTop / height,
                (geometry.cropLeft + geometry.cropWidth) / width,
                (geometry.cropTop + geometry.cropHeight) / height);
    }

    private void setMask(float left, float top, float right, float bottom) {
        if (left <= 0f && top <= 0f && right >= 1f && bottom >= 1f) {
            clearMask();
            return;
        }
        if (maskSet && left == maskLeft && top == maskTop && right == maskRight
                && bottom == maskBottom) return;
        maskSet = true;
        maskLeft = left;
        maskTop = top;
        maskRight = right;
        maskBottom = bottom;
        invalidate();
    }

    private void clearMask() {
        if (!maskSet) return;
        maskSet = false;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        Bitmap image = frame;
        if (image == null || image.isRecycled()) return;
        int width = getWidth();
        int height = getHeight();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return;
        // Fit, not crop: the full sensor view stays visible even when the card is a
        // different shape than the frame.
        float scale = Math.min((float) width / imageWidth, (float) height / imageHeight);
        float drawnWidth = imageWidth * scale;
        float drawnHeight = imageHeight * scale;
        float left = (width - drawnWidth) / 2f;
        float top = (height - drawnHeight) / 2f;
        source.set(0, 0, imageWidth, imageHeight);
        target.set(left, top, left + drawnWidth, top + drawnHeight);
        canvas.drawBitmap(image, source, target, paint);
        if (!maskSet) return;
        crop.set(target.left + drawnWidth * maskLeft, target.top + drawnHeight * maskTop,
                target.left + drawnWidth * maskRight, target.top + drawnHeight * maskBottom);
        canvas.drawRect(target.left, target.top, target.right, crop.top, scrim);
        canvas.drawRect(target.left, crop.bottom, target.right, target.bottom, scrim);
        canvas.drawRect(target.left, crop.top, crop.left, crop.bottom, scrim);
        canvas.drawRect(crop.right, crop.top, target.right, crop.bottom, scrim);
    }
}
