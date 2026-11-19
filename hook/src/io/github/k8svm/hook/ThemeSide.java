package io.github.k8svm.hook;

import hmgapi.hook.Bridge;
import hmgapi.hook.MethodHook;
import java.lang.reflect.Method;

import static io.github.k8svm.hook.SvmOverlayEntry.*;

/**
 * Navigation process: the ccNC now-playing bar places itself inside the 1280-wide map only when it is told the
 * split side panel is open (BottomBarHost.setSideOpen). While the around-view panel covers the right column,
 * report "open" so the bar moves into the 16:9 map; otherwise pass the stock value through unchanged.
 */
final class ThemeSide {
    private static Object host;
    private static Boolean stock;
    private static Method set;
    private static boolean forced;

    static void install(ClassLoader appLoader) {
        try {
            Class<?> c = Class.forName("dev.ev6.nowplaying.BottomBarHost", false, ThemeSide.class.getClassLoader());
            set = c.getDeclaredMethod("setSideOpen", Boolean.class);
            set.setAccessible(true);
            Bridge.hookMethod(set, new MethodHook() {
                @Override protected void beforeHookedMethod(Param p) {
                    if (p.thisObject == null) return;
                    host = p.thisObject;
                    if (!forced) stock = (Boolean) p.args[0];
                    if (panelCovers()) p.args[0] = Boolean.TRUE;
                }
            });
            log("theme side hook set");
        } catch (Throwable t) {
            log("theme side hook unavailable (" + t + ")");
        }
    }

    static boolean panelCovers() {
        if (!Live.on("panel", panelStart) || !Live.on("split", splitStart)) return false;
        return state.at(2) || state.at(3) || state.at(21) || state.at(22) || state.at(23);
    }

    /** main thread: re-apply after a view or reverse change. */
    static void sync() {
        if (host == null || set == null) return;
        boolean want = panelCovers();
        if (want == forced) return;
        try {
            forced = want;
            set.invoke(host, want ? Boolean.TRUE : stock);
            log("theme side " + (want ? "open for panel" : "stock " + stock));
        } catch (Throwable t) {
            err("theme side update failed", t);
        }
    }
}
