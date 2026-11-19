package io.github.k8svm.hook;

import android.content.Context;
import android.os.Handler;
import hmgapi.hook.Bridge;
import hmgapi.hook.MethodHook;
import java.lang.reflect.Method;

import static io.github.k8svm.hook.SvmOverlayEntry.*;

/**
 * Camera process: while the around-view panel and split options are on, the stock split screen
 * (map 1280 = 16:9 on the left) stays open all the time, so the camera never starts with a split change.
 * 0.2.2: opened after boot (0.2.4: and again after each camera session), never closed by this module, and the camera
 * app's own "split off at camera start" request is skipped (the split change made the SVM drop out).
 * Uses com.hkmc.split.manager.SplitScreenManager (automotive.jar): isSplitScreenOn(), requestSplitScreenOn(..).
 * Display only: no vehicle request.
 */
final class SplitOpen {
    private static Object manager;
    private static Method isOn, request;
    private static Context appContext;
    private static Handler handler;
    private static boolean selfRequest;

    static boolean keep() {
        return Live.on("panel", panelStart) && Live.on("split", splitStart);
    }

    private static boolean ready(Context context) {
        if (manager != null) return true;
        try {
            Class<?> c = Class.forName("com.hkmc.split.manager.SplitScreenManager", true, context.getClassLoader());
            manager = c.getMethod("getInstance", Context.class).invoke(null, context);
            isOn = c.getMethod("isSplitScreenOn");
            request = c.getMethod("requestSplitScreenOn", boolean.class, boolean.class);
            return manager != null;
        } catch (Throwable t) {
            err("split manager unavailable", t);
            manager = null;
            return false;
        }
    }

    /** Camera process start: block the stock split-off requests and open the split after boot. */
    static void install(ClassLoader cl, Context context, Handler main) {
        appContext = context;
        handler = main;
        try {
            Class<?> c = Class.forName("com.hkmc.split.manager.SplitScreenManager", true, cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("requestSplitScreenOn")) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 0 || t[0] != boolean.class) continue;
                final Object skipped = m.getReturnType() == boolean.class ? (Object) Boolean.TRUE : null;
                if (m.getReturnType().isPrimitive() && m.getReturnType() != boolean.class && m.getReturnType() != void.class) continue;
                Bridge.hookMethod(m, new MethodHook() {
                    @Override protected void beforeHookedMethod(Param p) {
                        if (selfRequest || Boolean.TRUE.equals(p.args[0]) || !keep() || state.inReverse()) return;
                        // 0.2.3: a primitive boolean method must get a Boolean back (null crashed the camera app).
                        p.setResult(skipped);
                        log("camera app split-off request skipped (keep split on)");
                    }
                });
                n++;
            }
            log("split keep hook set (" + n + ")");
        } catch (Throwable t) {
            err("split keep hook unavailable", t);
        }
        for (long d : new long[] {20000, 45000, 90000}) {
            main.postDelayed(new Runnable() {
                @Override public void run() { ensure("boot"); }
            }, d);
        }
    }

    /** main thread: open the split screen if it is closed. */
    static void ensure(String why) {
        if (appContext == null || !keep() || !ready(appContext)) return;
        try {
            if (Boolean.TRUE.equals(isOn.invoke(manager))) return;
            selfRequest = true;
            Object r = request.invoke(manager, true, false);
            log("split screen opened (" + why + ") -> " + r);
        } catch (Throwable t) {
            err("split screen request failed", t);
        } finally {
            selfRequest = false;
        }
    }

    /**
     * main thread, called by the window code. 0.2.4: never touch the split while a camera view is up
     * (opening it during the camera made the SVM drop out 1-3 s later). When the camera ends the stock app
     * hides the split, so open it again 2.5 s later, while the camera is still off.
     */
    static void panel(Context context, boolean on) {
        if (context != null && appContext == null) appContext = context.getApplicationContext();
        if (on || handler == null) return;
        handler.removeCallbacks(AFTER_CAMERA);
        handler.postDelayed(AFTER_CAMERA, 2500);
    }

    private static final Runnable AFTER_CAMERA = new Runnable() {
        @Override public void run() {
            if (state.at(1)) ensure("after camera");
        }
    };
}
