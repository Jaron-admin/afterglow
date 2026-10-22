package com.oldphone.ipcam;

public final class LightPolicyTest {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(LightPolicy.satisfied(30, 30), "threshold is inclusive");
        check(!LightPolicy.satisfied(29, 30), "dimmer than the threshold is not enough");
        check(LightPolicy.satisfied(-1, 30), "an unknown reading does not count as dim");
        check(LightPolicy.clampPercent(140) == 100 && LightPolicy.clampPercent(-4) == 0, "percent clamp");

        check(LightPolicy.pauseRecording(true, 12, 30), "record light pauses a dim frame");
        check(!LightPolicy.pauseRecording(false, 12, 30), "record light off leaves recording alone");
        check(!LightPolicy.pauseRecording(true, 42, 30), "bright enough resumes recording");
        check(!LightPolicy.pauseRecording(true, -1, 30), "unknown brightness does not pause recording");

        check(!LightPolicy.pauseStream(LightPolicy.FRAME, true, 12, 30),
                "frame mode never pauses the stream");
        check(!LightPolicy.cameraMaySleep(LightPolicy.FRAME, true, 12, 30),
                "frame mode keeps the camera");
        check(LightPolicy.pauseStream(LightPolicy.SENSOR, true, 12, 30),
                "sensor mode pauses the stream when that switch is on");
        check(LightPolicy.cameraMaySleep(LightPolicy.SENSOR, true, 12, 30),
                "sensor pause may sleep the camera");
        check(!LightPolicy.pauseStream(LightPolicy.SENSOR, false, 12, 30),
                "sensor mode keeps streaming when 光感控制推流 is off");
        check(!LightPolicy.pauseStream(LightPolicy.SENSOR, true, 30, 30),
                "sensor stream resumes at the threshold");
        check(LightPolicy.FRAME.equals(LightPolicy.method(null)), "default method is frame");
        check(LightPolicy.SENSOR.equals(LightPolicy.method("sensor")), "sensor method");

        byte[] nv21 = new byte[16];
        for (int i = 0; i < 16; i++) nv21[i] = (byte) 255;
        check(LightPolicy.framePercent(nv21, 4, 4) == 100, "white frame is 100%");
        check(LightPolicy.framePercent(new byte[16], 4, 4) == 0, "black frame is 0%");
        check(LightPolicy.framePercent(null, 4, 4) == -1, "missing frame is unknown");

        check(LightPolicy.luxToPercent(0f) == 0, "dark sensor");
        check(LightPolicy.luxToPercent(10000f) == 100, "daylight maps to 100%");
        int indoor = LightPolicy.luxToPercent(100f);
        check(indoor > 30 && indoor < 80, "indoor lux sits on the slider, got " + indoor);
    }
}
