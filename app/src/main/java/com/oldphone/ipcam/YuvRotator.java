package com.oldphone.ipcam;

/**
 * NV21 帧的顺时针旋转。
 *
 * 为什么需要它：Camera1 的 onPreviewFrame 拿到的是「传感器原生方向」的帧，
 * 不受 setDisplayOrientation 影响。真机上常见后置 orientation=90、前置=270，
 * 也就是同样把手机摆正，前置出来的画面相对后置会倒 180°。
 * 监控场景里手机通常固定安装，让 App 直接转好比让播放端各自处理更省心。
 *
 * 输入布局：Y 平面 w*h，随后 VU 交错（V 在前）。
 * 旋转 90/270 时输出宽高互换，输出缓冲长度仍为 w*h*3/2。
 */
public final class YuvRotator {

    private YuvRotator() {
    }

    /** 旋转后输出宽度 */
    public static int outWidth(int w, int h, int degrees) {
        return (degrees == 90 || degrees == 270) ? h : w;
    }

    /** 旋转后输出高度 */
    public static int outHeight(int w, int h, int degrees) {
        return (degrees == 90 || degrees == 270) ? w : h;
    }

    /**
     * @param in      NV21 输入，长度 >= w*h*3/2
     * @param out     NV21 输出，长度 >= w*h*3/2
     * @param degrees 0 / 90 / 180 / 270（顺时针）
     */
    public static void rotate(byte[] in, byte[] out, int w, int h, int degrees) {
        if (degrees == 0) {
            if (in != out) {
                System.arraycopy(in, 0, out, 0, w * h * 3 / 2);
            }
            return;
        }
        if (degrees == 180) {
            rotate180(in, out, w, h);
        } else if (degrees == 90) {
            rotate90(in, out, w, h, true);
        } else if (degrees == 270) {
            rotate90(in, out, w, h, false);
        } else {
            if (in != out) {
                System.arraycopy(in, 0, out, 0, w * h * 3 / 2);
            }
        }
    }

    private static void rotate180(byte[] in, byte[] out, int w, int h) {
        final int ySize = w * h;
        // Y 平面整体前后对调
        for (int i = 0, j = ySize - 1; i < ySize; i++, j--) {
            out[i] = in[j];
        }
        // 色度按 VU 对整体倒序，但每一对内部顺序不能变
        final int uvSize = ySize / 2;
        for (int p = 0, q = uvSize - 2; p < uvSize; p += 2, q -= 2) {
            out[ySize + p] = in[ySize + q];
            out[ySize + p + 1] = in[ySize + q + 1];
        }
    }

    /**
     * 90/270 度旋转。顺时针 90 度时 目标(x,y) = 源(y, srcH-1-x)，
     * 所以目标尺寸是 h x w。
     */
    private static void rotate90(byte[] in, byte[] out, int w, int h, boolean clockwise) {
        final int outW = h;
        final int outH = w;

        // ---- Y 平面 ----
        for (int dy = 0; dy < outH; dy++) {
            int dstRow = dy * outW;
            for (int dx = 0; dx < outW; dx++) {
                int sx;
                int sy;
                if (clockwise) {
                    sx = dy;
                    sy = h - 1 - dx;
                } else {
                    sx = w - 1 - dy;
                    sy = dx;
                }
                out[dstRow + dx] = in[sy * w + sx];
            }
        }

        // ---- 色度平面（宽高各一半，VU 交错）----
        final int cw = w / 2;
        final int ch = h / 2;
        final int outCw = ch;
        final int outCh = cw;
        final int uvOff = w * h;

        for (int dy = 0; dy < outCh; dy++) {
            int dstRow = uvOff + dy * outCw * 2;
            for (int dx = 0; dx < outCw; dx++) {
                int sx;
                int sy;
                if (clockwise) {
                    sx = dy;
                    sy = ch - 1 - dx;
                } else {
                    sx = cw - 1 - dy;
                    sy = dx;
                }
                int src = uvOff + (sy * cw + sx) * 2;
                out[dstRow + dx * 2] = in[src];
                out[dstRow + dx * 2 + 1] = in[src + 1];
            }
        }
    }
}
