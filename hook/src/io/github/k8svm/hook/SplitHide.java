package io.github.k8svm.hook;

import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import hmgapi.hook.Bridge;
import hmgapi.hook.MethodHook;

import static io.github.k8svm.hook.SvmOverlayEntry.*;

/**
 * Split screen app (com.mobis.splitscreen.right): its right-column window (1280..1920) sits above the camera
 * window, and the SVM video is a hardware plane under every window, so an open split screen hid the around view.
 * 0.2.5: while the camera panel shows, make that window fully transparent and let touches pass through; put it
 * back when the camera ends. Display only.
 */
final class SplitHide {
    private static View view;
    private static WindowManager wm;
    private static WindowManager.LayoutParams orig;
    private static boolean hidden, self;

    static void install(ClassLoader cl) {
        try {
            Class<?> wmi = Class.forName("android.view.WindowManagerImpl", false, cl);
            Bridge.hookMethod(wmi.getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class), new MethodHook() {
                @Override protected void afterHookedMethod(Param p) {
                    try {
                        if (p.hasThrowable() || !isPanelWindow(p.args[1])) return;
                        view = (View) p.args[0];
                        wm = (WindowManager) p.thisObject;
                        orig = copy((WindowManager.LayoutParams) p.args[1]);
                        hidden = false;
                        log("split panel window found " + describe(orig));
                        sync();
                    } catch (Throwable t) { err("split addView hook", t); }
                }
            });
            Bridge.hookMethod(wmi.getDeclaredMethod("updateViewLayout", View.class, ViewGroup.LayoutParams.class), new MethodHook() {
                @Override protected void beforeHookedMethod(Param p) {
                    try {
                        if (self || view == null || p.args[0] != view || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
                        orig = copy(lp);
                        if (hidden) cover(lp);
                    } catch (Throwable t) { err("split updateViewLayout hook", t); }
                }
            });
            Bridge.hookMethod(wmi.getDeclaredMethod("removeView", View.class), new MethodHook() {
                @Override protected void afterHookedMethod(Param p) {
                    if (view != null && p.args[0] == view) { view = null; hidden = false; }
                }
            });
            log("split hide hooks set");
        } catch (Throwable t) {
            err("split hide hooks unavailable", t);
        }
    }

    static boolean isPanelWindow(Object o) {
        if (!(o instanceof WindowManager.LayoutParams)) return false;
        return SPLIT.contentEquals(String.valueOf(((WindowManager.LayoutParams) o).getTitle()));
    }

    private static void cover(WindowManager.LayoutParams lp) {
        lp.alpha = 0f;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    /** main thread */
    static void sync() {
        if (view == null || wm == null || orig == null) return;
        boolean want = ThemeSide.panelCovers() && !state.inReverse();
        if (want == hidden) return;
        try {
            WindowManager.LayoutParams lp = copy(orig);
            if (want) cover(lp);
            self = true;
            wm.updateViewLayout(view, lp);
            hidden = want;
            log(want ? "split panel window hidden for camera" : "split panel window shown");
        } catch (Throwable t) {
            err("split panel window update failed", t);
        } finally {
            self = false;
        }
    }

    private static String describe(WindowManager.LayoutParams lp) {
        return "type=" + lp.type + " x=" + lp.x + " w=" + lp.width + " h=" + lp.height + " alpha=" + lp.alpha;
    }
}
