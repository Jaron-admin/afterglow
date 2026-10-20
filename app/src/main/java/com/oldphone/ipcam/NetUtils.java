package com.oldphone.ipcam;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 取本机局域网 IPv4。优先 wlan / eth 接口，避开蜂窝网和 VPN。
 */
public final class NetUtils {

    private NetUtils() {
    }

    public static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) {
                        continue;
                    }
                    if (name.startsWith("wlan") || name.startsWith("eth")
                            || name.startsWith("ap") || name.startsWith("rndis")) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback == null ? "127.0.0.1" : fallback;
    }
}
