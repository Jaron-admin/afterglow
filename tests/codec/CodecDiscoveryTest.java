package com.oldphone.ipcam;
import android.media.MediaCodecInfo;
import android.os.Build;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
/** Executes production codec discovery while modeling only Android's discovery boundary. */
public final class CodecDiscoveryTest {
    public static void main(String[] args) throws Exception {
        Method discover = H264Encoder.class.getDeclaredMethod("listAvcEncoders");
        discover.setAccessible(true);
        final List<?> encoders;
        try { encoders = (List<?>) discover.invoke(null); }
        catch (InvocationTargetException failure) {
            throw new AssertionError("AVC discovery must work on API " + Build.VERSION.SDK_INT,
                    failure.getCause());
        }
        check(encoders.size() == 2, "discovery must omit decoders and other video formats");
        check(((MediaCodecInfo) encoders.get(0)).getName().equals("vendor.h264.encoder"),
                "hardware AVC encoder must be first");
        check(((MediaCodecInfo) encoders.get(1)).getName().equals("omx.google.h264.encoder"),
                "software AVC encoder must remain available as fallback");
        System.out.println("PASS CodecDiscoveryTest API " + Build.VERSION.SDK_INT);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
