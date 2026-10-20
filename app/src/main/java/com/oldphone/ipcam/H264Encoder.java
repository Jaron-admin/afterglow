package com.oldphone.ipcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaCodec H.264 硬编码封装。
 *
 * 输入走字节缓冲区（而不是 Surface），因为 Camera1 无法直接把预览帧送到编码器的
 * 输入 Surface 上——那需要 EGL 中转。字节缓冲方式在 Android 4.1+ 全平台可用，
 * 代价是一次 NV21 色彩格式转换（在 640x480 下基本可以忽略）。
 *
 * 不同芯片（高通/联发科/Exynos）支持的输入色彩格式和可配置 profile 差异很大，
 * 真机上见过 configure 直接抛 CodecException(0x80001001) 的情况，
 * 所以 start() 会按「优先格式 → 其余受支持格式」逐个尝试，直到有一个能起来。
 */
public final class H264Encoder {

    private static final String TAG = "H264Encoder";
    private static final String MIME = "video/avc";

    public interface Listener {
        /** 编码出一帧 H.264（Annex-B，带起始码） */
        void onEncodedFrame(byte[] data, long ptsUs, boolean keyFrame);

        /** SPS / PPS 就绪（DESCRIBE 的 SDP 里要用） */
        void onCodecConfig(byte[] sps, byte[] pps);
    }

    private MediaCodec codec;
    private MediaCodec.BufferInfo bufferInfo;
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;

    private int width;
    private int height;
    private int colorFormat;
    private int targetLayout;
    private byte[] convBuf;

    private Listener listener;
    private volatile boolean started;

    private byte[] sps;
    private byte[] pps;

    public void start(int width, int height, int fps, int bitrate, Listener listener) throws IOException {
        this.width = width;
        this.height = height;
        this.listener = listener;
        this.bufferInfo = new MediaCodec.BufferInfo();

        List<MediaCodecInfo> encoders = listAvcEncoders();
        if (encoders.isEmpty()) {
            throw new IOException("no video/avc encoder on device");
        }

        IOException lastError = null;
        for (int e = 0; e < encoders.size(); e++) {
            MediaCodecInfo info = encoders.get(e);
            int[] supported;
            try {
                supported = info.getCapabilitiesForType(MIME).colorFormats;
            } catch (Throwable t) {
                continue;
            }
            List<Integer> formats = buildCandidates(supported);
            Log.i(TAG, "codec[" + e + "] " + info.getName()
                    + " hw=" + (!isSoftware(info)) + " formats=" + formats.size());

            for (int i = 0; i < formats.size(); i++) {
                int cf = formats.get(i).intValue();
                try {
                    configureInternal(info.getName(), width, height, fps, bitrate, cf);
                    started = true;
                    Log.i(TAG, "STARTED " + width + "x" + height + " @" + fps + "fps " + bitrate + "bps"
                            + " codec=" + info.getName()
                            + " colorFormat=0x" + Integer.toHexString(cf));
                    return;
                } catch (Throwable t) {
                    lastError = new IOException("configure failed [" + info.getName()
                            + " colorFormat=0x" + Integer.toHexString(cf) + "] : " + t);
                    Log.w(TAG, "configure failed [" + info.getName() + " 0x"
                            + Integer.toHexString(cf) + "] : " + t);
                    releaseCodec();
                }
            }
        }
        throw lastError != null ? lastError : new IOException("no usable encoder/color format");
    }

    /** 列出所有能编码 video/avc 的组件，硬件优先、软件兜底 */
    private static List<MediaCodecInfo> listAvcEncoders() {
        List<MediaCodecInfo> hw = new ArrayList<MediaCodecInfo>();
        List<MediaCodecInfo> sw = new ArrayList<MediaCodecInfo>();
        MediaCodecInfo[] codecInfos;
        if (Build.VERSION.SDK_INT >= 21) {
            codecInfos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } else {
            // KitKat has only the static discovery API (available since API 16).
            int count = MediaCodecList.getCodecCount();
            codecInfos = new MediaCodecInfo[count];
            for (int i = 0; i < count; i++) {
                codecInfos[i] = MediaCodecList.getCodecInfoAt(i);
            }
        }
        for (MediaCodecInfo info : codecInfos) {
            if (!info.isEncoder()) {
                continue;
            }
            boolean supports = false;
            String[] types = info.getSupportedTypes();
            for (String t : types) {
                if (MIME.equalsIgnoreCase(t)) {
                    supports = true;
                    break;
                }
            }
            if (!supports) {
                continue;
            }
            if (isSoftware(info)) {
                sw.add(info);
            } else {
                hw.add(info);
            }
        }
        List<MediaCodecInfo> all = new ArrayList<MediaCodecInfo>(hw.size() + sw.size());
        all.addAll(hw);
        all.addAll(sw);
        return all;
    }

    private static boolean isSoftware(MediaCodecInfo info) {
        String n = info.getName().toLowerCase();
        return n.startsWith("omx.google.") || n.startsWith("c2.android.")
                || n.startsWith("c2.google.") || n.contains(".sw.");
    }

    /**
     * 只保留 ROM 真正认识的输入色彩格式。
     * 高通/联发科的私有格式（0x7fa30c00..0x7fa30c0a 等）在部分 ROM 的
     * libstagefright 里会直接让 setupVideoEncoder 返回 -ENOSYS，
     * 所以在候选里把它们剔掉，避免白白触发一次崩溃式失败。
     */
    private static List<Integer> buildCandidates(int[] supported) {
        int[] preferredOrder = new int[]{
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,  // NV12
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,      // I420
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                0x7F420888,                                                     // Flexible
        };
        List<Integer> out = new ArrayList<Integer>();
        for (int p : preferredOrder) {
            for (int f : supported) {
                if (f == p && out.indexOf(Integer.valueOf(f)) < 0) {
                    out.add(Integer.valueOf(f));
                }
            }
        }
        return out;
    }

    private void configureInternal(String codecName, int width, int height, int fps, int bitrate,
                                   int colorFormat) throws IOException {
        codec = MediaCodec.createByCodecName(codecName);

        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        // 不设 KEY_PROFILE：部分 ROM 在 configure 阶段硬塞 profile 会直接返回 0x80001001，
        // 交给编码器自选默认值最稳（H.264 默认就是 baseline/main，HA 完全能解）。

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        this.colorFormat = colorFormat;
        this.targetLayout = layoutFor(colorFormat);

        if (Build.VERSION.SDK_INT < 21) {
            inputBuffers = codec.getInputBuffers();
            outputBuffers = codec.getOutputBuffers();
        }
        convBuf = new byte[width * height * 3 / 2];
    }

    public boolean isStarted() {
        return started;
    }

    public byte[] getSps() {
        return sps;
    }

    public byte[] getPps() {
        return pps;
    }

    public int colorFormat() {
        return colorFormat;
    }

    /** 送一帧 NV21 进去，非阻塞 */
    public void encode(byte[] nv21, long ptsUs) {
        if (!started) {
            return;
        }
        try {
            int inIndex = codec.dequeueInputBuffer(5000);
            if (inIndex >= 0) {
                ByteBuffer in = (Build.VERSION.SDK_INT >= 21)
                        ? codec.getInputBuffer(inIndex)
                        : inputBuffers[inIndex];
                if (in != null) {
                    in.clear();
                    Nv21Converter.convert(nv21, convBuf, width, height, targetLayout);
                    in.put(convBuf);
                    codec.queueInputBuffer(inIndex, 0, convBuf.length, ptsUs, 0);
                }
            }
            drain();
        } catch (IllegalStateException e) {
            Log.w(TAG, "encode: " + e);
        }
    }

    private void drain() {
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                readCsdFromFormat(codec.getOutputFormat());
                continue;
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (Build.VERSION.SDK_INT < 21) {
                    outputBuffers = codec.getOutputBuffers();
                }
                continue;
            }
            if (outIndex < 0) {
                continue;
            }

            ByteBuffer out = (Build.VERSION.SDK_INT >= 21)
                    ? codec.getOutputBuffer(outIndex)
                    : outputBuffers[outIndex];

            if (out != null && bufferInfo.size > 0) {
                byte[] data = new byte[bufferInfo.size];
                out.position(bufferInfo.offset);
                out.limit(bufferInfo.offset + bufferInfo.size);
                out.get(data, 0, bufferInfo.size);

                boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (config) {
                    parseParameterSets(data);
                } else if (listener != null) {
                    boolean key = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    listener.onEncodedFrame(data, bufferInfo.presentationTimeUs, key);
                }
            }
            codec.releaseOutputBuffer(outIndex, false);
        }
    }

    private void readCsdFromFormat(MediaFormat format) {
        try {
            ByteBuffer csd0 = format.getByteBuffer("csd-0");
            ByteBuffer csd1 = format.getByteBuffer("csd-1");
            if (csd0 != null) {
                sps = stripStartCode(toArray(csd0));
            }
            if (csd1 != null) {
                pps = stripStartCode(toArray(csd1));
            }
            notifyConfig();
        } catch (Throwable t) {
            Log.w(TAG, "readCsd: " + t);
        }
    }

    /** 从 CODEC_CONFIG 缓冲区里解析 SPS(7) / PPS(8) */
    private void parseParameterSets(byte[] data) {
        List<int[]> nals = splitAnnexB(data, data.length);
        for (int i = 0; i < nals.size(); i++) {
            int off = nals.get(i)[0];
            int len = nals.get(i)[1];
            if (len < 1) {
                continue;
            }
            int type = data[off] & 0x1F;
            if (type == 7 || type == 8) {
                byte[] nal = new byte[len];
                System.arraycopy(data, off, nal, 0, len);
                if (type == 7) {
                    sps = nal;
                } else {
                    pps = nal;
                }
            }
        }
        notifyConfig();
    }

    private void notifyConfig() {
        if (sps != null && pps != null && listener != null) {
            listener.onCodecConfig(sps, pps);
        }
    }

    private static byte[] toArray(ByteBuffer buf) {
        ByteBuffer dup = buf.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static byte[] stripStartCode(byte[] raw) {
        int start = 0;
        if (raw.length > 3 && raw[0] == 0 && raw[1] == 0 && raw[2] == 1) {
            start = 3;
        } else if (raw.length > 4 && raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 1) {
            start = 4;
        }
        if (start == 0) {
            return raw;
        }
        byte[] out = new byte[raw.length - start];
        System.arraycopy(raw, start, out, 0, out.length);
        return out;
    }

    /** 优先级：NV12 > NV21-packed(高通) > I420 > 其余 */
    private int preferredColorFormat(int[] supported) {
        for (int f : supported) {
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return f;
            }
        }
        for (int f : supported) {
            if (f == 0x7FA30C00) {
                return f;
            }
        }
        for (int f : supported) {
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return f;
            }
        }
        for (int f : supported) {
            if (f == 0x7F420888) {   // Flexible，按 NV12 尝试
                return f;
            }
        }
        for (int f : supported) {
            if (f != 0) {
                return f;
            }
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }

    private int layoutFor(int colorFormat) {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                || colorFormat == 0x7F420888) {
            return Nv21Converter.FMT_NV12;
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
                || colorFormat == 0x7FA30C00) {
            return Nv21Converter.FMT_NV21_PACKED;
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                || colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar) {
            return Nv21Converter.FMT_I420;
        }
        return Nv21Converter.FMT_NV12;
    }

    public void stop() {
        started = false;
        releaseCodec();
        inputBuffers = null;
        outputBuffers = null;
        Log.i(TAG, "stopped");
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
    }

    /** 按 Annex-B 起始码切分 NAL，供 RTSP 复用 */
    static List<int[]> splitAnnexB(byte[] buf, int len) {
        List<int[]> out = new ArrayList<int[]>(4);
        int i = 0;
        int nalStart = -1;
        while (i + 2 < len) {
            boolean sc3 = buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1;
            boolean sc4 = (i + 3 < len) && buf[i] == 0 && buf[i + 1] == 0
                    && buf[i + 2] == 0 && buf[i + 3] == 1;
            if (sc3 || sc4) {
                int scLen = sc4 ? 4 : 3;
                if (nalStart >= 0) {
                    int end = i;
                    if (end > nalStart) {
                        out.add(new int[]{nalStart, end - nalStart});
                    }
                }
                nalStart = i + scLen;
                i += scLen;
            } else {
                i++;
            }
        }
        if (nalStart >= 0 && nalStart < len) {
            out.add(new int[]{nalStart, len - nalStart});
        }
        if (out.isEmpty()) {
            out.add(new int[]{0, len});
        }
        return out;
    }
}
