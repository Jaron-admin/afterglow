package com.oldphone.ipcam;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：找出同网段里其它正在推流的「旧手机监控」实例。
 *
 * 判定方式刻意做成「协议指纹」而不是 UDP 广播：
 *   - 优先访问 HTTP 8080 的公共 /api/info，以设备身份和能力判断观看方式；
 *   - 旧版本回退 /status，返回体里必须同时出现 "has_frame" 与 "running"；
 *   - 打不通再试 RTSP 8554 的 OPTIONS，看 Server 头是不是 "OldPhoneCam"。
 * 这种扫描不依赖组播，代价是要逐个 IP 试；Home Assistant 另用 DNS-SD 自动发现。
 *
 * 扫描范围固定为「本机 IP 所在的 /24」。家用网络基本都是 /24，
 * 扫 /16 是 65534 个地址，手机上不现实。
 */
public final class LanScanner {

    private static final String TAG = "LanScanner";

    private static final int HTTP_PORT = 8080;
    private static final int RTSP_PORT = 8554;
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int READ_TIMEOUT_MS = 800;
    private static final int THREADS = 32;

    /** 扫描结果 */
    public static final class Device {
        public String ip;
        public String deviceId = "";
        public String name = "";
        public int httpPort = HTTP_PORT;
        public int rtspPort = RTSP_PORT;
        public boolean httpAuthRequired;
        /** 仅可通过 RTSP 观看（HTTP 身份/录像服务可以仍然开启） */
        public boolean rtspOnly;
        public boolean running;
        public String camera = "";
        public int width;
        public int height;

        public String detail() {
            if (rtspOnly) {
                return (name.length() > 0 ? name + " · " : "")
                        + "仅 RTSP · rtsp://" + ip + ":" + rtspPort + "/live";
            }
            StringBuilder sb = new StringBuilder();
            if (name.length() > 0) sb.append(name);
            if (httpAuthRequired) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("HTTP 访问需验证");
            }
            if (camera != null && camera.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(camera);
            }
            if (width > 0 && height > 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(width).append('x').append(height);
            }
            if (sb.length() == 0) {
                sb.append("正在推流");
            }
            return sb.toString();
        }
    }

    public interface Callback {
        /** 每发现一台就回调一次（主线程） */
        void onFound(Device device);

        /** 扫完（主线程），scanned 是尝试过的地址数 */
        void onFinished(int scanned);
    }

    private LanScanner() {
    }

    public static void scan(final Callback callback) {
        final String self = NetUtils.localIp();
        final String prefix = subnetPrefix(self);
        final Handler ui = new Handler(Looper.getMainLooper());
        final AtomicInteger remaining = new AtomicInteger(254);
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        Log.i(TAG, "scan " + prefix + ".0/24, self=" + self);

        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + "." + i;
            if (ip.equals(self)) {
                finishOne(ui, callback, remaining);
                continue;
            }
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Device d = probeHttp(ip, HTTP_PORT);
                        if (d == null) {
                            d = probeRtsp(ip);
                        }
                        if (d != null) {
                            final Device found = d;
                            Log.i(TAG, "found " + found.ip + " rtspOnly=" + found.rtspOnly);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onFound(found);
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        finishOne(ui, callback, remaining);
                    }
                }
            });
        }
        pool.shutdown();
    }

    private static void finishOne(Handler ui, final Callback cb, AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.onFinished(254);
                }
            });
        }
    }

    /** 把 "192.168.1.50" 取成 "192.168.1" */
    private static String subnetPrefix(String ip) {
        if (ip == null) {
            return "127.0.0";
        }
        int idx = ip.lastIndexOf('.');
        return idx > 0 ? ip.substring(0, idx) : ip;
    }

    // ------------------------------------------------------------------

    static Device probeHttp(String ip, int port) {
        String info = requestHttp(ip, port, "/api/info");
        if (info != null && jsonInt(info, "api_version") == 1) {
            String id = jsonString(info, "device_id");
            Boolean rtsp = jsonBool(info, "rtsp_enabled");
            Boolean mjpeg = jsonBool(info, "mjpeg_enabled");
            int httpPort = jsonInt(info, "http_port"), rtspPort = jsonInt(info, "rtsp_port");
            boolean validId = false;
            try { validId = UUID.fromString(id).toString().equals(id); }
            catch (IllegalArgumentException ignored) { }
            if (validId && rtsp != null && mjpeg != null && validPort(httpPort) && validPort(rtspPort)) {
                if (!rtsp && !mjpeg) return null;
                Device device = new Device();
                device.ip = ip;
                device.deviceId = id;
                device.name = jsonString(info, "name");
                device.httpPort = httpPort;
                device.rtspPort = rtspPort;
                device.httpAuthRequired = Boolean.TRUE.equals(jsonBool(info, "http_auth_required"));
                // The built-in viewer has no HTTP login UI; preserve the legacy RTSP fallback.
                device.rtspOnly = rtsp && (!mjpeg || device.httpAuthRequired);
                device.running = true;
                return device;
            }
        }
        String status = requestHttp(ip, port, "/status");
        if (status == null || jsonBool(status, "has_frame") == null || jsonBool(status, "running") == null)
            return null;
        Boolean rtsp = jsonBool(status, "rtsp_enabled"), mjpeg = jsonBool(status, "mjpeg_enabled");
        if (Boolean.FALSE.equals(mjpeg) && !Boolean.TRUE.equals(rtsp)) return null;
        Device device = new Device();
        device.ip = ip;
        device.httpPort = port;
        device.rtspOnly = Boolean.FALSE.equals(mjpeg) && Boolean.TRUE.equals(rtsp);
        device.running = Boolean.TRUE.equals(jsonBool(status, "running"));
        device.camera = jsonString(status, "camera");
        device.width = jsonInt(status, "width");
        device.height = jsonInt(status, "height");
        return device;
    }

    private static boolean validPort(int port) { return port > 0 && port <= 65535; }

    private static String requestHttp(String ip, int port, String path) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + ip + ":" + port + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();

            String resp = readAll(s.getInputStream(), 4096);
            // Discovery is public: never attach credentials or follow redirects.
            if (resp == null || resp.length() >= 4096
                    || !(resp.startsWith("HTTP/1.1 200 ") || resp.startsWith("HTTP/1.0 200 "))) return null;
            int separator = resp.indexOf("\r\n\r\n");
            if (separator < 0) return null;
            String body = resp.substring(separator + 4).trim();
            return body.startsWith("{") && body.endsWith("}") ? body : null;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(s);
        }
    }

    private static Device probeRtsp(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, RTSP_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(("OPTIONS rtsp://" + ip + ":" + RTSP_PORT + "/ RTSP/1.0\r\n"
                    + "CSeq: 1\r\nUser-Agent: OldPhoneCamScanner\r\n\r\n").getBytes("UTF-8"));
            out.flush();

            String resp = readAll(s.getInputStream(), 1024);
            if (resp == null || !resp.contains("OldPhoneCam")) {
                return null;
            }
            Device d = new Device();
            d.ip = ip;
            d.rtspOnly = true;
            d.running = true;
            return d;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(s);
        }
    }

    private static String readAll(InputStream in, int limit) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
            byte[] tmp = new byte[512];
            while (buf.size() < limit) {
                int n = in.read(tmp, 0, Math.min(tmp.length, limit - buf.size()));
                if (n <= 0) {
                    break;
                }
                buf.write(tmp, 0, n);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static String jsonString(String json, String key) {
        int start = jsonValueStart(json, key);
        if (start < 0 || start >= json.length() || json.charAt(start) != '"') return "";
        start++;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

    private static int jsonInt(String json, String key) {
        int start = jsonValueStart(json, key);
        if (start < 0) return 0;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Boolean jsonBool(String json, String key) {
        int start = jsonValueStart(json, key);
        if (start < 0) return null;
        if (json.startsWith("true", start) && jsonValueEnd(json, start + 4)) return Boolean.TRUE;
        if (json.startsWith("false", start) && jsonValueEnd(json, start + 5)) return Boolean.FALSE;
        return null;
    }

    private static boolean jsonValueEnd(String json, int position) {
        return position == json.length() || json.charAt(position) == ',' || json.charAt(position) == '}'
                || Character.isWhitespace(json.charAt(position));
    }

    private static int jsonValueStart(String json, String key) {
        String quoted = "\"" + key + "\"";
        int start = json.indexOf(quoted);
        if (start < 0) return -1;
        start += quoted.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start == json.length() || json.charAt(start++) != ':') return -1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        return start;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** 供界面显示：当前网段 */
    public static String currentSubnet() {
        return subnetPrefix(NetUtils.localIp()) + ".0/24";
    }
}
