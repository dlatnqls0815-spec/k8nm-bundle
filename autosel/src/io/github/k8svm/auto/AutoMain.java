package io.github.k8svm.auto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * NEMESIS service k8avs.auto (app_process, uid=system): after the driver presses the camera key in P or D (stopped
 * or driving), press the stock "rear camera" (10) and "around view" (15) menu buttons, the same requests the stock
 * touch buttons send (com.hkmc.pgs.AvmManager.requestSelectMenu). No app injection, no window change.
 *
 * Rules (recorded on the car 2026-11-19; sequence from view 2 unconfirmed on the car): from camera off (view 1)
 * only, once per camera session.
 * 2, 3, 21 -> select 10 -> 23 -> select 15 -> 22. 23 -> select 15 -> 22. 1, 22: nothing.
 * Cancelled after 6 s or on any unexpected view; never retried.
 * persist.sys.k8avs.on=0 turns it off (read every time); persist.sys.k8avs.dry=1 logs without pressing.
 */
public final class AutoMain {
    static final int OFF = 1, PARK = 2, FRONT = 3, KEY = 21, TARGET = 22, REAR_WIDE = 23;

    static Handler main;
    static Object avm;
    static Method selectMenu;
    static int view = -1;
    static long revision, lastMenuRevision, deadline;
    static int lastMenu;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        main = new Handler();
        Context ctx = context();
        log("start, context " + (ctx == null ? "null" : ctx.getClass().getName()));
        Class<?> am = Class.forName("com.hkmc.pgs.AvmManager");
        avm = am.getConstructor(Context.class).newInstance(ctx);
        selectMenu = am.getMethod("requestSelectMenu", int.class);
        Class<?> li = Class.forName("com.hkmc.pgs.AvmStatusListener");
        am.getMethod("addListener", li).invoke(avm, new com.hkmc.pgs.AvmStatusListener() {
            @Override
            public void onAvmStatus(final int v, final int display, int rearOpt, int frontOpt) {
                main.post(new Runnable() {
                    @Override public void run() { status(v, display); }
                });
            }
        });
        log("avm listener registered" + (dry() ? " (dry run)" : ""));
        Looper.loop();
    }

    static Context context() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    /** main thread */
    static void status(int v, int display) {
        if (v == view) return;
        int prev = view;
        view = v;
        revision++;
        log("view " + prev + " -> " + v + " display " + display);
        if (prev == -1 || !enabled()) return;
        step(prev, v, revision);
    }

    static void step(int prev, int v, long rev) {
        if (prev == OFF) {
            lastMenu = 0;
            deadline = SystemClock.uptimeMillis() + 6000;
            if (v == REAR_WIDE) { lastMenu = 10; select(15, REAR_WIDE, rev, 500); }
            else if (v != TARGET && v != OFF) select(10, v, rev, 800);
            else return;
            log("auto start from view " + v);
        } else if (lastMenuRevision + 1 == rev && SystemClock.uptimeMillis() <= deadline) {
            if (lastMenu == 10 && v == REAR_WIDE) select(15, REAR_WIDE, rev, 500);
            if (lastMenu == 15 && v == TARGET) log("auto done (view 22)");
        }
    }

    static void select(final int menu, final int expected, final long rev, long delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (revision != rev || view != expected || SystemClock.uptimeMillis() > deadline
                        || (menu == 15 && lastMenu != 10) || !enabled()) {
                    log("auto skip " + menu + " (view " + view + ")");
                    return;
                }
                lastMenu = menu;
                lastMenuRevision = rev;
                if (dry()) { log("dry run: would requestSelectMenu(" + menu + ")"); return; }
                try {
                    Object r = selectMenu.invoke(avm, menu);
                    if (r instanceof Integer && (Integer) r < 0) lastMenu = 0;
                    log("requestSelectMenu(" + menu + ") -> " + r + " (view " + expected + ")");
                } catch (Throwable t) {
                    lastMenu = 0;
                    log("requestSelectMenu(" + menu + ") failed: " + t);
                }
            }
        }, delayMs);
    }

    static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean enabled() {
        String v = prop("persist.sys.k8avs.on");
        return !("0".equals(v) || "off".equals(v) || "false".equals(v));
    }

    static boolean dry() {
        return "1".equals(prop("persist.sys.k8avs.dry"));
    }

    static void log(String s) {
        System.out.println(new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date()) + " " + s);
        System.out.flush();
    }
}
