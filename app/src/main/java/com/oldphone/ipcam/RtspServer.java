package com.oldphone.ipcam;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 轻量 RTSP 服务端（只做推流方向）。
 *
 * 支持：
 *  - OPTIONS / DESCRIBE / SETUP / PLAY / TEARDOWN / GET_PARAMETER / SET_PARAMETER
 *  - RTP over TCP（interleaved）与 RTP over UDP
 *  - H.264 单 NAL + FU-A 分片
 *
 * 已用 go2rtc / ffmpeg / VLC 的通用行为对齐：DESCRIBE 返回带
 * sprop-parameter-sets 的 SDP，PLAY 返回 RTP-Info。
 */
public final class RtspServer {

    private static final String TAG = "RtspServer";
    private static final int MAX_PAYLOAD = 1400;
    private static final int RTP_PT = 96;

    private final int port;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    private final List<Client> clients = new CopyOnWriteArrayList<Client>();

    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile int videoWidth = 640;
    private volatile int videoHeight = 480;
    private volatile int bitrate = 1_000_000;

    private final Random random = new Random();

    public RtspServer(int port) {
        this.port = port;
    }

    public void setVideoInfo(int width, int height, int bitrate) {
        this.videoWidth = width;
        this.videoHeight = height;
        this.bitrate = bitrate;
    }

    public void setCodecConfig(byte[] sps, byte[] pps) {
        this.sps = sps;
        this.pps = pps;
    }

    public int getPort() {
        return port;
    }

    public int clientCount() {
        return clients.size();
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        final ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            listener.close();
            throw e;
        }
        serverSocket = listener;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(listener);
            }
        }, "rtsp-accept");
        acceptThread.start();
        Log.i(TAG, "RTSP listening on " + port);
    }

    private void acceptLoop(ServerSocket listener) {
        while (running && serverSocket == listener) {
            try {
                Socket socket = listener.accept();
                try {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(8000);
                } catch (IOException e) {
                    socket.close();
                    throw e;
                }
                Client client = new Client(socket);
                synchronized (this) {
                    if (!running || serverSocket != listener) {
                        socket.close();
                        return;
                    }
                    clients.add(client);
                }
                new Thread(client, "rtsp-client").start();
                Log.i(TAG, "client connected: " + socket.getInetAddress().getHostAddress());
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept: " + e);
                }
            }
        }
    }

    /** 把一帧编码后的 H.264 分发给所有正在播放的客户端 */
    public void pushFrame(byte[] data, long ptsUs, boolean keyFrame) {
        if (clients.isEmpty()) {
            return;
        }
        Frame frame = new Frame(data, ptsUs, keyFrame);
        for (Client c : clients) {
            c.offer(frame);
        }
    }

    public synchronized void stop() {
        running = false;
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        serverSocket = null;
        Log.i(TAG, "RTSP stopped");
    }

    // ------------------------------------------------------------------

    private static final class Frame {
        final byte[] data;
        final long ptsUs;
        final boolean key;

        Frame(byte[] data, long ptsUs, boolean key) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.key = key;
        }
    }

    private final class Client implements Runnable {

        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private final String sessionId;
        private final int ssrc;

        private volatile boolean playing;
        private volatile boolean closed;

        private boolean useTcp = true;
        private int rtpChannel = 0;
        private int rtcpChannel = 1;
        private InetAddress clientAddr;
        private int clientRtpPort;
        private int clientRtcpPort;
        private DatagramSocket udpSocket;
        private int serverRtpPort;
        private DatagramSocket rtcpSocket;
        private int serverRtcpPort;

        private int seq = 0;
        /**
         * RTP 时间戳的随机基准。
         *
         * 同一个连接里所有 RTP 包的时间戳都基于它，所以 PLAY 响应的
         * RTP-Info rtptime= 可以直接写它，客户端据此把 npt=0 对回真实时间轴。
         */
        private final int tsBase;
        private volatile int lastRtpTs;
        private volatile long lastRtpWallMs;
        private volatile int rtpPacketCount;
        private volatile long rtpOctetCount;

        /**
         * SETUP 请求里客户端自己写的 track URL。
         *
         * 这个必须原样回填到 PLAY 响应的 RTP-Info url= 里：
         * Android 的 MyHandler::parsePlayResponse 会把 RTP-Info 的 url 与
         * 它保存的 track URL 做字符串全等比较，不等就直接 CHECK_LT 失败
         * abort 掉 mediaserver（不是返回错误，是整个 media 服务崩）。
         * 而它保存的那个 URL 就是 SETUP 里发出来的这一个，所以照抄最安全。
         */
        private String trackUrl;
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<Frame>(40);
        private Thread sender;
        private Thread rtcpThread;

        Client(Socket socket) {
            this.socket = socket;
            this.sessionId = String.format(Locale.US, "%08X", random.nextInt());
            this.ssrc = random.nextInt();
            this.seq = random.nextInt() & 0xFFFF;
            this.tsBase = random.nextInt();
        }

        void offer(Frame f) {
            if (!playing || closed) {
                return;
            }
            if (!queue.offer(f)) {
                // 客户端跟不上：丢掉旧帧，宁可花屏也不要无限堆积
                queue.poll();
                queue.offer(f);
            }
        }

        @Override
        public void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
                String line;
                while (!closed && (line = readLine(in)) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split(" ");
                    if (parts.length < 2) {
                        continue;
                    }
                    String method = parts[0];
                    String url = parts[1];

                    Map<String, String> headers = new HashMap<String, String>();
                    String h;
                    while ((h = readLine(in)) != null && !h.isEmpty()) {
                        int c = h.indexOf(':');
                        if (c > 0) {
                            headers.put(h.substring(0, c).trim().toLowerCase(Locale.US),
                                    h.substring(c + 1).trim());
                        }
                    }
                    if (closed || h == null) {
                        break;
                    }
                    handle(method, url, headers);
                }
            } catch (IOException e) {
                if (!closed) {
                    Log.w(TAG, "client io: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        private void handle(String method, String url, Map<String, String> h) throws IOException {
            int cseq = parseInt(h.get("cseq"), 0);

            if ("OPTIONS".equalsIgnoreCase(method)) {
                respond(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n", null);

            } else if ("DESCRIBE".equalsIgnoreCase(method)) {
                String sdp = buildSdp();
                byte[] body = sdp.getBytes("UTF-8");
                String base = "rtsp://" + localHost() + ":" + port + "/live";
                respond(cseq,
                        "Content-Base: " + base + "/\r\n"
                                + "Content-Type: application/sdp\r\n"
                                + "Content-Length: " + body.length + "\r\n",
                        body);

            } else if ("SETUP".equalsIgnoreCase(method)) {
                trackUrl = url;
                String transport = h.get("transport");
                if (transport == null) {
                    respond(cseq, "", null, "461 Unsupported Transport");
                    return;
                }
                String upper = transport.toUpperCase(Locale.US);
                StringBuilder resp = new StringBuilder();

                if (upper.contains("TCP")) {
                    useTcp = true;
                    int ch = parseInt(substringAfter(upper, "INTERLEAVED="), 0);
                    rtpChannel = ch;
                    rtcpChannel = ch + 1;
                    resp.append("Transport: RTP/AVP/TCP;unicast;interleaved=")
                            .append(rtpChannel).append("-").append(rtcpChannel).append("\r\n");
                } else {
                    useTcp = false;
                    String cp = substringAfter(upper, "CLIENT_PORT=");
                    int cp1 = parseInt(cp, 0);
                    int cp2 = cp1 + 1;
                    int dash = cp.indexOf('-');
                    if (dash > 0) {
                        cp2 = parseInt(cp.substring(dash + 1), cp1 + 1);
                    }
                    if (cp1 <= 0 || cp1 > 65535) {
                        // 没有可用的 client_port 就没法发 RTP，明确回 461 让客户端改走 TCP
                        respond(cseq, "", null, "461 Unsupported Transport");
                        return;
                    }
                    clientRtpPort = cp1;
                    clientRtcpPort = cp2;
                    clientAddr = socket.getInetAddress();

                    if (udpSocket == null) {
                        udpSocket = new DatagramSocket(0);
                        udpSocket.setSendBufferSize(512 * 1024);
                        serverRtpPort = udpSocket.getLocalPort();
                    }
                    if (rtcpSocket == null) {
                        rtcpSocket = new DatagramSocket(0);
                        serverRtcpPort = rtcpSocket.getLocalPort();
                    }
                    resp.append("Transport: RTP/AVP;unicast;client_port=")
                            .append(cp1).append("-").append(cp2)
                            .append(";server_port=").append(serverRtpPort)
                            .append("-").append(serverRtcpPort).append("\r\n");
                    resp.append("SSRC: ").append(String.format(Locale.US, "%08X", ssrc)).append("\r\n");
                }
                resp.append("Session: ").append(sessionId).append(";timeout=60\r\n");
                respond(cseq, resp.toString(), null);

            } else if ("PLAY".equalsIgnoreCase(method)) {
                // Bound abandoned handshakes, while allowing established players
                // to leave the control socket idle as media continues to flow.
                socket.setSoTimeout(0);
                playing = true;
                startSender();
                startRtcp();
                // 用 SETUP 里客户端给的 track URL 原样回填，见 trackUrl 的注释
                String infoUrl = trackUrl != null ? trackUrl : url + "/trackID=0";
                String rtpInfo = "url=" + infoUrl + ";seq=" + seq + ";rtptime=" + tsBase + "\r\n";
                Log.i(TAG, "PLAY rtp-info url=" + infoUrl + " seq=" + seq);
                respond(cseq,
                        "Session: " + sessionId + "\r\n"
                                + "Range: npt=0.000-\r\n"
                                + "RTP-Info: " + rtpInfo, null);

            } else if ("PAUSE".equalsIgnoreCase(method)) {
                playing = false;
                respond(cseq, "Session: " + sessionId + "\r\n", null);

            } else if ("TEARDOWN".equalsIgnoreCase(method)) {
                respond(cseq, "Session: " + sessionId + "\r\n", null);
                close();

            } else if ("GET_PARAMETER".equalsIgnoreCase(method)
                    || "SET_PARAMETER".equalsIgnoreCase(method)) {
                respond(cseq, "Session: " + sessionId + "\r\n", null);

            } else {
                respond(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n", null,
                        "405 Method Not Allowed");
            }
        }

        private String buildSdp() {
            StringBuilder sb = new StringBuilder();
            sb.append("v=0\r\n");
            sb.append("o=- ").append(sessionId).append(" 1 IN IP4 0.0.0.0\r\n");
            sb.append("s=OldPhoneCam\r\n");
            sb.append("c=IN IP4 0.0.0.0\r\n");
            sb.append("t=0 0\r\n");
            sb.append("a=control:*\r\n");
            sb.append("a=range:npt=0-\r\n");
            sb.append("m=video 0 RTP/AVP ").append(RTP_PT).append("\r\n");
            sb.append("b=AS:").append(Math.max(1, bitrate / 1000)).append("\r\n");
            sb.append("a=rtpmap:").append(RTP_PT).append(" H264/90000\r\n");

            StringBuilder fmtp = new StringBuilder("a=fmtp:").append(RTP_PT)
                    .append(" packetization-mode=1");
            byte[] s = sps;
            byte[] p = pps;
            if (s != null && s.length >= 4) {
                String profileLevel = String.format(Locale.US, "%02x%02x%02x",
                        s[1] & 0xFF, s[2] & 0xFF, s[3] & 0xFF);
                fmtp.append(";profile-level-id=").append(profileLevel);
                if (p != null) {
                    fmtp.append(";sprop-parameter-sets=")
                            .append(Base64.encodeToString(s, Base64.NO_WRAP))
                            .append(",")
                            .append(Base64.encodeToString(p, Base64.NO_WRAP));
                }
            }
            fmtp.append("\r\n");
            sb.append(fmtp);
            sb.append("a=control:trackID=0\r\n");
            return sb.toString();
        }

        private void respond(int cseq, String headers, byte[] body) throws IOException {
            respond(cseq, headers, body, "200 OK");
        }

        private void respond(int cseq, String headers, byte[] body, String status) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("RTSP/1.0 ").append(status).append("\r\n");
            sb.append("CSeq: ").append(cseq).append("\r\n");
            sb.append("Server: OldPhoneCam/1.0\r\n");
            sb.append("Date: ").append(httpDate()).append("\r\n");
            sb.append(headers);
            sb.append("\r\n");
            byte[] head = sb.toString().getBytes("UTF-8");
            synchronized (this) {
                out.write(head);
                if (body != null && body.length > 0) {
                    out.write(body);
                }
                out.flush();
            }
        }

        private void startSender() {
            if (sender != null) {
                return;
            }
            sender = new Thread(new Runnable() {
                @Override
                public void run() {
                    senderLoop();
                }
            }, "rtsp-sender");
            sender.setDaemon(true);
            sender.start();
        }

        /**
         * RTCP 发送者报告。
         *
         * Android 的 MediaPlayer 收不到 RTCP SR 时要等一个约 10 秒的
         * 超时才去「假装」时间戳（日志会打 using fake timestamps），
         * 在那之前画面是空屏。1 秒一条 SR 让客户端立刻对齐时间轴，
         * ffmpeg / VLC 也会更稳。
         */
        private void startRtcp() {
            if (rtcpThread != null) {
                return;
            }
            rtcpThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (playing && !closed && running) {
                        try {
                            sendRtcp(buildSenderReport());
                        } catch (IOException e) {
                            return;
                        } catch (Throwable ignored) {
                            return;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "rtcp-sender");
            rtcpThread.setDaemon(true);
            rtcpThread.start();
        }

        /** 28 字节 RTCP SR（PT=200），只带必填字段，不带 report block */
        private byte[] buildSenderReport() {
            long nowMs = System.currentTimeMillis();
            long secs = nowMs / 1000L + 2208988800L;        // NTP 纪元的秒
            long frac = ((nowMs % 1000L) << 32) / 1000L;   // 毫秒转小数部分

            int ts = lastRtpTs;
            if (lastRtpWallMs != 0) {
                // 把最新一帧的时间戳外推到"现在"
                ts += (int) ((nowMs - lastRtpWallMs) * 90L);
            }

            byte[] p = new byte[28];
            p[0] = (byte) 0x80;    // V=2, P=0, RC=0
            p[1] = (byte) 200;     // PT=SR
            p[2] = 0;
            p[3] = 6;              // length = 28/4 - 1
            putInt(p, 4, ssrc);
            putInt(p, 8, (int) secs);
            putInt(p, 12, (int) frac);
            putInt(p, 16, ts);
            putInt(p, 20, rtpPacketCount);
            putInt(p, 24, (int) rtpOctetCount);
            return p;
        }

        private void putInt(byte[] p, int off, int v) {
            p[off] = (byte) (v >> 24);
            p[off + 1] = (byte) (v >> 16);
            p[off + 2] = (byte) (v >> 8);
            p[off + 3] = (byte) v;
        }

        private void sendRtcp(byte[] p) throws IOException {
            if (useTcp) {
                synchronized (this) {
                    out.write('$');
                    out.write(rtcpChannel);
                    out.write((p.length >> 8) & 0xFF);
                    out.write(p.length & 0xFF);
                    out.write(p, 0, p.length);
                    out.flush();
                }
            } else if (rtcpSocket != null && clientAddr != null && clientRtcpPort > 0) {
                rtcpSocket.send(new DatagramPacket(p, p.length, clientAddr, clientRtcpPort));
            }
        }

        private void senderLoop() {
            while (playing && !closed && running) {
                Frame f;
                try {
                    f = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (f == null) {
                    continue;
                }
                try {
                    sendAccessUnit(f);
                } catch (IOException e) {
                    Log.w(TAG, "send: " + e.getMessage());
                    close();
                    break;
                }
            }
        }

        private void sendAccessUnit(Frame f) throws IOException {
            int ts = tsBase + (int) ((f.ptsUs * 90L / 1000L) & 0xFFFFFFFFL);
            lastRtpTs = ts;
            lastRtpWallMs = System.currentTimeMillis();

            byte[] s = sps;
            byte[] p = pps;
            // 关键帧前显式补发 SPS/PPS，保证中途接入的播放器也能解码
            if (f.key && s != null) {
                sendNal(s, 0, s.length, ts, false);
                if (p != null) {
                    sendNal(p, 0, p.length, ts, false);
                }
            }

            List<int[]> nals = H264Encoder.splitAnnexB(f.data, f.data.length);
            for (int i = 0; i < nals.size(); i++) {
                int off = nals.get(i)[0];
                int len = nals.get(i)[1];
                boolean last = (i == nals.size() - 1);
                sendNal(f.data, off, len, ts, last);
            }
        }

        private void sendNal(byte[] d, int off, int len, int ts, boolean marker) throws IOException {
            if (len <= 0) {
                return;
            }
            if (len <= MAX_PAYLOAD) {
                byte[] pkt = new byte[12 + len];
                writeRtpHeader(pkt, marker, ts);
                System.arraycopy(d, off, pkt, 12, len);
                writePacket(pkt, pkt.length);
                return;
            }

            // FU-A 分片
            int indicator = (d[off] & 0xE0) | 28;
            int type = d[off] & 0x1F;
            int pos = off + 1;
            int remaining = len - 1;
            boolean first = true;
            int chunkMax = MAX_PAYLOAD - 2;

            while (remaining > 0) {
                int chunk = Math.min(chunkMax, remaining);
                boolean last = (chunk == remaining);
                byte[] pkt = new byte[12 + 2 + chunk];
                writeRtpHeader(pkt, marker && last, ts);
                pkt[12] = (byte) indicator;
                pkt[13] = (byte) ((first ? 0x80 : 0x00) | (last ? 0x40 : 0x00) | type);
                System.arraycopy(d, pos, pkt, 14, chunk);
                writePacket(pkt, 14 + chunk);
                pos += chunk;
                remaining -= chunk;
                first = false;
            }
        }

        private void writeRtpHeader(byte[] pkt, boolean marker, int ts) {
            pkt[0] = (byte) 0x80;
            pkt[1] = (byte) ((marker ? 0x80 : 0x00) | RTP_PT);
            pkt[2] = (byte) ((seq >> 8) & 0xFF);
            pkt[3] = (byte) (seq & 0xFF);
            pkt[4] = (byte) ((ts >> 24) & 0xFF);
            pkt[5] = (byte) ((ts >> 16) & 0xFF);
            pkt[6] = (byte) ((ts >> 8) & 0xFF);
            pkt[7] = (byte) (ts & 0xFF);
            pkt[8] = (byte) ((ssrc >> 24) & 0xFF);
            pkt[9] = (byte) ((ssrc >> 16) & 0xFF);
            pkt[10] = (byte) ((ssrc >> 8) & 0xFF);
            pkt[11] = (byte) (ssrc & 0xFF);
            seq = (seq + 1) & 0xFFFF;
            rtpPacketCount++;
        }

        private void writePacket(byte[] pkt, int len) throws IOException {
            rtpOctetCount += len;
            if (useTcp) {
                synchronized (this) {
                    out.write('$');
                    out.write(rtpChannel);
                    out.write((len >> 8) & 0xFF);
                    out.write(len & 0xFF);
                    out.write(pkt, 0, len);
                    out.flush();
                }
            } else {
                DatagramPacket dp = new DatagramPacket(pkt, len, clientAddr, clientRtpPort);
                udpSocket.send(dp);
            }
        }

        private String localHost() {
            InetAddress a = socket.getLocalAddress();
            String ip = a == null ? null : a.getHostAddress();
            if (ip == null || ip.contains(":")) {
                ip = NetUtils.localIp();
            }
            return ip;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            playing = false;
            clients.remove(this);
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
            if (udpSocket != null) {
                udpSocket.close();
                udpSocket = null;
            }
            if (rtcpSocket != null) {
                rtcpSocket.close();
                rtcpSocket = null;
            }
            queue.clear();
        }
    }

    // ---------------- 工具方法 ----------------

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '$') {
                // 客户端可能插入 RTCP 交错包，直接跳过
                int ch = in.read();
                int hi = in.read();
                int lo = in.read();
                if (hi < 0 || lo < 0) {
                    return null;
                }
                int len = (hi << 8) | lo;
                long skipped = 0;
                while (skipped < len) {
                    long n = in.skip(len - skipped);
                    if (n <= 0) {
                        if (in.read() < 0) {
                            return null;
                        }
                        n = 1;
                    }
                    skipped += n;
                }
                continue;
            }
            if (b == '\n') {
                int l = sb.length();
                if (l > 0 && sb.charAt(l - 1) == '\r') {
                    sb.setLength(l - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > 8192) {
                return null;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String substringAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return "";
        }
        return s.substring(i + key.length()).trim();
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            String t = s.trim();
            int i = 0;
            // 允许一个前导符号位，但 '-' 不能当数字位用：
            // "client_port=50000-50001" 里 '-' 是端口分隔符，
            // 之前把它算进数字串会导致 Integer.parseInt 抛异常并静默回退成 0。
            if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+')) {
                i++;
            }
            int start = i;
            while (i < t.length() && Character.isDigit(t.charAt(i))) {
                i++;
            }
            if (i == start) {
                return def;
            }
            return Integer.parseInt(t.substring(0, i));
        } catch (Exception e) {
            return def;
        }
    }

    private static String httpDate() {
        return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .format(new Date());
    }
}
