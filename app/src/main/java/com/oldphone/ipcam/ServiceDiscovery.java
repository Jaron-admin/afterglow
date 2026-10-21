package com.oldphone.ipcam;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.IdentityHashMap;
import java.util.Map;

/** Publishes the HTTP API through Android DNS-SD; the instance name is not a host name. */
public final class ServiceDiscovery {
    public static final String SERVICE_TYPE = "_afterglow._tcp.";
    private static final String TAG = "ServiceDiscovery";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NsdManager manager;
    private final NsdRegistration registration;
    private final Map<NsdRegistration.Listener, NsdManager.RegistrationListener> listeners =
            new IdentityHashMap<NsdRegistration.Listener, NsdManager.RegistrationListener>();
    private WifiManager.MulticastLock multicastLock;
    private String deviceId;

    public ServiceDiscovery(Context context) {
        this.context = context.getApplicationContext();
        manager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        registration = new NsdRegistration(new Backend());
    }

    /** Call only once the HTTP listener is bound; callers exclude local-recording mode. */
    public void start(final int httpPort) {
        if (httpPort < 1 || httpPort > 65535) throw new IllegalArgumentException("Invalid discovery port");
        // Persist before posting, while the service's worker thread still owns startup.
        final String id = Prefs.deviceId();
        main.post(new Runnable() {
            @Override public void run() {
                if (manager == null) {
                    Log.w(TAG, "Android network service discovery is unavailable");
                    return;
                }
                deviceId = id;
                acquireMulticastLock();
                registration.start(httpPort);
            }
        });
    }

    public void stop() {
        main.post(new Runnable() {
            @Override public void run() {
                registration.stop();
                releaseMulticastLock();
            }
        });
    }

    private void acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) return;
                multicastLock = wifi.createMulticastLock("afterglow-discovery");
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld()) multicastLock.acquire();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to enable Wi-Fi multicast reception", error);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to release Wi-Fi multicast reception", error);
        }
    }

    private final class Backend implements NsdRegistration.Backend {
        @Override public void register(int port, final NsdRegistration.Listener listener) {
            final NsdManager.RegistrationListener androidListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo info) {
                    main.post(new Runnable() { @Override public void run() { listener.registered(); } });
                }
                @Override public void onRegistrationFailed(NsdServiceInfo info, final int errorCode) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            listeners.remove(listener);
                            Log.w(TAG, "DNS-SD registration failed: " + errorCode);
                            listener.registrationFailed();
                        }
                    });
                }
                @Override public void onServiceUnregistered(NsdServiceInfo info) {
                    main.post(new Runnable() {
                        @Override public void run() { listeners.remove(listener); listener.unregistered(); }
                    });
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo info, final int errorCode) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            Log.w(TAG, "DNS-SD withdrawal failed: " + errorCode);
                            listener.unregistrationFailed();
                        }
                    });
                }
            };
            // Never reuse Android listeners: their reuse is unreliable on API 19.
            listeners.put(listener, androidListener);
            try {
                NsdServiceInfo info = new NsdServiceInfo();
                info.setServiceName("Afterglow-" + deviceId);
                info.setServiceType(SERVICE_TYPE);
                info.setPort(port);
                if (Build.VERSION.SDK_INT >= 21) Attributes.set(info, deviceId);
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, androidListener);
            } catch (RuntimeException error) {
                listeners.remove(listener);
                Log.w(TAG, "Unable to register DNS-SD service", error);
                listener.registrationFailed();
            }
        }

        @Override public void unregister(NsdRegistration.Listener listener) {
            NsdManager.RegistrationListener androidListener = listeners.get(listener);
            if (androidListener == null) { listener.unregistered(); return; }
            try {
                manager.unregisterService(androidListener);
            } catch (IllegalArgumentException alreadyGone) {
                listeners.remove(listener);
                listener.unregistered();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to withdraw DNS-SD service", error);
                listener.unregistrationFailed();
            }
        }

        @Override public void schedule(Runnable task, long delayMillis) { main.postDelayed(task, delayMillis); }
        @Override public void cancel(Runnable task) { main.removeCallbacks(task); }
    }

    /** Keep the API-21 symbol out of code executed on Dalvik/API 19. */
    @TargetApi(21)
    private static final class Attributes {
        static void set(NsdServiceInfo info, String id) {
            info.setAttribute("id", id);
            info.setAttribute("api", "1");
        }
    }
}
