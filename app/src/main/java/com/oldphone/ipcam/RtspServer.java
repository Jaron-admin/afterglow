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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** H.264 and optional AAC-LC RTSP server, with independent TCP/UDP RTP tracks. */
public final class RtspServer {
    private static final String TAG = "RtspServer";
    private static final int MAX_PAYLOAD = 1400;
    private static final int VIDEO_PT = 96;
    private static final int AUDIO_PT = 97;
    private final int port;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final List<Client> clients = new CopyOnWriteArrayList<Client>();
    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile int bitrate = 1_000_000;
    private volatile AudioConfig audioConfig;
    private final Random random = new Random();

    public RtspServer(int port) { this.port = port; }

    public void setVideoInfo(int width, int height, int bitrate) { this.bitrate = bitrate; }

    public void setCodecConfig(byte[] sps, byte[] pps) {
        this.sps = sps;
        this.pps = pps;
    }

    /** Configure before accepting clients; track topology changes require a new session. */
    public void setAudioConfig(int sampleRate, int channels, byte[] audioSpecificConfig) {
        if (audioSpecificConfig == null) { audioConfig = null; return; }
        if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 8
                || audioSpecificConfig.length == 0 || audioSpecificConfig.length > 64)
            throw new IllegalArgumentException("Invalid AAC audio configuration");
        audioConfig = new AudioConfig(sampleRate, channels, audioSpecificConfig.clone());
    }

    public int getPort() { return port; }
    public int clientCount() { return clients.size(); }

    public synchronized void start() throws IOException {
        if (running) return;
        final ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
        } catch (IOException e) { listener.close(); throw e; }
        serverSocket = listener;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(listener); }
        }, "rtsp-accept");
        acceptThread.start();
        Log.i(TAG, "RTSP listening on " + port);
    }

    private void acceptLoop(ServerSocket listener) {
        while (running && serverSocket == listener) {
            try {
                Socket socket = listener.accept();
                try { socket.setTcpNoDelay(true); socket.setSoTimeout(8000); }
                catch (IOException e) { socket.close(); throw e; }
                Client client = new Client(socket);
                synchronized (this) {
                    if (!running || serverSocket != listener) { socket.close(); return; }
                    clients.add(client);
                }
                new Thread(client, "rtsp-client").start();
            } catch (IOException e) { if (running && serverSocket == listener) Log.w(TAG, "accept: " + e); }
        }
    }

    /** Encoded immutable Annex-B access unit; PTS uses System.nanoTime()/1000. */
    public void pushFrame(byte[] data, long ptsUs, boolean keyFrame) {
        if (data == null || data.length == 0 || clients.isEmpty()) return;
        Frame frame = new Frame(data, ptsUs, keyFrame, 0);
        for (Client client : clients) client.offer(frame);
    }

    /** One immutable raw AAC access unit (without ADTS), on the same monotonic clock as video. */
    public void pushAudio(byte[] rawAac, long ptsUs) {
        if (rawAac == null || rawAac.length == 0 || audioConfig == null) return;
        if (rawAac.length > 8191) throw new IllegalArgumentException("AAC AU exceeds 13-bit AU-size");
        Frame frame = new Frame(rawAac, ptsUs, false, 1);
        for (Client client : clients) client.offer(frame);
    }

    public void stop() {
        Thread accept;
        synchronized (this) {
            running = false;
            for (Client client : clients) client.close();
            clients.clear();
            accept = acceptThread;
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
            serverSocket = null;
        }
        // Let accept() leave the old socket before the next start() binds the same port.
        if (accept != null && Thread.currentThread() != accept) {
            try { accept.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        Log.i(TAG, "RTSP stopped");
    }

    private static final class AudioConfig {
        final int rate, channels;
        final byte[] config;
        AudioConfig(int rate, int channels, byte[] config) { this.rate = rate; this.channels = channels; this.config = config; }
    }

    private static final class Frame {
        final byte[] data;
        final long ptsUs;
        final boolean key;
        final int track;
        Frame(byte[] data, long ptsUs, boolean key, int track) {
            this.data = data; this.ptsUs = ptsUs; this.key = key; this.track = track;
        }
    }

    private final class Client implements Runnable {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private final String sessionId = String.format(Locale.US, "%08X", random.nextInt());
        private final byte[] cname = (sessionId + "@afterglow").getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        private final AudioConfig audio = audioConfig;
        private final Track video = new Track(0, 90000, VIDEO_PT);
        private final Track sound = audio == null ? null : new Track(1, audio.rate, AUDIO_PT);
        private final Track[] tracks;
        // A slow TCP client must not turn into seconds of video delay. Keep at most a
        // few frames queued (<= ~1.5s even at 5 fps); on overflow the oldest frame is
        // dropped and the stream resynchronises at the next IDR (at most 1s away).
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<Frame>(8);
        private final Object transportLock = new Object();
        private volatile boolean playing;
        private volatile boolean closed;
        private Thread sender;
        private Thread rtcpThread;
        // One clock origin for both tracks. Never anchor a track to its send wall time:
        // different encoder/queue latency must not turn into a lip-sync error.
        private long originMonoUs;
        private long originWallMs;
        private boolean timelineStarted;

        Client(Socket socket) {
            this.socket = socket;
            tracks = sound == null ? new Track[]{video} : new Track[]{video, sound};
            if (sound != null && sound.ssrc == video.ssrc) sound.ssrc++;
        }

        private final class Track {
            final int id, clockRate, payloadType;
            final int tsBase = random.nextInt();
            int ssrc = random.nextInt();
            int seq = random.nextInt() & 65535;
            volatile boolean setup;
            boolean useTcp;
            int rtpChannel, rtcpChannel;
            int clientRtpPort, clientRtcpPort;
            DatagramSocket udpSocket, rtcpSocket;
            String url;
            int packetCount;
            long octetCount;
            Track(int id, int clockRate, int payloadType) {
                this.id = id; this.clockRate = clockRate; this.payloadType = payloadType;
            }
            int timestamp(long ptsUs) {
                long delta = ptsUs - originMonoUs;
                return tsBase + (int)((delta / 1000000L) * clockRate + (delta % 1000000L) * clockRate / 1000000L);
            }
            void closeTransport() {
                setup = false;
                if (udpSocket != null) udpSocket.close();
                if (rtcpSocket != null) rtcpSocket.close();
            }
        }

        void offer(Frame frame) {
            Track track = frame.track == 0 ? video : sound;
            if (!playing || closed || track == null || !track.setup) return;
            if (!queue.offer(frame)) { queue.poll(); queue.offer(frame); }
        }

        @Override public void run() {
            try {
                in = socket.getInputStream(); out = socket.getOutputStream();
                String line;
                while (!closed && (line = readLine(in)) != null) {
                    if (line.isEmpty()) continue;
                    String[] parts = line.split(" ");
                    if (parts.length < 2) continue;
                    Map<String, String> headers = new HashMap<String, String>();
                    String header;
                    while ((header = readLine(in)) != null && !header.isEmpty()) {
                        int colon = header.indexOf(':');
                        if (colon > 0) headers.put(header.substring(0, colon).trim().toLowerCase(Locale.US), header.substring(colon + 1).trim());
                    }
                    if (closed || header == null) break;
                    int bodyLength = parseInt(headers.get("content-length"), 0);
                    if (bodyLength < 0 || bodyLength > 65536) break;
                    skipFully(in, bodyLength);
                    handle(parts[0], parts[1], headers);
                }
            } catch (IOException e) { if (!closed) Log.w(TAG, "client io: " + e.getMessage()); }
            finally { close(); }
        }

        private void handle(String method, String url, Map<String, String> headers) throws IOException {
            int cseq = parseInt(headers.get("cseq"), 0);
            if ("OPTIONS".equalsIgnoreCase(method)) {
                respond(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n", null);
            } else if ("DESCRIBE".equalsIgnoreCase(method)) {
                byte[] body = buildSdp().getBytes("UTF-8");
                respond(cseq, "Content-Base: rtsp://" + localHost() + ":" + port + "/live/\r\nContent-Type: application/sdp\r\nContent-Length: " + body.length + "\r\n", body);
            } else if ("SETUP".equalsIgnoreCase(method)) {
                if (playing) { respond(cseq, "", null, "455 Method Not Valid in This State"); return; }
                Track track = trackForUrl(url);
                if (track == null) { respond(cseq, "", null, "404 Not Found"); return; }
                setup(track, url, headers.get("transport"), cseq);
            } else if ("PLAY".equalsIgnoreCase(method)) {
                boolean any = false;
                for (Track track : tracks) any |= track.setup;
                if (!any) { respond(cseq, "", null, "455 Method Not Valid in This State"); return; }
                socket.setSoTimeout(0);
                long now = System.nanoTime() / 1000L;
                if (!timelineStarted) {
                    originMonoUs = now; originWallMs = System.currentTimeMillis(); timelineStarted = true;
                }
                // Hold the packet write lock while reading seq and sending RTP-Info so
                // PLAY on an already-running session cannot race the advertised sequence.
                synchronized (this) {
                    StringBuilder info = new StringBuilder();
                    for (Track track : tracks) if (track.setup) {
                        if (info.length() != 0) info.append(',');
                        // Preserve exact client SETUP URLs: old Android MediaPlayer compares them literally.
                        info.append("url=").append(track.url).append(";seq=").append(track.seq)
                                .append(";rtptime=").append(track.timestamp(now) & 0xffffffffL);
                    }
                    // Accept frames before the peer can see the PLAY acknowledgement.
                    // Keeping the output lock held still guarantees the whole RTSP
                    // response precedes RTP/RTCP, including a resumed sender.
                    playing = true;
                    respond(cseq, "Session: " + sessionId + "\r\nRange: npt=0.000-\r\nRTP-Info: " + info + "\r\n", null);
                }
                startWorkers();
            } else if ("PAUSE".equalsIgnoreCase(method)) {
                playing = false; queue.clear(); respond(cseq, "Session: " + sessionId + "\r\n", null);
            } else if ("TEARDOWN".equalsIgnoreCase(method)) {
                playing = false; respond(cseq, "Session: " + sessionId + "\r\n", null); close();
            } else if ("GET_PARAMETER".equalsIgnoreCase(method) || "SET_PARAMETER".equalsIgnoreCase(method)) {
                respond(cseq, "Session: " + sessionId + "\r\n", null);
            } else respond(cseq, "", null, "405 Method Not Allowed");
        }

        private Track trackForUrl(String url) {
            String path = url.split("\\?", 2)[0];
            if (path.endsWith("/trackID=1")) return sound;
            if (path.endsWith("/trackID=0") || path.endsWith("/live") || path.endsWith("/live/")) return video;
            return null;
        }

        private void setup(Track track, String url, String transport, int cseq) throws IOException {
            if (transport == null) { respond(cseq, "", null, "461 Unsupported Transport"); return; }
            String upper = transport.trim().toUpperCase(Locale.US);
            String profile = upper.split(";", 2)[0].trim();
            boolean tcp = profile.equals("RTP/AVP/TCP");
            boolean udp = profile.equals("RTP/AVP") || profile.equals("RTP/AVP/UDP");
            if ((!tcp && !udp) || upper.contains("MULTICAST")) {
                respond(cseq, "", null, "461 Unsupported Transport"); return;
            }
            int[] pair = transportPair(upper, tcp ? "INTERLEAVED=" : "CLIENT_PORT=", tcp ? track.id * 2 : -1);
            int max = tcp ? 255 : 65535;
            if (pair == null || pair[0] < (tcp ? 0 : 1) || pair[1] < (tcp ? 0 : 1)
                    || pair[0] > max || pair[1] > max || pair[0] == pair[1]) {
                respond(cseq, "", null, "461 Unsupported Transport"); return;
            }
            if (tcp) for (Track other : tracks) if (other != track && other.setup && other.useTcp
                    && (pair[0] == other.rtpChannel || pair[0] == other.rtcpChannel || pair[1] == other.rtpChannel || pair[1] == other.rtcpChannel)) {
                respond(cseq, "", null, "461 Unsupported Transport"); return;
            }
            DatagramSocket rtp = null, rtcp = null;
            if (!tcp) {
                try {
                    rtp = new DatagramSocket(0); rtp.setSendBufferSize(512 * 1024);
                    rtcp = new DatagramSocket(0);
                } catch (IOException e) {
                    if (rtp != null) rtp.close(); if (rtcp != null) rtcp.close();
                    respond(cseq, "", null, "500 Internal Server Error"); return;
                }
            }
            String response;
            synchronized (transportLock) {
                // stop() may close the client while UDP sockets are being allocated.
                // Publish or dispose both sockets atomically with closeTransport().
                if (closed) {
                    if (rtp != null) rtp.close();
                    if (rtcp != null) rtcp.close();
                    return;
                }
                track.closeTransport();
                track.useTcp = tcp; track.url = url;
                track.udpSocket = rtp; track.rtcpSocket = rtcp;
                if (tcp) {
                    track.rtpChannel = pair[0]; track.rtcpChannel = pair[1];
                    response = "RTP/AVP/TCP;unicast;interleaved=" + pair[0] + "-" + pair[1];
                } else {
                    track.clientRtpPort = pair[0]; track.clientRtcpPort = pair[1];
                    response = profile + ";unicast;client_port=" + pair[0] + "-" + pair[1]
                            + ";server_port=" + rtp.getLocalPort() + "-" + rtcp.getLocalPort();
                }
                track.setup = true;
            }
            respond(cseq, "Transport: " + response + ";ssrc=" + String.format(Locale.US, "%08X", track.ssrc)
                    + "\r\nSession: " + sessionId + ";timeout=60\r\n", null);
        }

        private String buildSdp() {
            StringBuilder sb = new StringBuilder("v=0\r\no=- ").append(sessionId)
                    .append(" 1 IN IP4 0.0.0.0\r\ns=Afterglow\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\na=control:*\r\na=range:npt=0-\r\n")
                    .append("m=video 0 RTP/AVP ").append(VIDEO_PT).append("\r\nb=AS:").append(Math.max(1, bitrate / 1000))
                    .append("\r\na=rtpmap:").append(VIDEO_PT).append(" H264/90000\r\na=fmtp:").append(VIDEO_PT).append(" packetization-mode=1");
            byte[] s = sps, p = pps;
            if (s != null && s.length >= 4) {
                sb.append(";profile-level-id=").append(String.format(Locale.US, "%02x%02x%02x", s[1] & 255, s[2] & 255, s[3] & 255));
                if (p != null) sb.append(";sprop-parameter-sets=").append(Base64.encodeToString(s, Base64.NO_WRAP))
                        .append(',').append(Base64.encodeToString(p, Base64.NO_WRAP));
            }
            sb.append("\r\na=control:trackID=0\r\n");
            if (audio != null) {
                sb.append("m=audio 0 RTP/AVP ").append(AUDIO_PT).append("\r\na=rtpmap:").append(AUDIO_PT)
                        .append(" MPEG4-GENERIC/").append(audio.rate).append('/').append(audio.channels)
                        .append("\r\na=fmtp:").append(AUDIO_PT)
                        .append(" streamtype=5;profile-level-id=1;mode=AAC-hbr;config=");
                for (byte b : audio.config) sb.append(String.format(Locale.US, "%02x", b & 255));
                sb.append(";SizeLength=13;IndexLength=3;IndexDeltaLength=3;constantDuration=1024\r\na=control:trackID=1\r\n");
            }
            return sb.toString();
        }

        private void respond(int cseq, String headers, byte[] body) throws IOException { respond(cseq, headers, body, "200 OK"); }
        private void respond(int cseq, String headers, byte[] body, String status) throws IOException {
            // Keep the legacy scanner fingerprint for RTSP-only devices.
            byte[] head = ("RTSP/1.0 " + status + "\r\nCSeq: " + cseq + "\r\nServer: Afterglow (OldPhoneCam)\r\nDate: " + httpDate() + "\r\n" + headers + "\r\n").getBytes("UTF-8");
            synchronized (this) {
                out.write(head); if (body != null) out.write(body); out.flush();
            }
        }

        private void startWorkers() {
            if (sender == null) {
                sender = new Thread(new Runnable() { @Override public void run() { senderLoop(); } }, "rtsp-sender");
                sender.setDaemon(true); sender.start();
            }
            if (rtcpThread == null) {
                rtcpThread = new Thread(new Runnable() {
                    @Override public void run() {
                        while (!closed && running) {
                            try {
                                if (playing) {
                                    long now = System.nanoTime() / 1000L;
                                    for (Track track : tracks) if (track.setup) sendPacket(track, buildSenderReport(track, now), true);
                                }
                                Thread.sleep(1000);
                            } catch (InterruptedException e) { return; }
                            catch (IOException e) { close(); return; }
                        }
                    }
                }, "rtcp-sender");
                rtcpThread.setDaemon(true); rtcpThread.start();
            }
        }

        /** RFC3550 compound SR+SDES: common NTP/CNAME lets receivers align both media clocks. */
        private byte[] buildSenderReport(Track track, long nowMonoUs) {
            long unixUs = originWallMs * 1000L + nowMonoUs - originMonoUs;
            long seconds = unixUs / 1000000L + 2208988800L;
            long fraction = (unixUs % 1000000L) * 4294967296L / 1000000L;
            int sdesLength = (8 + 2 + cname.length + 1 + 3) & ~3;
            byte[] p = new byte[28 + sdesLength];
            p[0] = (byte) 0x80; p[1] = (byte) 200; p[3] = 6;
            putInt(p, 4, track.ssrc); putInt(p, 8, (int) seconds); putInt(p, 12, (int) fraction);
            putInt(p, 16, track.timestamp(nowMonoUs));
            synchronized (this) { putInt(p, 20, track.packetCount); putInt(p, 24, (int) track.octetCount); }
            p[28] = (byte) 0x81; p[29] = (byte) 202;
            p[30] = (byte) ((sdesLength / 4 - 1) >> 8); p[31] = (byte) (sdesLength / 4 - 1);
            putInt(p, 32, track.ssrc); p[36] = 1; p[37] = (byte) cname.length;
            System.arraycopy(cname, 0, p, 38, cname.length);
            return p;
        }

        private void senderLoop() {
            while (!closed && running) {
                try {
                    Frame frame = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (frame == null || !playing) continue;
                    Track track = frame.track == 0 ? video : sound;
                    if (track == null || !track.setup) continue;
                    if (frame.track == 1) sendAudio(track, frame); else sendVideo(track, frame);
                } catch (InterruptedException e) { return; }
                catch (IOException e) { if (!closed) Log.w(TAG, "send: " + e.getMessage()); close(); return; }
            }
        }

        private void sendAudio(Track track, Frame frame) throws IOException {
            int ts = track.timestamp(frame.ptsUs);
            for (int off = 0; off < frame.data.length;) {
                int chunk = Math.min(MAX_PAYLOAD - 4, frame.data.length - off);
                byte[] p = new byte[16 + chunk];
                p[12] = 0; p[13] = 16; // AU-headers-length in bits.
                p[14] = (byte) (frame.data.length >> 5); p[15] = (byte) (frame.data.length << 3); // 13-bit size, 3-bit AU-index 0.
                System.arraycopy(frame.data, off, p, 16, chunk);
                off += chunk;
                sendRtp(track, p, off == frame.data.length, ts);
            }
        }

        private void sendVideo(Track track, Frame frame) throws IOException {
            int ts = track.timestamp(frame.ptsUs);
            byte[] s = sps, p = pps;
            if (frame.key && s != null) {
                sendNal(track, s, 0, s.length, ts, false);
                if (p != null) sendNal(track, p, 0, p.length, ts, false);
            }
            List<int[]> nals = H264Encoder.splitAnnexB(frame.data, frame.data.length);
            for (int i = 0; i < nals.size(); i++) {
                int[] nal = nals.get(i);
                sendNal(track, frame.data, nal[0], nal[1], ts, i == nals.size() - 1);
            }
        }

        private void sendNal(Track track, byte[] data, int off, int len, int ts, boolean marker) throws IOException {
            if (len <= 0) return;
            if (len <= MAX_PAYLOAD) {
                byte[] packet = new byte[12 + len]; System.arraycopy(data, off, packet, 12, len);
                sendRtp(track, packet, marker, ts); return;
            }
            int indicator = (data[off] & 0xe0) | 28, type = data[off] & 31;
            int pos = off + 1, remaining = len - 1;
            boolean first = true;
            while (remaining > 0) {
                int chunk = Math.min(MAX_PAYLOAD - 2, remaining);
                boolean last = chunk == remaining;
                byte[] packet = new byte[14 + chunk];
                packet[12] = (byte) indicator; packet[13] = (byte) ((first ? 128 : 0) | (last ? 64 : 0) | type);
                System.arraycopy(data, pos, packet, 14, chunk);
                sendRtp(track, packet, marker && last, ts);
                pos += chunk; remaining -= chunk; first = false;
            }
        }

        private void sendRtp(Track track, byte[] packet, boolean marker, int timestamp) throws IOException {
            synchronized (this) {
                if (!playing || closed || !track.setup) return;
                packet[0] = (byte) 128; packet[1] = (byte) ((marker ? 128 : 0) | track.payloadType);
                packet[2] = (byte) (track.seq >> 8); packet[3] = (byte) track.seq;
                putInt(packet, 4, timestamp); putInt(packet, 8, track.ssrc);
                sendPacket(track, packet, false);
                track.seq = (track.seq + 1) & 65535;
                track.packetCount++;
                track.octetCount += packet.length - 12; // RFC3550 counts payload octets, excluding RTP header.
            }
        }

        private void sendPacket(Track track, byte[] packet, boolean rtcp) throws IOException {
            synchronized (this) {
                if (closed || !playing || !track.setup) return;
                if (track.useTcp) {
                    out.write('$'); out.write(rtcp ? track.rtcpChannel : track.rtpChannel);
                    out.write((packet.length >> 8) & 255); out.write(packet.length & 255);
                    out.write(packet); out.flush();
                } else {
                    DatagramSocket transport = rtcp ? track.rtcpSocket : track.udpSocket;
                    if (transport != null) transport.send(new DatagramPacket(packet, packet.length,
                            socket.getInetAddress(), rtcp ? track.clientRtcpPort : track.clientRtpPort));
                }
            }
        }

        private String localHost() {
            InetAddress address = socket.getLocalAddress();
            String ip = address == null ? null : address.getHostAddress();
            return ip == null || ip.contains(":") ? NetUtils.localIp() : ip;
        }

        void close() {
            if (closed) return;
            closed = true; playing = false; clients.remove(this);
            // Do not acquire the output lock here: closing unblocks a slow TCP writer.
            try { socket.close(); } catch (IOException ignored) { }
            synchronized (transportLock) {
                for (Track track : tracks) track.closeTransport();
            }
            queue.clear();
            if (sender != null) sender.interrupt();
            if (rtcpThread != null) rtcpThread.interrupt();
        }
    }

    private static void putInt(byte[] p, int off, int value) {
        p[off] = (byte) (value >> 24); p[off + 1] = (byte) (value >> 16);
        p[off + 2] = (byte) (value >> 8); p[off + 3] = (byte) value;
    }

    private static int[] transportPair(String value, String key, int fallback) {
        int index = value.indexOf(key);
        if (index < 0) return fallback < 0 ? null : new int[]{fallback, fallback + 1};
        String pair = value.substring(index + key.length()).split(";", 2)[0].trim();
        String[] numbers = pair.split("-", -1);
        if (numbers.length != 2) return null;
        try { return new int[]{Integer.parseInt(numbers[0]), Integer.parseInt(numbers[1])}; }
        catch (NumberFormatException e) { return null; }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '$' && line.length() == 0) {
                int channel = in.read(), high = in.read(), low = in.read();
                if (channel < 0 || high < 0 || low < 0) return null;
                skipFully(in, (high << 8) | low); continue;
            }
            if (b == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') line.setLength(length - 1);
                return line.toString();
            }
            line.append((char) b);
            if (line.length() > 8192) return null;
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void skipFully(InputStream in, int length) throws IOException {
        int consumed = 0;
        while (consumed < length) {
            long count = in.skip(length - consumed);
            if (count <= 0) { if (in.read() < 0) throw new java.io.EOFException(); count = 1; }
            consumed += (int) count;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static String httpDate() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }
}
