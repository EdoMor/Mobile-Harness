package com.jarves.mh.adb;

import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
 * <p>{@code hold} turns the panel off and re-applies that every few seconds, because some
 * phones power it back on after a rotation or a display-mode change. It prints a line each
 * time; once the adb connection is gone that write fails, and it turns the panel back on and
 * exits, so a lost connection never leaves the phone with a dark panel.
 *
 * <p>Uses only the Android framework: no Kotlin and no app classes, because it runs outside
 * the app's process.
 */
public final class ScreenPowerMain {
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;
    private static final long HOLD_INTERVAL_MS = 3_000L;

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
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            System.err.println("screen power failed: " + cause);
            System.exit(1);
            return;
        }
        System.exit(0);
    }

    private static void hold() throws Exception {
        // Fail fast, before reporting anything, if this Android version blocks the call.
        setPowerMode(POWER_MODE_OFF);
        while (true) {
            System.out.println("holding");
            if (System.out.checkError()) {
                setPowerMode(POWER_MODE_NORMAL);
                return;
            }
            Thread.sleep(HOLD_INTERVAL_MS);
            setPowerMode(POWER_MODE_OFF);
        }
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
        // DisplayControl; older versions still have them on SurfaceControl.
        Class<?> owner = Build.VERSION.SDK_INT >= 34 ? loadDisplayControl() : surfaceControl;
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

    private static Class<?> loadDisplayControl() throws Exception {
        Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method createClassLoader = factory.getDeclaredMethod(
                "createClassLoader",
                String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class);
        ClassLoader loader = (ClassLoader) createClassLoader.invoke(
                null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> displayControl = loader.loadClass("com.android.server.display.DisplayControl");
        Method loadLibrary = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        loadLibrary.setAccessible(true);
        loadLibrary.invoke(Runtime.getRuntime(), displayControl, "android_servers");
        return displayControl;
    }
}
