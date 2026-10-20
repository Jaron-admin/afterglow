package com.oldphone.ipcam;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real loopback sockets exercise signaling, routing and packet bytes without Android. */
public final class RtspAudioTest {
    static final byte[] AAC = new byte[]{10, 20, 30, 40, 50};
    static final byte[] VIDEO = new byte[]{0, 0, 0, 1, 0x61, 11, 22};
    public static void main(String[] args) throws Exception {
        disabledAudioAndVideoCompatibility();
        tcpTracks(false);
        tcpTracks(true);
        audioOnlyAndPauseResume();
        udpTracks();
        restartDisposesTracks();
        System.out.println("PASS: RTSP audio SDP; both SETUP orders; audio-only/video-only; independent TCP/UDP tracks; AAC AU fragmentation; shared RTCP clock/CNAME; pause/resume; restart disposal");
    }

    static RtspServer server(boolean audio) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        RtspServer server = new RtspServer(port);
        if (audio) {
            // Reflection makes the initial missing feature a runtime failure after the old
            // video-only socket contract has been exercised.
            try { RtspServer.class.getMethod("setAudioConfig", int.class, int.class, byte[].class)
                    .invoke(server, 16000, 1, new byte[]{0x14, 0x08}); }
            catch (NoSuchMethodException missing) { throw new AssertionError("RTSP must expose optional AAC audio configuration", missing); }
        }
        server.start();
        return server;
    }
    static void audio(RtspServer server, byte[] data, long ptsUs) throws Exception {
        RtspServer.class.getMethod("pushAudio", byte[].class, long.class).invoke(server, data, ptsUs);
    }
    static void disabledAudioAndVideoCompatibility() throws Exception {
        RtspServer server = server(false);
        try (Peer p = new Peer(server)) {
            check(p.request("OPTIONS", "", "").headers.get("server").contains("OldPhoneCam"), "retain LAN discovery fingerprint");
            check(!p.request("DESCRIBE", "", "").body.contains("m=audio"), "microphone-off SDP must be video-only");
            check(p.request("SETUP", "/trackID=1", "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n").status == 404,
                    "disabled audio track must not SETUP as video");
            p.setupTcp(0, 4, 7);
            Response play = p.request("PLAY", "", "");
            check(play.headers.get("rtp-info").contains("/trackID=0"), "video RTP-Info URL");
            server.pushFrame(VIDEO, System.nanoTime() / 1000, false);
            Packet packet = p.packet(4);
            check((packet.data[1] & 127) == 96, "original video payload type");
            check(Arrays.equals(Arrays.copyOfRange(packet.data, 12, packet.data.length), new byte[]{0x61, 11, 22}), "original video NAL");
        } finally { server.stop(); }
    }
    static void tcpTracks(boolean audioFirst) throws Exception {
        RtspServer server = server(true);
        try (Peer p = new Peer(server)) {
            String sdp = p.request("DESCRIBE", "", "").body;
            check(sdp.contains("m=audio 0 RTP/AVP 97\r\n"), "audio payload section");
            check(sdp.contains("MPEG4-GENERIC/16000/1"), "audio sample clock/channels");
            check(sdp.contains("config=1408") && sdp.contains("SizeLength=13") && sdp.contains("IndexLength=3"), "AAC-HBR config");
            if (audioFirst) { p.setupTcp(1, 8, 11); p.setupTcp(0, 4, 7); }
            else { p.setupTcp(0, 4, 7); p.setupTcp(1, 8, 11); }
            check(p.request("SETUP", "/trackID=1", "Transport: RTP/AVP/TCP;unicast;interleaved=4-5\r\n").status == 461,
                    "overlapping track channels rejected without destroying configured track");
            Response play = p.request("PLAY", "", "");
            String info = play.headers.get("rtp-info");
            check(info.contains(p.base + "/trackID=0") && info.contains(p.base + "/trackID=1"), "RTP-Info lists exact SETUP URLs");
            Map<Integer, Integer> firstSequences = sequences(info);
            long pts = System.nanoTime() / 1000;
            audio(server, AAC, pts);
            Thread.sleep(100); // Different encoder arrival delays must not alter shared capture time.
            server.pushFrame(VIDEO, pts, false);
            Packet a = p.packet(8), v = p.packet(4);
            check((a.data[1] & 127) == 97 && (v.data[1] & 127) == 96, "independent payload types");
            check(u16(a.data, 2) == firstSequences.get(1) && u16(v.data, 2) == firstSequences.get(0), "RTP-Info matches first packet sequences");
            check(u32(a.data, 8) != u32(v.data, 8), "distinct SSRC per track");
            check((a.data[1] & 128) != 0 && u16(a.data, 12) == 16 && (u16(a.data, 14) >> 3) == AAC.length, "RFC3640 marker and AU length");
            check(Arrays.equals(Arrays.copyOfRange(a.data, 16, a.data.length), AAC), "raw AAC copied without ADTS");
            audio(server, AAC, pts + 64000);
            Packet a2 = p.packet(8);
            check((u32(a2.data, 4) - u32(a.data, 4) & 0xffffffffL) == 1024, "AAC clock uses sample rate");
            check((u16(a2.data, 2) - u16(a.data, 2) & 65535) == 1, "audio sequence isolated from video");
            Packet asr = p.packet(11), vsr = p.packet(7);
            check((asr.data[1] & 255) == 200 && (vsr.data[1] & 255) == 200, "per-track sender report");
            check(u32(asr.data, 4) == u32(a.data, 8) && u32(vsr.data, 4) == u32(v.data, 8), "SR SSRC");
            check(u32(asr.data, 8) == u32(vsr.data, 8) && u32(asr.data, 12) == u32(vsr.data, 12), "SR tracks use same NTP sample");
            double videoDelta = signedDelta(u32(v.data, 4), u32(vsr.data, 16)) / 90000d;
            double audioDelta = signedDelta(u32(a.data, 4), u32(asr.data, 16)) / 16000d;
            check(Math.abs(videoDelta - audioDelta) < 0.0002, "same capture PTS maps to same wall clock despite send order");
            check(asr.data.length > 28 && asr.data[29] == (byte) 202, "compound SR carries SDES");
            check(cname(asr.data).equals(cname(vsr.data)), "common CNAME associates audio and video");
            Packet counted;
            do { counted = p.packet(11); } while (u32(counted.data, 20) == 0);
            check(u32(counted.data, 20) == 2 && u32(counted.data, 24) == 2 * (4 + AAC.length),
                    "SR counts RTP packets and payload octets only");

            byte[] large = new byte[3000]; Arrays.fill(large, (byte) 61);
            audio(server, large, pts + 128000);
            int received = 0, packets = 0;
            while (received < large.length) {
                Packet fragment = p.packet(8);
                check((u16(fragment.data, 14) >> 3) == large.length, "fragment AU size remains whole AU");
                received += fragment.data.length - 16; packets++;
                check(((fragment.data[1] & 128) != 0) == (received == large.length), "only final AAC fragment marked");
            }
            check(packets > 1 && received == large.length, "AAC fragmentation preserves full access unit");
        } finally { server.stop(); }
    }
    static void audioOnlyAndPauseResume() throws Exception {
        RtspServer server = server(true);
        try (Peer p = new Peer(server)) {
            p.setupTcp(1, 2, 3);
            String info = p.request("PLAY", "", "").headers.get("rtp-info");
            check(info.contains("trackID=1") && !info.contains("trackID=0"), "audio-only setup must not advertise an unconfigured video RTP-Info");
            audio(server, AAC, System.nanoTime() / 1000);
            p.packet(2);
            p.request("PAUSE", "", "");
            p.request("PLAY", "", "");
            audio(server, AAC, System.nanoTime() / 1000);
            p.packet(2);
            p.request("TEARDOWN", "", "");
            try (Peer video = new Peer(server)) {
                video.setupTcp(0, 0, 1);
                String videoInfo = video.request("PLAY", "", "").headers.get("rtp-info");
                check(!videoInfo.contains("trackID=1"), "video-only client still works when microphone enabled");
                audio(server, AAC, System.nanoTime() / 1000);
                server.pushFrame(VIDEO, System.nanoTime() / 1000, false);
                video.packet(0);
            }
        } finally { server.stop(); }
    }
    static void udpTracks() throws Exception {
        RtspServer server = server(true);
        try (Peer p = new Peer(server);
             DatagramSocket vr = udp(); DatagramSocket vc = udp(); DatagramSocket ar = udp(); DatagramSocket ac = udp()) {
            Response va = p.setupUdp(0, vr.getLocalPort(), vc.getLocalPort());
            Response aa = p.request("SETUP", "/trackID=1",
                    "Transport: RTP/AVP/UDP;unicast;client_port=" + ar.getLocalPort()
                            + "-" + ac.getLocalPort() + "\r\n");
            check(aa.status == 200, "explicit RTP/AVP/UDP transport used by FFmpeg");
            check(!va.headers.get("transport").equals(aa.headers.get("transport")), "UDP tracks have independent ports");
            p.request("PLAY", "", "");
            long pts = System.nanoTime() / 1000;
            audio(server, AAC, pts); server.pushFrame(VIDEO, pts, false);
            check((receive(vr)[1] & 127) == 96 && (receive(ar)[1] & 127) == 97, "UDP payloads delivered to each requested port");
            check((receive(vc)[1] & 255) == 200 && (receive(ac)[1] & 255) == 200, "UDP sender reports use each RTCP port");
            server.stop();
            assertPortsReleased(va); assertPortsReleased(aa);
        } finally { server.stop(); }
    }
    static void assertPortsReleased(Response setup) throws Exception {
        String ports = setup.headers.get("transport").split("server_port=")[1].split(";")[0];
        for (String port : ports.split("-")) {
            try (DatagramSocket released = new DatagramSocket(Integer.parseInt(port))) {
                check(released.isBound(), "stop releases each RTP/RTCP server UDP socket");
            }
        }
    }
    static void restartDisposesTracks() throws Exception {
        RtspServer server = server(true);
        try {
            for (int i = 0; i < 8; i++) {
                try (Peer p = new Peer(server)) {
                    p.setupTcp(0, 0, 1); p.setupTcp(1, 2, 3); p.request("PLAY", "", "");
                    audio(server, AAC, System.nanoTime() / 1000); p.packet(2);
                    server.stop();
                    check(server.clientCount() == 0, "stop disposes all clients");
                }
                server.start();
            }
        } finally { server.stop(); }
    }
    static String cname(byte[] p) {
        int length = p[37] & 255;
        return new String(p, 38, length, StandardCharsets.UTF_8);
    }
    static Map<Integer, Integer> sequences(String info) {
        Map<Integer, Integer> result = new HashMap<Integer, Integer>();
        for (String entry : info.split(",")) {
            int track = entry.contains("trackID=0") ? 0 : 1;
            for (String field : entry.split(";")) if (field.startsWith("seq=")) result.put(track, Integer.parseInt(field.substring(4)));
        }
        return result;
    }
    static DatagramSocket udp() throws Exception { DatagramSocket s = new DatagramSocket(0); s.setSoTimeout(3000); return s; }
    static byte[] receive(DatagramSocket socket) throws Exception {
        DatagramPacket p = new DatagramPacket(new byte[2000], 2000); socket.receive(p);
        return Arrays.copyOf(p.getData(), p.getLength());
    }
    static int u16(byte[] p, int o) { return (p[o] & 255) << 8 | p[o+1] & 255; }
    static long u32(byte[] p, int o) { return ((long)u16(p,o) << 16) | u16(p,o+2); }
    static long signedDelta(long a, long b) { return (int)(a-b); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.write(c);
        if (c == -1) throw new EOFException();
        return b.toString("UTF-8");
    }
    static byte[] bytes(InputStream in, int size) throws Exception {
        byte[] data = new byte[size]; int at = 0;
        while (at < size) { int count = in.read(data, at, size-at); if (count < 0) throw new EOFException(); at += count; }
        return data;
    }
    static final class Response { int status; Map<String,String> headers = new HashMap<String,String>(); String body; }
    static final class Packet { int channel; byte[] data; Packet(int c, byte[] d) { channel=c; data=d; } }
    static final class Peer implements AutoCloseable {
        final Socket socket; final PushbackInputStream in; final String base;
        final List<Packet> pending = new ArrayList<Packet>(); int cseq;
        Peer(RtspServer server) throws Exception {
            socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(3000);
            in = new PushbackInputStream(socket.getInputStream()); base = "rtsp://127.0.0.1:" + server.getPort() + "/live";
        }
        Response request(String method, String suffix, String headers) throws Exception {
            socket.getOutputStream().write((method+" "+base+suffix+" RTSP/1.0\r\nCSeq: "+(++cseq)+"\r\n"+headers+"\r\n").getBytes(StandardCharsets.UTF_8));
            int first;
            while ((first=in.read()) == '$') pending.add(readPacket());
            if (first == -1) throw new EOFException();
            in.unread(first);
            Response response = new Response(); response.status = Integer.parseInt(line(in).split(" ")[1]);
            String h; while (!(h=line(in)).isEmpty()) { int sep=h.indexOf(':'); response.headers.put(h.substring(0,sep).toLowerCase(Locale.US), h.substring(sep+1).trim()); }
            response.body = new String(bytes(in, Integer.parseInt(response.headers.getOrDefault("content-length", "0"))), StandardCharsets.UTF_8);
            return response;
        }
        void setupTcp(int track, int rtp, int rtcp) throws Exception {
            Response r = request("SETUP", "/trackID="+track, "Transport: RTP/AVP/TCP;unicast;interleaved="+rtp+"-"+rtcp+"\r\n");
            check(r.status == 200 && r.headers.get("transport").contains("interleaved="+rtp+"-"+rtcp), "honor exact requested TCP channel pair");
        }
        Response setupUdp(int track, int rtp, int rtcp) throws Exception {
            Response r = request("SETUP", "/trackID="+track, "Transport: RTP/AVP;unicast;client_port="+rtp+"-"+rtcp+"\r\n");
            check(r.status == 200, "UDP SETUP"); return r;
        }
        Packet packet(int channel) throws Exception {
            for (int i=0; i<pending.size(); i++) if (pending.get(i).channel == channel) return pending.remove(i);
            for (int i=0; i<50; i++) {
                check(in.read() == '$', "expected interleaved media"); Packet packet = readPacket();
                if (packet.channel == channel) return packet; pending.add(packet);
            }
            throw new AssertionError("requested channel not received");
        }
        Packet readPacket() throws Exception { int channel=in.read(), high=in.read(), low=in.read(); return new Packet(channel, bytes(in, high*256+low)); }
        public void close() throws IOException { socket.close(); }
    }
}
