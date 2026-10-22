package com.oldphone.ipcam;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enumerate usable local IPv4 addresses. Interface kinds are name-based hints,
 * not a guarantee that another device can reach an address.
 */
public final class NetUtils {
    /** How long a snapshot is trusted before the next read re-enumerates. */
    private static final long ADDRESS_TTL_NANOS = 4000L * 1000000L;

    private static volatile List<Address> cachedAddresses;
    private static volatile long cachedAtNanos;

    public static final int WIFI = 0;
    public static final int ETHERNET = 1;
    public static final int HOTSPOT = 2;
    public static final int USB = 3;
    public static final int VPN = 4;
    public static final int MOBILE = 5;
    public static final int OTHER = 6;

    public static final class Address {
        public final String ip;
        public final String interfaceName;
        public final int kind;

        private Address(String ip, String interfaceName) {
            this.ip = ip;
            this.interfaceName = interfaceName;
            this.kind = classify(interfaceName);
        }

        @Override public boolean equals(Object value) {
            if (!(value instanceof Address)) return false;
            Address other = (Address) value;
            return kind == other.kind && ip.equals(other.ip) && interfaceName.equals(other.interfaceName);
        }

        @Override public int hashCode() {
            return 31 * (31 * ip.hashCode() + interfaceName.hashCode()) + kind;
        }
    }

    /** Snapshot boundary keeps address filtering independent of live OS interfaces. */
    static final class CapturedInterface {
        final String name;
        final boolean up;
        final boolean loopback;
        final String[] addresses;

        CapturedInterface(String name, boolean up, boolean loopback, String[] addresses) {
            this.name = name == null ? "" : name;
            this.up = up;
            this.loopback = loopback;
            this.addresses = addresses;
        }
    }

    private NetUtils() {
    }

    public static String localIp() {
        List<Address> addresses = localAddresses();
        return addresses.isEmpty() ? "127.0.0.1" : addresses.get(0).ip;
    }

    /** Empty means no usable interface; callers must not invent a remote URL. */
    public static List<Address> localAddresses() {
        List<Address> snapshot = cachedAddresses;
        if (snapshot != null && System.nanoTime() - cachedAtNanos < ADDRESS_TTL_NANOS) {
            return snapshot;
        }
        return refreshAddresses();
    }

    /**
     * Re-enumerates and replaces the snapshot. Enumeration walks netlink and can take tens
     * of milliseconds on an old phone, so the result is memoised: the screens read this
     * once a second and must never block the frame on it.
     */
    public static synchronized List<Address> refreshAddresses() {
        List<Address> fresh = enumerate();
        cachedAddresses = fresh;
        cachedAtNanos = System.nanoTime();
        return fresh;
    }

    /** Warms the snapshot off the UI thread so the first paint never waits on netlink. */
    public static void warmAddresses() {
        if (cachedAddresses != null) return;
        new Thread(new Runnable() {
            @Override public void run() { refreshAddresses(); }
        }, "afterglow-net-warm").start();
    }

    private static List<Address> enumerate() {
        List<CapturedInterface> captured = new ArrayList<CapturedInterface>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni == null) continue;
                try {
                    boolean up = ni.isUp(), loopback = ni.isLoopback();
                    List<String> ips = new ArrayList<String>();
                    if (up && !loopback) {
                        Enumeration<InetAddress> addresses = ni.getInetAddresses();
                        while (addresses != null && addresses.hasMoreElements()) {
                            InetAddress address = addresses.nextElement();
                            if (address != null) ips.add(address.getHostAddress());
                        }
                    }
                    captured.add(new CapturedInterface(ni.getName(), up, loopback,
                            ips.toArray(new String[ips.size()])));
                } catch (SocketException unavailable) {
                    // An interface can disappear while a VPN or Wi-Fi connection changes.
                } catch (SecurityException unavailable) {
                    // Keep the other interfaces if this one cannot be inspected.
                }
            }
        } catch (SocketException unavailable) {
            // A later foreground refresh can retry enumeration.
        } catch (SecurityException unavailable) {
            // No additional privileges are required or requested for address display.
        }
        return normalize(captured);
    }

    static List<Address> normalize(List<CapturedInterface> captured) {
        List<Address> result = new ArrayList<Address>();
        Set<String> seen = new HashSet<String>();
        if (captured != null) for (CapturedInterface ni : captured) {
            if (ni == null || !ni.up || ni.loopback || ni.addresses == null) continue;
            for (String value : ni.addresses) {
                long number = ipv4(value);
                if (number < 0) continue;
                int first = (int) (number >>> 24), second = (int) ((number >>> 16) & 255);
                if (first == 0 || first == 127 || first >= 224 || (first == 169 && second == 254)) continue;
                String ip = first + "." + second + "." + ((number >>> 8) & 255) + "." + (number & 255);
                if (seen.add(ni.name + '\u0000' + ip)) result.add(new Address(ip, ni.name));
            }
        }
        Collections.sort(result, new Comparator<Address>() {
            @Override public int compare(Address left, Address right) {
                int order = rank(left.kind) - rank(right.kind);
                if (order == 0) order = left.interfaceName.compareToIgnoreCase(right.interfaceName);
                if (order == 0) order = left.interfaceName.compareTo(right.interfaceName);
                if (order != 0) return order;
                long a = ipv4(left.ip), b = ipv4(right.ip);
                return a < b ? -1 : a == b ? 0 : 1;
            }
        });
        return Collections.unmodifiableList(result);
    }

    private static int classify(String interfaceName) {
        String name = interfaceName.toLowerCase(Locale.US);
        if (name.startsWith("wlan") || name.startsWith("wifi")) return WIFI;
        if (name.startsWith("eth") || name.startsWith("en")) return ETHERNET;
        if (name.startsWith("ap")) return HOTSPOT;
        if (name.startsWith("rndis") || name.startsWith("usb")) return USB;
        if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("wg")
                || name.startsWith("ppp") || name.startsWith("tailscale")) return VPN;
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                || name.startsWith("cell") || name.startsWith("wwan") || name.startsWith("ccemni")
                || name.startsWith("v4-rmnet") || name.startsWith("v4-ccmni")) return MOBILE;
        return OTHER;
    }

    private static int rank(int kind) {
        return kind == OTHER ? 4 : kind == VPN ? 5 : kind == MOBILE ? 6 : kind;
    }

    /** Strict numeric parsing avoids DNS lookup for invalid or non-IPv4 input. */
    private static long ipv4(String value) {
        if (value == null) return -1;
        long result = 0;
        int octet = 0, digits = 0, dots = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (digits == 0 || ++dots > 3) return -1;
                result = (result << 8) | octet;
                octet = 0;
                digits = 0;
            } else {
                if (c < '0' || c > '9' || ++digits > 3) return -1;
                octet = octet * 10 + c - '0';
                if (octet > 255) return -1;
            }
        }
        return dots == 3 && digits > 0 ? (result << 8) | octet : -1;
    }
}
