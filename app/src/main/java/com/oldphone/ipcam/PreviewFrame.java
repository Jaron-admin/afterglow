package com.oldphone.ipcam;

/** Owned, scaled upright NV21 plus the original full-view crop/output plan. */
public final class PreviewFrame {
    public final byte[] nv21;
    public final int width, height;
    public final FrameGeometry geometry;
    public final long generation;

    /** The caller retains exclusive buffer ownership until decode/UI handoff finishes. */
    public PreviewFrame(byte[] nv21, int width, int height, FrameGeometry geometry, long generation) {
        this.nv21 = nv21;
        this.width = width;
        this.height = height;
        this.geometry = geometry;
        this.generation = generation;
    }
}
