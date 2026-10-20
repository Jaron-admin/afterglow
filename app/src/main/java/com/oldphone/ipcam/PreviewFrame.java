package com.oldphone.ipcam;

/** One complete upright JPEG and the exact crop/output plan used for that capture. */
public final class PreviewFrame {
    public final byte[] jpeg;
    public final FrameGeometry geometry;
    public final long generation;

    public PreviewFrame(byte[] jpeg, FrameGeometry geometry, long generation) {
        this.jpeg = jpeg;
        this.geometry = geometry;
        this.generation = generation;
    }
}
