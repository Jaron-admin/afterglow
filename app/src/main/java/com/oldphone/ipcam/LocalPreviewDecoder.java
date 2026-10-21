package com.oldphone.ipcam;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.io.ByteArrayOutputStream;

/** Codec work belongs to the preview worker, never the capture callback or UI thread. */
final class LocalPreviewDecoder {
    private final JpegBuffer jpeg = new JpegBuffer();
    private final JpegFrameDecoder decoder = new JpegFrameDecoder();
    private volatile Bitmap displayed;

    Bitmap decode(PreviewFrame frame) {
        jpeg.reset();
        YuvImage image = new YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null);
        if (!image.compressToJpeg(new Rect(0, 0, frame.width, frame.height), 75, jpeg)) return null;
        return decoder.decode(jpeg.bytes(), jpeg.size(), displayed);
    }

    /** Called before releasing the single-frame gate after a successful UI handoff. */
    void presented(Bitmap bitmap) { displayed = bitmap; }

    private static final class JpegBuffer extends ByteArrayOutputStream {
        JpegBuffer() { super(128 * 1024); }
        byte[] bytes() { return buf; }
    }
}
