package com.oldphone.ipcam;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.YuvImage;

/** Android codecs are replaced only at their byte/bitmap boundary; preview ownership is production code. */
public final class LocalPreviewTest {
    public static void main(String[] args) {
        JpegFrameDecoder decoder = new JpegFrameDecoder();
        byte[] capacity = new byte[200];
        decoder.decode(capacity, 7);
        check(BitmapFactory.lastBytes == capacity && BitmapFactory.lastLength == 7, "decode only populated prefix");
        check(decoder.decode(capacity, 0) == null, "empty prefix has no bitmap");
        check(decoder.decode(capacity, 201) == null, "oversized prefix rejected before codec");
        decoder.decode(capacity);
        check(BitmapFactory.lastLength == 200, "remote whole-array decode retained");

        LocalPreviewDecoder local = new LocalPreviewDecoder();
        FrameGeometry geometry = FrameGeometry.create(8, 4, 0, 4, 4, 9);
        PreviewFrame frame = new PreviewFrame(new byte[48], 8, 4, geometry, 12L);
        Bitmap shown = local.decode(frame);
        byte[] firstBuffer = BitmapFactory.lastBytes;
        check(BitmapFactory.lastLength == 7, "first encoded prefix");
        local.presented(shown);
        YuvImage.encodedLength = 3;
        Bitmap discarded = local.decode(frame);
        check(discarded != shown, "decode never targets displayed bitmap");
        Bitmap next = local.decode(frame);
        check(next != shown, "discarded frames cannot advance into displayed bitmap");
        check(BitmapFactory.lastBytes == firstBuffer && BitmapFactory.lastLength == 3,
                "reuse encoded capacity and reset length between frames");
        check(frame.geometry == geometry && frame.generation == 12, "preserve original crop/session metadata");
        YuvImage.succeed = false;
        check(local.decode(frame) == null, "failed compression must not decode prior bytes");
        System.out.println("PASS local preview byte-prefix, buffer reuse, displayed ownership and compression failure");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
