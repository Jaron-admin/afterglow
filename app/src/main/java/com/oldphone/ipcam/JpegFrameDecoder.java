package com.oldphone.ipcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * JPEG 帧解码器：双缓冲 + inBitmap 复用。
 *
 * 640x480 的 RGB_565 位图约 600KB，8fps 不复用就是每秒 5MB 垃圾，
 * 老机器会被 GC 拖到掉帧；双缓冲是为了避免解码进「正在显示」的那张图。
 *
 * 只在单一后台线程调用，不需要加锁。
 */
public final class JpegFrameDecoder {

    private final Bitmap[] slots = new Bitmap[2];
    private int index;

    public Bitmap decode(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        int next = (index + 1) % slots.length;
        Bitmap target = slots[next];

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        opts.inMutable = true;
        if (target != null && !target.isRecycled()) {
            opts.inBitmap = target;
        }

        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        } catch (Throwable t) {
            // 尺寸/格式不匹配时 inBitmap 会抛异常，退回普通解码
            opts.inBitmap = null;
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        }
        if (bmp != null) {
            slots[next] = bmp;
            index = next;
        }
        return bmp;
    }
}
