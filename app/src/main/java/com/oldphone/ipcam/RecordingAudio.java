package com.oldphone.ipcam;

/**
 * One main-screen switch owns the live microphone, sound trigger and file audio.
 * {@code recording_audio} is still read so an older install that turned file audio off
 * does not start saving sound just because the app was upgraded.
 */
public final class RecordingAudio {
    private RecordingAudio() {}

    /** Missing legacy value is treated as on, which was the old default. */
    public static boolean fileAudio(boolean microphoneOn, boolean recordAudioPref) {
        return microphoneOn && recordAudioPref;
    }

    /** The sound-trigger row can be used only while 「录制声音」 is on. The saved flag stays. */
    public static boolean soundControlsEnabled(boolean microphoneOn) {
        return microphoneOn;
    }

    public static boolean soundSensitivityEnabled(boolean microphoneOn, boolean soundChecked) {
        return microphoneOn && soundChecked;
    }

    /** Empty when the controls are usable. Shown under the disabled sound trigger. */
    public static String soundDisabledNote(boolean microphoneOn) {
        if (microphoneOn) return "";
        return "请先打开主界面的「录制声音」，才能使用声音触发。";
    }

    /**
     * Call this only after the user moves 「录制声音」.
     * Opening the screen or the settings page must not, so a legacy opt-out survives.
     */
    public static boolean recordAudioAfterUserToggle(boolean microphoneOn) {
        return microphoneOn;
    }
}
