package com.oldphone.ipcam;

import java.util.List;

/** Handshake/lifecycle tests never encode or distribute H.264 payloads. */
public final class H264Encoder {
    public static List<int[]> splitAnnexB(byte[] data, int length) {
        throw new UnsupportedOperationException("RTP payload encoding is outside these lifecycle tests");
    }
}
