package android.graphics;
import java.io.OutputStream;
import java.io.IOException;
public final class YuvImage {
    public static boolean succeed = true;
    public static int encodedLength = 7;
    private final int width, height;
    public YuvImage(byte[] data, int format, int w, int h, int[] strides) {
        if (format != ImageFormat.NV21 || data.length < w*h*3/2) throw new AssertionError("NV21 boundary");
        width=w; height=h;
    }
    public boolean compressToJpeg(Rect rect, int quality, OutputStream out) {
        if (rect.left != 0 || rect.top != 0 || rect.right != width || rect.bottom != height)
            throw new AssertionError("Encode full preview");
        if (!succeed) return false;
        try { for (int i=0; i<encodedLength; i++) out.write(i); }
        catch (IOException e) { throw new AssertionError(e); }
        return true;
    }
}
