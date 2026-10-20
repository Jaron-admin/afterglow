package android.media;
import android.os.Build;
/** Models discovery only; encoding is never replaced or invoked by these tests. */
public final class MediaCodecList {
    public static final int REGULAR_CODECS = 0;
    private static final MediaCodecInfo[] CODECS = {
        new MediaCodecInfo("omx.google.h264.encoder", true, "video/avc"),
        new MediaCodecInfo("vendor.h264.decoder", false, "video/avc"),
        new MediaCodecInfo("vendor.vp8.encoder", true, "video/x-vnd.on2.vp8"),
        new MediaCodecInfo("vendor.h264.encoder", true, "VIDEO/AVC")
    };
    public MediaCodecList(int kind) {
        if (Build.VERSION.SDK_INT < 21) throw new NoSuchMethodError("MediaCodecList(int) requires API 21");
    }
    public MediaCodecInfo[] getCodecInfos() { return CODECS; }
    public static int getCodecCount() { return CODECS.length; }
    public static MediaCodecInfo getCodecInfoAt(int index) { return CODECS[index]; }
}
