package com.oldphone.ipcam;

/**
 * 摄像头预览回调给出的是 NV21（Y 平面 + VU 交错）。
 * 不同芯片的 H.264 编码器要求不同的输入色彩格式，这里做最小代价的转换。
 *
 * 之所以不用 COLOR_FormatYUV420Flexible + Image 接口：那需要 API 21+，
 * 而本项目要兼容 Android 4.4，所以统一走字节缓冲区输入。
 */
public final class Nv21Converter {

    /** 与 NV21 内存布局完全一致（高通 PackedSemiPlanar），可以 0 转换直接拷贝 */
    public static final int FMT_NV21_PACKED = 0;
    /** NV12：Y 平面 + UV 交错 */
    public static final int FMT_NV12 = 1;
    /** I420：Y 平面 + U 平面 + V 平面 */
    public static final int FMT_I420 = 2;

    private Nv21Converter() {
    }

    /**
     * @param nv21   输入帧
     * @param out    输出缓冲，长度必须 >= w*h*3/2
     * @param target 目标布局
     */
    public static void convert(byte[] nv21, byte[] out, int w, int h, int target) {
        final int ySize = w * h;
        final int uvSize = ySize / 2;

        System.arraycopy(nv21, 0, out, 0, ySize);

        if (target == FMT_NV21_PACKED) {
            System.arraycopy(nv21, ySize, out, ySize, uvSize);
            return;
        }

        if (target == FMT_NV12) {
            // NV21: V U V U ...  ->  NV12: U V U V ...
            for (int i = 0; i < uvSize; i += 2) {
                out[ySize + i] = nv21[ySize + i + 1];
                out[ySize + i + 1] = nv21[ySize + i];
            }
            return;
        }

        // I420
        int uOff = ySize;
        int vOff = ySize + uvSize / 2;
        for (int i = 0; i < uvSize; i += 2) {
            out[uOff++] = nv21[ySize + i + 1];
            out[vOff++] = nv21[ySize + i];
        }
    }
}
