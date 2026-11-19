package io.github.k8svm.hook;

import android.os.Looper;
import hmgapi.hook.Bridge;
import hmgapi.hook.MethodHook;
import java.lang.reflect.Method;

import static io.github.k8svm.hook.SvmOverlayEntry.*;

/** A/B/A comparison using two actual stock selections from this visit to view 22. */
final class StockOptionTrial {
    private Method method;
    private long session = -1;
    private volatile long tried = -1;
    private Object receiver;
    private int lastValue;
    private boolean self, temporary;
    private Runnable pending;

    void install(ClassLoader cl, String probe) throws Exception {
        boolean zoom = "zoom".equals(probe);
        Class<?> type = cl.loadClass(zoom ? "com.hkmc.pgs.PgsManager" : "com.hkmc.pgs.AvmManager");
        method = type.getMethod(zoom ? "requestAVMSelectedZoom" : "requestTopViewOption", int.class);
        Bridge.hookMethod(method, new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if (p.hasThrowable() || rejected(p.getResult())) return;
                if (Looper.myLooper() == Looper.getMainLooper() && self) return;
                final long rev = state.revision();
                final Object manager = p.thisObject;
                final int value = (Integer) p.args[0];
                Runnable observe = new Runnable() {
                    @Override public void run() { sample(manager, value, rev); }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) observe.run(); else main.post(observe);
            }
        });
        log("option waiting for two stock selections " + method.getName());
    }

    // Leave the stock controls reachable until both values are known in this visit.
    boolean showPanel(long rev) { return tried == rev; }

    private static boolean rejected(Object result) {
        return result instanceof Integer && (Integer) result < 0;
    }

    private void sample(final Object manager, final int value, final long rev) {
        if (!state.at(rev, TARGET_VIEW) || !panelEligible()) return;
        if (pending != null) main.removeCallbacks(pending);
        temporary = false; // A new stock request supersedes our temporary selection.
        final int alternate = lastValue;
        boolean pair = session == rev && receiver == manager && alternate != value && tried != rev;
        session = rev;
        receiver = manager;
        lastValue = value;
        log("option stock " + method.getName() + "(" + value + ")");
        if (!pair) return;
        tried = rev;
        stateChanged("option sample pair");
        pending = new Runnable() {
            @Override public void run() {
                if (!state.at(rev, TARGET_VIEW) || !wanted()) return;
                temporary = true;
                boolean sent = request(manager, alternate, "trial");
                if (!state.at(rev, TARGET_VIEW)) { changed(); return; }
                if (!sent) { restoreOption(manager, value, rev); return; }
                pending = new Runnable() {
                    @Override public void run() { restoreOption(manager, value, rev); }
                };
                main.postDelayed(pending, 4000);
            }
        };
        main.postDelayed(pending, 1200);
    }

    private void restoreOption(Object manager, int value, long rev) {
        if (!state.at(rev, TARGET_VIEW)) { changed(); return; }
        temporary = false;
        request(manager, value, "restore");
    }

    private boolean request(Object manager, int value, String phase) {
        self = true;
        try {
            if (!state.at(session, TARGET_VIEW)) return false;
            Object r = method.invoke(manager, value);
            log("option " + phase + " " + method.getName() + "(" + value + ") -> " + r);
            if (rejected(r)) throw new IllegalStateException("negative return " + r);
            return true;
        } catch (Throwable t) {
            stockRevision = session;
            err("option " + phase + " failed", t);
            stateChanged("option failure");
            return false;
        } finally { self = false; }
    }

    void changed() {
        if (session < 0 || state.at(session, TARGET_VIEW)) return;
        if (pending != null) main.removeCallbacks(pending);
        if (temporary) log("option interrupted; check stock selection");
        temporary = false;
        receiver = null;
        session = -1;
    }
}
