package android.graphics;
public final class BitmapFactory {
    public static byte[] lastBytes;
    public static int lastLength;
    public static final class Options {
        public Bitmap.Config inPreferredConfig;
        public boolean inMutable;
        public Bitmap inBitmap;
    }
    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        lastBytes = data; lastLength = length;
        return options.inBitmap == null ? new Bitmap() : options.inBitmap;
    }
}
