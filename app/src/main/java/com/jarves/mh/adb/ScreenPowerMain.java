package com.jarves.mh.adb;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Powers the phone's display panel off or on without putting the phone to sleep.
 *
 * <p>Runs as Android's shell user through Wireless ADB, never inside the app:
 * {@code CLASSPATH=<this APK> app_process /system/bin com.jarves.mh.adb.ScreenPowerMain off|on|hold}.
 * It sets the panel's power mode through SurfaceControl, the same call scrcpy uses for its
 * "turn screen off" option. Android still considers the screen on, so the phone stays awake
 * and unlocked, apps keep running, and screenshots and taps keep working, while an OLED panel
 * shows nothing and cannot burn in.
 *
 * <p>{@code hold} keeps the panel off for as long as it runs. Some phones re-light the panel
 * on their own, for example when the refresh rate drops after the screen content stops
 * changing, or when the backlight level changes. SurfaceFlinger does not see that, still
 * believes the panel is off and ignores a repeated "off". So {@code hold} listens for display
 * changes and answers each burst with a quick on-then-off, which makes SurfaceFlinger send the
 * off command for real. It logs what it sees, prints a heartbeat each second, and once the adb
 * connection is gone (the write fails) it turns the panel back on and exits, so a lost
 * connection never leaves the phone with a dark panel.
 *
 * <p>Uses only the Android framework: no Kotlin and no app classes, because it runs outside
 * the app's process.
 */
public final class ScreenPowerMain {
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;
    private static final long TICK_MS = 250L;
    private static final long HEARTBEAT_MS = 1_000L;
    /** Wait for a burst of display events to settle before correcting once. */
    private static final long FORCE_DEBOUNCE_MS = 150L;
    /** At most one correction this often, so a long brightness ramp cannot cause flicker. */
    private static final long FORCE_MIN_INTERVAL_MS = 1_500L;
    /** Our own on-then-off may echo back as a display event; ignore events this long after it. */
    private static final long SELF_EVENT_IGNORE_MS = 500L;
    /** DisplayManager.EVENT_FLAG_DISPLAY_CHANGED | EVENT_FLAG_DISPLAY_BRIGHTNESS. */
    private static final long DISPLAY_EVENTS = (1L << 2) | (1L << 3);

    private static final long STARTED_AT = SystemClock.uptimeMillis();
    private static volatile long forceAt = 0L;
    private static volatile long ignoreEventsUntil = 0L;

    private ScreenPowerMain() {
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "";
        try {
            switch (command) {
                case "off":
                    setPowerMode(POWER_MODE_OFF);
                    System.out.println("screen off");
                    break;
                case "on":
                    setPowerMode(POWER_MODE_NORMAL);
                    System.out.println("screen on");
                    break;
                case "hold":
                    hold();
                    break;
                default:
                    System.err.println("usage: ScreenPowerMain off|on|hold");
                    System.exit(2);
                    return;
            }
        } catch (Throwable error) {
            System.err.println("screen power failed: " + rootCause(error));
            System.exit(1);
            return;
        }
        System.exit(0);
    }

    private static void hold() throws Exception {
        // Fail fast, before reporting anything, if this Android version blocks the call.
        setPowerMode(POWER_MODE_OFF);
        log("panel off on " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        System.out.println("holding");
        watchDisplayEvents();
        long lastHeartbeat = SystemClock.uptimeMillis();
        long lastForce = 0L;
        while (true) {
            Thread.sleep(TICK_MS);
            long now = SystemClock.uptimeMillis();
            long due = forceAt;
            if (due != 0L && now >= due && now - lastForce >= FORCE_MIN_INTERVAL_MS) {
                forceAt = 0L;
                lastForce = now;
                ignoreEventsUntil = now + SELF_EVENT_IGNORE_MS;
                setPowerMode(POWER_MODE_NORMAL);
                setPowerMode(POWER_MODE_OFF);
                log("panel forced off again after a display change");
            }
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now;
                setPowerMode(POWER_MODE_OFF);
                System.out.println("holding");
                if (System.out.checkError()) {
                    setPowerMode(POWER_MODE_NORMAL);
                    return;
                }
            }
        }
    }

    /** Asks Android to report display changes (mode, refresh rate, state, brightness) to {@link #hold}. */
    private static void watchDisplayEvents() {
        try {
            HandlerThread thread = new HandlerThread("display-events");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            final Object global = globalClass.getDeclaredMethod("getInstance").invoke(null);
            Class<?> listenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            InvocationHandler events = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("onDisplayChanged".equals(name)) {
                        long now = SystemClock.uptimeMillis();
                        if (now >= ignoreEventsUntil) {
                            forceAt = now + FORCE_DEBOUNCE_MS;
                            int displayId = (Integer) args[0];
                            log("display " + displayId + " changed" + describe(global, displayId));
                        }
                        return null;
                    }
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    if ("toString".equals(name)) return "ScreenPowerDisplayListener";
                    return null;
                }
            };
            Object listener = Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(), new Class<?>[] {listenerClass}, events);
            try {
                globalClass.getMethod("registerDisplayListener", listenerClass, Handler.class, long.class, String.class)
                        .invoke(global, listener, handler, DISPLAY_EVENTS, "com.android.shell");
            } catch (NoSuchMethodException withoutPackage) {
                try {
                    globalClass.getMethod("registerDisplayListener", listenerClass, Handler.class, long.class)
                            .invoke(global, listener, handler, DISPLAY_EVENTS);
                } catch (NoSuchMethodException withoutMask) {
                    globalClass.getMethod("registerDisplayListener", listenerClass, Handler.class)
                            .invoke(global, listener, handler);
                }
            }
            log("watching display changes" + describe(global, 0));
        } catch (Throwable error) {
            log("cannot watch display changes: " + rootCause(error));
        }
    }

    /** State, mode, refresh rate and brightness of a display, for the log; empty if unavailable. */
    private static String describe(Object global, int displayId) {
        StringBuilder text = new StringBuilder();
        try {
            Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, displayId);
            if (info != null) {
                try {
                    text.append(" state=").append(info.getClass().getField("state").getInt(info));
                } catch (Throwable ignored) {
                    // Field renamed on this Android version.
                }
                try {
                    text.append(" mode=").append(info.getClass().getField("modeId").getInt(info));
                } catch (Throwable ignored) {
                    // Field renamed on this Android version.
                }
                try {
                    text.append(" refresh=").append(info.getClass().getMethod("getRefreshRate").invoke(info));
                } catch (Throwable ignored) {
                    // Method missing on this Android version.
                }
            }
        } catch (Throwable ignored) {
            // No display info; the event itself is still logged.
        }
        try {
            text.append(" brightness=").append(global.getClass().getMethod("getBrightness", int.class).invoke(global, displayId));
        } catch (Throwable ignored) {
            // getBrightness exists from Android 12.
        }
        return text.toString();
    }

    private static synchronized void log(String message) {
        double seconds = (SystemClock.uptimeMillis() - STARTED_AT) / 1000.0;
        System.out.println(String.format(Locale.ROOT, "%.1fs %s", seconds, message));
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return String.valueOf(cause);
    }

    private static void setPowerMode(int mode) throws Exception {
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        Method setDisplayPowerMode = surfaceControl.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        List<IBinder> tokens = physicalDisplayTokens(surfaceControl);
        if (tokens.isEmpty()) throw new IllegalStateException("no physical display found");
        for (IBinder token : tokens) {
            setDisplayPowerMode.invoke(null, token, mode);
        }
    }

    private static List<IBinder> physicalDisplayTokens(Class<?> surfaceControl) throws Exception {
        // Android 14 moved the display-id calls out of SurfaceControl into the system server's
        // DisplayControl; older versions (and some Android 14 builds) still have them on SurfaceControl.
        Class<?> owner = surfaceControl;
        if (Build.VERSION.SDK_INT >= 34 && !hasMethod(surfaceControl, "getPhysicalDisplayIds")) {
            owner = loadDisplayControl();
        }
        long[] ids = (long[]) owner.getMethod("getPhysicalDisplayIds").invoke(null);
        Method getToken = owner.getMethod("getPhysicalDisplayToken", long.class);
        List<IBinder> tokens = new ArrayList<>();
        if (ids != null) {
            for (long id : ids) {
                IBinder token = (IBinder) getToken.invoke(null, id);
                if (token != null) tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static Class<?> displayControl;

    private static synchronized Class<?> loadDisplayControl() throws Exception {
        if (displayControl != null) return displayControl;
        Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method createClassLoader = factory.getDeclaredMethod(
                "createClassLoader",
                String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class);
        ClassLoader loader = (ClassLoader) createClassLoader.invoke(
                null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> loaded = loader.loadClass("com.android.server.display.DisplayControl");
        Method loadLibrary = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        loadLibrary.setAccessible(true);
        loadLibrary.invoke(Runtime.getRuntime(), loaded, "android_servers");
        displayControl = loaded;
        return loaded;
    }
}
