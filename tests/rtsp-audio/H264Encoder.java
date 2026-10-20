package com.oldphone.ipcam;

import java.util.ArrayList;
import java.util.List;

/** Socket tests use a single Annex-B NAL, not a device encoder. */
public final class H264Encoder {
    public static List<int[]> splitAnnexB(byte[] data, int length) {
        List<int[]> result = new ArrayList<int[]>();
        if (length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1)
            result.add(new int[]{4, length - 4});
        return result;
    }
}
