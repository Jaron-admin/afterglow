package com.oldphone.ipcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;

/** Small scene placeholders for recording rows. Not a flat color or a stripe. */
final class SceneThumb {
    private SceneThumb() {}

    static Bitmap bitmap(String kind, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean door = "door".equals(kind);
        boolean balcony = "balcony".equals(kind);
        paint.setColor(door ? 0xFF6B6F73 : balcony ? 0xFF7E8C8E : 0xFF7A6656);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setColor(door ? 0xFF45484C : balcony ? 0xFF4C5759 : 0xFF4E4038);
        canvas.drawRect(0, height * 0.55f, width, height, paint);
        if (door) {
            paint.setColor(0xFFF5E6CF);
            canvas.drawRoundRect(new RectF(width * 0.12f, height * 0.16f, width * 0.38f, height * 0.42f), 4, 4, paint);
            paint.setColor(0xFF8A5A44);
            canvas.drawRect(width * 0.58f, height * 0.12f, width * 0.86f, height * 0.72f, paint);
            paint.setColor(0xFFD9B37A);
            canvas.drawCircle(width * 0.78f, height * 0.42f, width * 0.025f, paint);
        } else {
            paint.setColor(0xFFFFE3B8);
            float winLeft = width * 0.08f;
            float winTop = height * 0.12f;
            float winRight = width * 0.42f;
            float winBottom = height * 0.48f;
            canvas.drawRoundRect(new RectF(winLeft, winTop, winRight, winBottom), 6, 6, paint);
            paint.setColor(balcony ? 0xFF4C5759 : 0xFF4E4038);
            paint.setStrokeWidth(Math.max(2f, width * 0.012f));
            paint.setStyle(Paint.Style.STROKE);
            float midX = (winLeft + winRight) / 2f;
            float midY = (winTop + winBottom) / 2f;
            canvas.drawLine(midX, winTop, midX, winBottom, paint);
            canvas.drawLine(winLeft, midY, winRight, midY, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(balcony ? 0xFF6E7A5A : 0xFF5C6B72);
            canvas.drawRoundRect(new RectF(width * 0.48f, height * 0.52f, width * 0.92f, height * 0.78f),
                    width * 0.04f, width * 0.04f, paint);
            paint.setColor(0xFFC98A5A);
            canvas.drawRoundRect(new RectF(width * 0.54f, height * 0.46f, width * 0.7f, height * 0.6f),
                    width * 0.03f, width * 0.03f, paint);
            if (!balcony) {
                paint.setColor(0xFF4E7350);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.02f);
                canvas.drawLine(width * 0.08f, height * 0.78f, width * 0.14f, height * 0.5f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawRect(0, height * 0.86f, width, height, paint);
        return bitmap;
    }

    static void write(File dest, String kind) throws Exception {
        Bitmap bitmap = bitmap(kind, 312, 192);
        FileOutputStream output = new FileOutputStream(dest);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                throw new IllegalStateException("thumbnail");
            }
        } finally {
            output.close();
            bitmap.recycle();
        }
    }
}
