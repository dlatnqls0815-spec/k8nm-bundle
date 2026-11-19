package io.github.k8svm.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import hmgapi.hook.Bridge;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * K8 SVM right-panel test, HMG-Seogaemo module k8-svm-overlay-test (verb hook_apps).
 *
 * 0.1.4 showed that narrowing the stock "AvmCamera" window (ty 2035) to the right WIDTH pixels clips the SVM
 * video (hardware plane under the Android windows): map on the left, top view on the right.
 *
 * 0.1.9: navigation learns a start method per boot. Only the camera process sends the
 * recorded 10/15 menu sequence. Window geometry, live crop and stock top-view options are separate trials.
 *
 * 0.1.5 (still a test):
 *  - Camera app (com.hkmc.system.app.rearcamera), setting panel_on: narrow the AvmCamera window only when ALL of
 *    these hold: the reverse listener (ISystemService.addListener) and the AVM listener (AvmManager.addListener)
 *    are registered, the system has not reported reverse, the screen is on, and the SVM controller reports view
 *    mode 22 ("rear + around view", only offered in D). Any other view mode, reverse, screen off or window
 *    removal puts the stock full-screen layout back. Restore completes only after updateViewLayout succeeded.
 *  - Camera app and split screen app (com.mobis.splitscreen.right), setting split_keep: under the same
 *    conditions PgsManager.isDisplaySSWithCamera() answers true (stock path that keeps/resizes the split screen
 *    with the camera). Otherwise the stock answer.
 *  - Camera app, setting record: logs every AvmManager.request* / PgsManager.request* call of the stock app with
 *    its arguments (observe only, this module sends nothing).
 */
public class SvmOverlayEntry extends ModEntry {
    static final String CAMERA = "com.hkmc.system.app.rearcamera";
    static final String SPLIT = "com.mobis.splitscreen.right";
    static final String NAV = "com.mnsoft.navi";
    static final int HEIGHT = 720;
    static final int TARGET_VIEW = 22;
    /** In P the camera key opens view 2, already rear + around view. R also shows view 2, so wait and require no reverse. */
    static final int PARK_VIEW = 2;
    /** D camera key can open view 3 (front + around view, top view on the right). */
    static final int FRONT_VIEW = 3;
    static final long PARK_SETTLE_MS = 900;
    /** Width without a head unit restart: adb shell "echo 620 > /data/local/tmp/k8svm_width; chmod 644 ...". */
    static final String OVERRIDE = "/data/local/tmp/k8svm_width";

    private static boolean hooked;
    private static String bootId;
    static ModContext mod;
    static boolean isCamera;
    static int width = 480;
    static int height = HEIGHT, top, inset;
    static boolean autoOn, panelStart = true, splitStart = true;
    static String shapeStart = "full", cutTopStart = "0", cutBottomStart = "0";
    static Object avmManager;
    static long menuDeadline, lastMenuRevision = -2;
    static int lastMenu;
    static final VehicleState state = new VehicleState();
    static NavStart nav;
    static volatile CropTrial crop;
    static volatile StockOptionTrial option;
    static String process;
    static volatile long stockRevision = -1;
    static volatile boolean windowReady;

    // window state (main thread only)
    static volatile View camView;
    static WindowManager camWm;
    static WindowManager.LayoutParams camOrig;   // last stock layout, never modified
    static WindowManager.LayoutParams lastPanel;
    static boolean narrowed;
    static boolean selfUpdate;

    private static Object reverseListener, avmListener, screenReceiver;   // kept alive
    static Handler main;

    @Override
    public void zygoteInit(ModContext ctx) {
        ctx.log("k8svm: ccnc-svm 0.2.5 panel loaded (zygote)");
        // Read once before the host forks. Navigation process restarts inherit the same kernel boot ID.
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            if (value == null || value.trim().isEmpty()) throw new java.io.IOException("empty boot ID");
            bootId = value.trim();
        } catch (Exception e) {
            ctx.error("k8svm: boot ID unavailable, automatic navigation start suspended", e);
        }
    }

    @Override
    public void appLoaded(final ModContext ctx, final LoadedApp app) {
        mod = ctx;
        process = app.processName;
        ctx.log("k8svm: 0.1.9 appLoaded pkg=" + app.packageName + " proc=" + process);
        try {
            String pkg = app.packageName, proc = app.processName;
            boolean primary = proc != null && proc.equals(app.context.getApplicationInfo().processName);
            boolean cam = CAMERA.equals(proc) || (primary && CAMERA.equals(pkg));
            boolean split = SPLIT.equals(proc) || (primary && SPLIT.equals(pkg));
            boolean navi = NAV.equals(pkg);
            if (!cam && !split && !navi) return;
            synchronized (SvmOverlayEntry.class) {
                if (hooked) return;
                hooked = true;
            }
            isCamera = cam;
            main = new Handler(Looper.getMainLooper());
            String savedStart = ctx.settingString("nav_try", "off");
            // A 0.1.8 card save can include test_on=off. Give the default-on panel its own toggle.
            boolean testOn = ctx.settingBoolean("panel_on", true);
            boolean splitKeep = ctx.settingBoolean("split_keep", true);
            boolean record = ctx.settingBoolean("record", true);
            autoOn = ctx.settingBoolean("auto_on", true);
            width = width(ctx.settingString("width", "640"));
            String shape = ctx.settingString("shape", "full");
            panelStart = testOn; splitStart = splitKeep; shapeStart = shape;
            cutTopStart = ctx.settingString("cut_top", "0"); cutBottomStart = ctx.settingString("cut_bottom", "0");
            String start = NavStart.effectiveMode(savedStart);
            ClassLoader cl = app.classLoader;
            log("start in " + proc + " panel_on=" + testOn + " auto_on=" + autoOn
                    + " width=" + width + (width == 1920 ? " (축소 안 함)" : "")
                    + " shape=" + shape + " nav_start=" + start
                    + " override=" + OVERRIDE);
            // 0.1.11: panel, width, shape, auto and split are read live (apply.sh mirrors them to k8svm.* props).

            listenScreen(app.context);
            boolean systemOk = listenReverse(cl);
            boolean avmOk = listenAvm(cl, app.context);
            state.listeners(systemOk, avmOk);
            if (systemOk && avmOk && state.needsReverseStatus()) checkInitialPark(cl, app.context);
            if (navi) {
                ThemeSide.install(cl);
                BarOffset.install(cl);
                if (!NavStart.supports(start)) return;
                nav = new NavStart(app.context, start, bootId);
                nav.register();
                return;
            }
            if (cam && record) hookRecorder(cl);
            if (cam) hookWindow(cl);
            if (cam) SplitOpen.install(cl, app.context, main);
            if (split) SplitHide.install(cl);
            hookSplitAnswer(cl);
            if (cam && testOn && ctx.settingBoolean("crop_on", false)) {
                crop = new CropTrial();
                crop.install(cl);
            }
            String probe = ctx.settingString("top_probe", "off");
            if (cam && testOn && ("zoom".equals(probe) || "top".equals(probe))) {
                option = new StockOptionTrial();
                option.install(cl, probe);
            }
        } catch (Throwable t) {
            state.listeners(false, false);
            if (main != null) stateChanged("setup failure");
            err("start failed, stock behaviour", t);
        }
    }

    // ---------------------------------------------------------------- conditions

    static boolean wanted() {
        return panelEligible() && (option == null || option.showPanel(state.revision()));
    }

    static boolean panelEligible() {
        return windowReady && Live.on("panel", panelStart) && (state.at(TARGET_VIEW) || state.at(FRONT_VIEW) || state.parkPanel(PARK_SETTLE_MS)) && stockRevision != state.revision() && (crop == null || !crop.failed);
    }

    static void changed(final String why, final int prev, final int next, final long revision) {
        Runnable work = new Runnable() {
            @Override
            public void run() {
                if (nav != null) nav.changed();
                ThemeSide.sync();
                SplitHide.sync();
                if (!isCamera) return;
                if (!wanted()) restore(why);
                if (crop != null) crop.sync();
                if (option != null) option.changed();
                if (wanted()) narrow(why);
                if (next != prev && Live.on("auto", autoOn) && state.at(revision, next)) autoStep(prev, next, revision);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) work.run(); else main.post(work);
    }

    static void stateChanged(String why) {
        changed(why, -1, -1, state.revision());
    }

    // ---------------------------------------------------------------- listeners

    static void checkInitialPark(ClassLoader cl, Context context) {
        try {
            Class<?> pm = cl.loadClass("com.hkmc.pgs.PgsManager");
            Object pgs = pm.getConstructor(Context.class).newInstance(context);
            Object parked = pm.getMethod("getGearPStatus").invoke(pgs);
            if (state.confirmPark(Boolean.TRUE.equals(parked))) log("initial non-reverse confirmed in P");
        } catch (Throwable t) { err("P status unavailable; waiting for reverse listener", t); }
    }

    static boolean listenReverse(ClassLoader cl) {
        try {
            reverseListener = new com.hkmc.micom.SystemListener() {
                @Override
                public void onReverseStatus(boolean on) {
                    state.reverse(on);
                    log("onReverseStatus " + on);
                    stateChanged("reverse " + on);
                }
                @Override
                public void onGearStatus(int gear) { log("onGearStatus " + gear); }
            };
            Object svc = service(cl, "com.hkmc.micom.system", "com.hkmc.micom.ISystemService$Stub");
            Class<?> li = Class.forName("com.hkmc.micom.ISystemListener", true, cl);
            svc.getClass().getMethod("addListener", li).invoke(svc, reverseListener);
            log("reverse listener registered");
            return true;
        } catch (Throwable t) {
            err("reverse listener failed (narrowing disabled)", t);
            return false;
        }
    }

    static boolean listenAvm(ClassLoader cl, Context context) {
        try {
            avmListener = new com.hkmc.pgs.AvmStatusListener() {
                @Override
                public void onAvmStatus(int view, int display, int rearOpt, int frontOpt) {
                    int prev = state.changeView(view);
                    if (view != prev) log("view_mode " + prev + " -> " + view + " display " + display);
                    changed("view " + view, prev, view, state.revision());
                    if (view == PARK_VIEW && prev == 1 && main != null) {
                        main.postDelayed(new Runnable() {
                            @Override public void run() { stateChanged("park view settled"); }
                        }, PARK_SETTLE_MS + 50);
                    }
                }
            };
            Class<?> am = Class.forName("com.hkmc.pgs.AvmManager", true, cl);
            Object m = am.getConstructor(Context.class).newInstance(context);
            Class<?> li = Class.forName("com.hkmc.pgs.AvmStatusListener", true, cl);
            am.getMethod("addListener", li).invoke(m, avmListener);
            avmManager = m;
            log("avm listener registered");
            return true;
        } catch (Throwable t) {
            err("avm listener failed (narrowing disabled)", t);
            return false;
        }
    }

    static Object service(ClassLoader cl, String name, String stubClass) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager", true, cl);
        Object b = sm.getMethod("getService", String.class).invoke(null, name);
        if (b == null) throw new IllegalStateException("no service " + name);
        Class<?> stub = Class.forName(stubClass, true, cl);
        return stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
    }

    static void listenScreen(Context context) {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            f.addAction(android.content.Intent.ACTION_SCREEN_ON);
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    boolean screenOn = android.content.Intent.ACTION_SCREEN_ON.equals(i.getAction());
                    state.screen(screenOn);
                    log("screen " + (screenOn ? "on" : "off"));
                    stateChanged("screen");
                }
            };
            context.getApplicationContext().registerReceiver(r, f);
            screenReceiver = r;
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            state.screen(power.isScreenOn());
        } catch (Throwable t) {
            err("screen receiver failed", t);
        }
    }

    // ---------------------------------------------------------------- window (camera app)

    static boolean isCamWindow(Object lpObj) {
        if (!(lpObj instanceof WindowManager.LayoutParams)) return false;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) lpObj;
        return lp.type == 2035 && "AvmCamera".contentEquals(String.valueOf(lp.getTitle()));
    }

    static void hookWindow(ClassLoader cl) throws Exception {
        Class<?> wmi = Class.forName("android.view.WindowManagerImpl", false, cl);
        Bridge.hookMethod(wmi.getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class), new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                try {
                    if (p.hasThrowable() || !windowReady || !isCamWindow(p.args[1])) return;
                    camOrig = copy((WindowManager.LayoutParams) p.args[1]);
                    camView = (View) p.args[0];
                    camWm = (WindowManager) p.thisObject;
                    narrowed = false;
                    stateChanged("AvmCamera added");
                } catch (Throwable t) { err("addView hook", t); }
            }
        });
        Bridge.hookMethod(wmi.getDeclaredMethod("updateViewLayout", View.class, ViewGroup.LayoutParams.class), new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                try {
                    if (selfUpdate || p.hasThrowable() || p.args[0] != camView || !isCamWindow(p.args[1])) return;
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
                    WindowManager.LayoutParams orig = copy(lp);
                    if (narrowed && lp == lastPanel) {
                        orig.width = camOrig.width;
                        orig.height = camOrig.height;
                        orig.gravity = camOrig.gravity;
                        orig.x = camOrig.x;
                        orig.y = camOrig.y;
                    } else {
                        narrowed = false;
                    }
                    camOrig = orig;
                    stateChanged("stock layout");
                } catch (Throwable t) { err("updateViewLayout hook", t); }
            }
        });
        MethodHook removed = new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!p.hasThrowable() && camView != null && p.args[0] == camView) {
                    log("AvmCamera removed");
                    hideMasks();
                    try { SplitOpen.panel(camView.getContext(), false); } catch (Throwable ignored) { }
                    camView = null; camWm = null; camOrig = null; lastPanel = null; narrowed = false;
                    if (crop != null) crop.sync();
                }
            }
        };
        Bridge.hookMethod(wmi.getDeclaredMethod("removeView", View.class), removed);
        Bridge.hookMethod(wmi.getDeclaredMethod("removeViewImmediate", View.class), removed);
        windowReady = true;
        log("window hooks set");
    }

    static WindowManager.LayoutParams copy(WindowManager.LayoutParams lp) {
        WindowManager.LayoutParams c = new WindowManager.LayoutParams();
        c.copyFrom(lp);
        return c;
    }

    static WindowManager.LayoutParams narrowedCopy(WindowManager.LayoutParams orig) {
        WindowManager.LayoutParams c = copy(orig);
        String shape = Live.get("shape", shapeStart);
        height = HEIGHT; top = 0; inset = 0;
        if ("h540_y90".equals(shape)) { height = 540; top = 90; }
        if ("h480_y120".equals(shape)) { height = 480; top = 120; }
        if ("left240".equals(shape)) inset = 240;
        // 0.1.14: cut the top and bottom separately (pixels). Either one set overrides the shape height.
        int cutTop = cut(Live.get("cut_top", cutTopStart)), cutBottom = cut(Live.get("cut_bottom", cutBottomStart));
        if (cutTop > 0 || cutBottom > 0) {
            top = cutTop;
            height = Math.max(240, HEIGHT - cutTop - cutBottom);
        }
        c.width = liveWidth();
        c.height = height;
        c.gravity = Gravity.RIGHT | Gravity.TOP;
        c.x = inset;
        c.y = top;
        return c;
    }

    /** main thread */
    static void narrow(String why) {
        if (narrowed || camView == null || camWm == null || camOrig == null || !wanted()) return;
        WindowManager.LayoutParams panel = narrowedCopy(camOrig);
        if (!apply(panel)) {
            stockRevision = state.revision();
            narrowed = true;   // a partial update still owes the stock layout
            restore("panel update failed");
            if (crop != null) crop.sync();
            return;
        }
        lastPanel = panel;
        narrowed = true;
        if (!wanted()) { restore("state changed during layout"); return; }
        showMasks(panel);
        SplitOpen.panel(camView.getContext(), true);
        log((panel.width == 1920 ? "width=1920 (축소 안 함) " : "narrowed to ")
                + panel.width + "x" + height + " right inset=" + inset + " y=" + top + " (" + why + ")");
    }

    /** main thread */
    static void restore(String why) {
        if (!narrowed || camView == null || camWm == null || camOrig == null) return;
        hideMasks();
        SplitOpen.panel(camView.getContext(), false);
        if (!apply(copy(camOrig))) return;   // narrowed stays true: the next state change retries
        narrowed = false;
        lastPanel = null;
        log("full screen restored (" + why + ")");
    }

    /**
     * 0.1.16: the stock SurfaceView keeps a fixed 720-pixel height, so its video hole runs past the bottom of a
     * shortened window (the top cut works because the hole starts at the window top). Shrink the SurfaceViews to
     * the panel height so the map shows below, and put their stock sizes back on restore.
     */
    static final java.util.List<Object[]> surfaces = new java.util.ArrayList<Object[]>();

    static void showMasks(WindowManager.LayoutParams panel) {
        hideMasks();
        try { shrink(camView, panel.width, panel.height); } catch (Throwable t) { err("surface shrink failed", t); }
        if (!surfaces.isEmpty()) log("surface views shrunk " + surfaces.size() + " to " + panel.width + "x" + panel.height);
    }

    static void shrink(View v, int w, int h) {
        if (v instanceof android.view.SurfaceView) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                surfaces.add(new Object[] {v, lp.width, lp.height});
                // Height only: the stock 1920 width keeps the camera menu column off the panel (0.1.16 showed it).
                lp.height = h;
                v.setLayoutParams(lp);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) shrink(g.getChildAt(i), w, h);
        }
    }

    static void hideMasks() {
        for (Object[] e : surfaces) {
            try {
                View v = (View) e[0];
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                lp.width = (Integer) e[1];
                lp.height = (Integer) e[2];
                v.setLayoutParams(lp);
            } catch (Throwable t) { err("surface restore failed", t); }
        }
        surfaces.clear();
    }

    static boolean apply(WindowManager.LayoutParams lp) {
        selfUpdate = true;
        try {
            camWm.updateViewLayout(camView, lp);
            return true;
        } catch (Throwable t) {
            err("updateViewLayout failed", t);
            return false;
        } finally {
            selfUpdate = false;
        }
    }

    // ---------------------------------------------------------------- split screen answer (both apps)

    static void hookSplitAnswer(ClassLoader cl) throws Exception {
        Class<?> pm = Class.forName("com.hkmc.pgs.PgsManager", true, cl);
        Bridge.hookMethod(pm.getMethod("isDisplaySSWithCamera"), new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (p.hasThrowable()) return;
                boolean w = Live.on("split", splitStart) && (state.at(2) || state.at(3) || state.at(21) || state.at(22) || state.at(23));
                Object stock = p.getResult();
                if (w) p.setResult(Boolean.TRUE);
                log("isDisplaySSWithCamera stock=" + stock + " -> " + (w ? "true" : stock) + " " + state.describe());
            }
        });
        log("split answer hook set");
    }

    // ---------------------------------------------------------------- auto select (camera app)

    /**
     * After the driver presses the camera key (view 1 -> 2, 3, or 21), send the two selections the stock touch buttons
     * send: requestSelectMenu(10) ("rear camera", 2/3/21 -> 23), then requestSelectMenu(15) ("around view", 23 -> 22).
     * Recorded from the stock app on this car (2026-11-19); sequence from view 2 is unconfirmed on the car.
     * Once per camera session; cancelled on reverse, screen off, camera off, an unexpected view or after 6 s;
     * never retried.
     */
    static void autoStep(int prev, int view, long rev) {
        // The camera key opens different views by speed and gear (P: 2, D <10 km/h: 3, D >=10 km/h: 21).
        // 1 and 22 do nothing. 23 needs only "around view" (15).
        // 2 (P, unconfirmed on the car), 3, 21 and any other view opened from camera off get "rear camera" (10)
        // first (-> 23), then 15 (-> 22).
        if (prev == 1) {
            lastMenu = 0;
            menuDeadline = SystemClock.uptimeMillis() + 6000;
            if (view == 23) { lastMenu = 10; select(15, 23, rev, 500); }
            else if (view != TARGET_VIEW && view != 1) select(10, view, rev, 800);
            else return;
            log("auto start from view " + view);
        } else if (lastMenuRevision + 1 == rev && SystemClock.uptimeMillis() <= menuDeadline) {
            if (lastMenu == 10 && view == 23) select(15, 23, rev, 500);
            if (lastMenu == 15 && view == 22) log("auto done (view 22)");
        }
    }

    static void select(final int menu, final int expected, final long rev, long delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Method selectMenu = avmManager.getClass().getMethod("requestSelectMenu", int.class);
                    if (!state.at(rev, expected) || SystemClock.uptimeMillis() > menuDeadline
                            || (menu == 15 && lastMenu != 10)) {
                        log("auto skip " + menu + " " + state.describe());
                        return;
                    }
                    lastMenu = menu;
                    lastMenuRevision = rev;
                    Object r = selectMenu.invoke(avmManager, menu);
                    if (r instanceof Integer && (Integer) r < 0) lastMenu = 0;
                    log("auto requestSelectMenu(" + menu + ") -> " + r + " (view " + expected + ")");
                } catch (Throwable t) {
                    err("auto requestSelectMenu(" + menu + ") failed", t);
                    lastMenu = 0;
                }
            }
        }, delayMs);
    }

    // ---------------------------------------------------------------- recorder (camera app)

    static void hookRecorder(ClassLoader cl) {
        int n = 0;
        for (String cls : new String[] {"com.hkmc.pgs.AvmManager", "com.hkmc.pgs.PgsManager"}) {
            try {
                Class<?> c = Class.forName(cls, true, cl);
                final String shortName = c.getSimpleName();
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().startsWith("request") || Modifier.isAbstract(m.getModifiers())) continue;
                    if (m.getName().equals("requestReverseStatus")) continue;   // a getter, polled often
                    final String name = m.getName();
                    Bridge.hookMethod(m, new MethodHook() {
                        @Override
                        protected void afterHookedMethod(Param p) {
                            log("REC " + shortName + "." + name + Arrays.toString(p.args)
                                + " -> " + (p.hasThrowable() ? "throw" : String.valueOf(p.getResult()))
                                + " (view " + state.view() + ")");
                        }
                    });
                    n++;
                }
            } catch (Throwable t) {
                err("recorder " + cls, t);
            }
        }
        log("recorder hooks " + n);
    }

    // ---------------------------------------------------------------- misc

    static int liveWidth() {
        try {
            java.io.File f = new java.io.File(OVERRIDE);
            if (f.isFile() && f.length() < 16) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                try {
                    String v = r.readLine();
                    if (v != null) { int w = width(v.trim()); if (w != 480 || "480".equals(v.trim())) return w; }
                } finally { r.close(); }
            }
        } catch (Throwable ignored) { }
        return width(Live.get("width", String.valueOf(width)));
    }

    static int cut(String v) {
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= 0 && n <= 240) return n;
        } catch (Throwable ignored) { }
        return 0;
    }

    static int width(String v) {
        try {
            int w = Integer.parseInt(v);
            if (w >= 320 && w <= 1920) return w;
        } catch (Throwable ignored) { }
        return 480;
    }

    static void log(String s) { try { mod.log("k8svm: " + s + " [" + process + "]"); } catch (Throwable ignored) { } }
    static void err(String s, Throwable t) { try { mod.error("k8svm: " + s + " [" + process + "]", t); } catch (Throwable ignored) { } }
}
