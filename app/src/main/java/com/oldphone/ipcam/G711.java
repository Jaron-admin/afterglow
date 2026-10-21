package com.oldphone.ipcam;

/** G.711 A-law. WebRTC can play this without transcoding; AAC cannot. */
public final class G711 {
    private static final int[] SEG_END = {0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF};

    private G711() {}

    /** 16-bit linear PCM to A-law. Silence is 0xD5. */
    public static byte alaw(short sample) {
        int pcm = sample;
        int mask;
        if (pcm >= 0) mask = 0xD5;
        else {
            mask = 0x55;
            pcm = -pcm - 1;
            if (pcm < 0) pcm = 0x7FFF;
        }
        int segment = 8;
        for (int i = 0; i < SEG_END.length; i++) if (pcm <= SEG_END[i]) { segment = i; break; }
        if (segment >= 8) return (byte) (0x7F ^ mask);
        int encoded = segment << 4;
        if (segment < 2) encoded |= (pcm >> 4) & 0x0F;
        else encoded |= (pcm >> (segment + 3)) & 0x0F;
        return (byte) (encoded ^ mask);
    }
}
