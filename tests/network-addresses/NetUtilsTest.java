package com.oldphone.ipcam;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Captured interface fixtures exercise the same normalization used by live enumeration. */
public final class NetUtilsTest {
    private static Class<?> captured;
    private static Constructor<?> capture;
    private static Method normalize;
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            NetUtils.class.getMethod("localAddresses");
            captured = Class.forName("com.oldphone.ipcam.NetUtils$CapturedInterface");
            capture = captured.getDeclaredConstructor(String.class, boolean.class, boolean.class, String[].class);
            capture.setAccessible(true);
            normalize = NetUtils.class.getDeclaredMethod("normalize", List.class);
            normalize.setAccessible(true);
        } catch (ReflectiveOperationException absent) {
            throw new AssertionError("All-address discovery is missing: LAN and VPN addresses must both be offered", absent);
        }
        vpnFirstDoesNotHideBothLanRanges();
        interfaceProvenanceSurvivesDuplicates();
        outputOrderDoesNotDependOnEnumeration();
        vpnOnlyRemainsAvailable();
        disconnectedDoesNotInventLoopback();
        excludesUnusableAddressesAndInterfaces();
        labelsUseInterfaceRatherThanAddressRange();
        namesAreLocaleIndependent();
        snapshotsCompareByContent();
        liveEnumerationUsesTheSameContract();
        System.out.println("NetUtilsTest: " + checks + " checks passed");
    }

    private static void vpnFirstDoesNotHideBothLanRanges() throws Exception {
        List<?> result = addresses(
                entry("tun0", "100.80.1.2"), entry("wlan0", "10.20.30.40", "192.168.1.6"));
        equal(Arrays.asList("wlan0=10.20.30.40", "wlan0=192.168.1.6", "tun0=100.80.1.2"), keys(result));
    }

    private static void interfaceProvenanceSurvivesDuplicates() throws Exception {
        List<?> result = addresses(entry("wlan0", "10.0.0.2", "10.0.0.2"), entry("tun0", "10.0.0.2"));
        equal(Arrays.asList("wlan0=10.0.0.2", "tun0=10.0.0.2"), keys(result));
    }

    private static void outputOrderDoesNotDependOnEnumeration() throws Exception {
        List<Object> input = new ArrayList<Object>(Arrays.asList(
                entry("rmnet0", "10.0.0.5"), entry("tun0", "10.0.0.6"),
                entry("custom0", "10.0.0.7"), entry("rndis0", "10.0.0.4"),
                entry("ap0", "10.0.0.3"), entry("eth0", "10.0.0.2"),
                entry("wlan1", "192.168.0.2"), entry("wlan0", "10.0.0.10", "10.0.0.2")));
        List<String> expected = Arrays.asList("wlan0=10.0.0.2", "wlan0=10.0.0.10", "wlan1=192.168.0.2",
                "eth0=10.0.0.2", "ap0=10.0.0.3", "rndis0=10.0.0.4", "custom0=10.0.0.7",
                "tun0=10.0.0.6", "rmnet0=10.0.0.5");
        equal(expected, keys(normalized(input)));
        Collections.reverse(input);
        equal(expected, keys(normalized(input)));
    }

    private static void vpnOnlyRemainsAvailable() throws Exception {
        List<?> result = addresses(entry("tun0", "10.8.0.2"));
        equal(Arrays.asList("tun0=10.8.0.2"), keys(result));
        equal(kind("VPN"), result.get(0).getClass().getField("kind").get(result.get(0)));
    }

    private static void disconnectedDoesNotInventLoopback() throws Exception {
        equal(Collections.emptyList(), addresses());
        equal(Collections.emptyList(), normalized(null));
        equal(Collections.emptyList(), addresses(capture.newInstance("wlan0", false, false, new String[]{"10.0.0.2"})));
    }

    private static void excludesUnusableAddressesAndInterfaces() throws Exception {
        List<?> result = addresses(
                capture.newInstance("lo", true, true, new String[]{"10.1.1.1"}),
                capture.newInstance("eth0", false, false, new String[]{"192.168.1.10"}),
                entry("wlan0", "127.0.0.1", "127.3.4.5", "169.254.0.2", "0.0.0.0", "224.0.0.1",
                        "239.255.255.250", "255.255.255.255", "::1", "fe80::1", "::ffff:10.0.0.2",
                        "256.1.1.1", "1.2.3", "host.example", "1.2.3.4:8080", "", null, "10.0.0.2"));
        equal(Arrays.asList("wlan0=10.0.0.2"), keys(result));
    }

    private static void labelsUseInterfaceRatherThanAddressRange() throws Exception {
        String[][] cases = {{"wlan0", "WIFI"}, {"wifi0", "WIFI"}, {"eth0", "ETHERNET"}, {"en0", "ETHERNET"},
                {"ap0", "HOTSPOT"}, {"rndis0", "USB"}, {"usb0", "USB"}, {"tun0", "VPN"}, {"tap0", "VPN"},
                {"wg0", "VPN"}, {"ppp0", "VPN"}, {"tailscale0", "VPN"}, {"rmnet_data0", "MOBILE"},
                {"ccmni0", "MOBILE"}, {"pdp0", "MOBILE"}, {"cell0", "MOBILE"}, {"custom0", "OTHER"}};
        for (String[] item : cases) {
            for (String ip : new String[]{"10.0.0.2", "192.168.1.2", "100.80.1.2"}) {
                Object address = addresses(entry(item[0], ip)).get(0);
                equal(kind(item[1]), address.getClass().getField("kind").get(address));
            }
        }
    }

    private static void namesAreLocaleIndependent() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Object address = addresses(entry("WIFI0", "10.0.0.2")).get(0);
            equal(kind("WIFI"), address.getClass().getField("kind").get(address));
        } finally { Locale.setDefault(previous); }
    }

    private static void snapshotsCompareByContent() throws Exception {
        List<?> first = addresses(entry("wlan0", "10.0.0.2"));
        List<?> same = addresses(entry("wlan0", "10.0.0.2"));
        equal(first, same);
        equal(first.hashCode(), same.hashCode());
        check(!first.equals(addresses(entry("wlan0", "10.0.0.3"))), "IP replacement must change the UI snapshot");
        check(!first.equals(addresses(entry("tun0", "10.0.0.2"))), "Interface replacement must change the UI snapshot");
        try { first.clear(); throw new AssertionError("Address snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }

    private static void liveEnumerationUsesTheSameContract() throws Exception {
        List<?> result = (List<?>) NetUtils.class.getMethod("localAddresses").invoke(null);
        List<Object> snapshots = new ArrayList<Object>();
        for (Object address : result) {
            snapshots.add(entry((String) address.getClass().getField("interfaceName").get(address),
                    (String) address.getClass().getField("ip").get(address)));
        }
        equal(result, normalized(snapshots));
    }

    private static Object entry(String name, String... ips) throws Exception {
        return capture.newInstance(name, true, false, ips);
    }
    private static List<?> addresses(Object... input) throws Exception { return normalized(Arrays.asList(input)); }
    private static List<?> normalized(List<?> input) throws Exception { return (List<?>) normalize.invoke(null, input); }
    private static Object kind(String name) throws Exception { return NetUtils.class.getField(name).get(null); }
    private static List<String> keys(List<?> addresses) throws Exception {
        List<String> result = new ArrayList<String>();
        for (Object address : addresses) result.add(address.getClass().getField("interfaceName").get(address)
                + "=" + address.getClass().getField("ip").get(address));
        return result;
    }
    private static void equal(Object expected, Object actual) {
        check(expected.equals(actual), "expected " + expected + " but was " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
