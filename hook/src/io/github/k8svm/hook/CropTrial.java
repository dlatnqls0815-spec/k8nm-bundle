package io.github.k8svm.hook;

import hmgapi.hook.Bridge;
import hmgapi.hook.MethodHook;
import java.lang.reflect.Method;

import static io.github.k8svm.hook.SvmOverlayEntry.*;

/** Borrow the stock preview. Snapshot only the two crop keys and restore them on the same camera. */
final class CropTrial {
    private static final String POS = "crop-position", SIZE = "crop-size";
    private Object preview, owner;
    private String oldPosition, oldSize;
    private long applied = -1;
    volatile boolean failed;

    void install(ClassLoader cl) {
        try {
            Class<?> camera = cl.loadClass("android.hardware.RearCamera");
            MethodHook stop = new MethodHook() {
                @Override protected void beforeHookedMethod(Param p) { stopping(p.thisObject); }
                @Override protected void afterHookedMethod(Param p) {
                    if (!p.hasThrowable() && "release".equals(p.method.getName())) released(p.thisObject);
                }
            };
            Bridge.hookMethod(camera.getMethod("stopPreview"), stop);
            Bridge.hookMethod(camera.getMethod("release"), stop);
            Bridge.hookMethod(camera.getMethod("startPreview"), new MethodHook() {
                @Override protected void afterHookedMethod(Param p) {
                    if (!p.hasThrowable()) started(p.thisObject);
                }
            });
            log("crop trial ready");
        } catch (Throwable t) { fail("install", t); }
    }

    synchronized void started(Object camera) {
        preview = camera;
        sync();
    }

    synchronized void stopping(Object camera) {
        if (preview == camera) preview = null;
        if (owner == camera) rollback();
    }

    synchronized void released(Object camera) {
        if (owner == camera) owner = null;
    }

    private static Object params(Object camera) throws Exception {
        return camera.getClass().getMethod("getParameters").invoke(camera);
    }

    private static void commit(Object camera, Object p) throws Exception {
        camera.getClass().getMethod("setParameters", p.getClass()).invoke(camera, p);
    }

    synchronized void sync() {
        long revision = state.revision();
        if (owner != null && (camView == null || preview != owner || state.revision() != applied || !wanted())) rollback();
        if (camView == null || preview == null || owner != null || failed || !wanted() || state.revision() != revision) return;
        try {
            Object p = params(preview);
            Class<?> type = p.getClass();
            Method get = type.getMethod("get", String.class);
            oldPosition = (String) get.invoke(p, POS);
            oldSize = (String) get.invoke(p, SIZE);
            if (oldPosition == null || !oldPosition.matches("[0-9]+,[0-9]+")
                    || oldSize == null || !oldSize.matches("[0-9]+x[0-9]+")) {
                throw new IllegalStateException("stock crop unavailable: " + oldPosition + " " + oldSize);
            }
            // Check the rollback setter exists before changing any camera state.
            type.getMethod("set", String.class, String.class);
            type.getMethod("setCropPosition", int.class, int.class).invoke(p, 1200, 0);
            type.getMethod("setCropSize", int.class, int.class).invoke(p, 720, 720);
            if (state.revision() != revision) return;
            owner = preview;   // setParameters can fail after a partial native update
            applied = revision;
            commit(owner, p);
            log("crop sent 1200,0 720x720 stock=" + oldPosition + " " + oldSize);
            if (state.revision() != revision) rollback();
        } catch (Throwable t) {
            fail("apply", t);
            if (owner != null) rollback();
        }
    }

    private void rollback() {
        try {
            Object p = params(owner);
            Method set = p.getClass().getMethod("set", String.class, String.class);
            set.invoke(p, POS, oldPosition);
            set.invoke(p, SIZE, oldSize);
            commit(owner, p);
            owner = null;
            log("crop stock sent " + oldPosition + " " + oldSize);
        } catch (Throwable t) { fail("restore", t); }
    }

    private void fail(String phase, Throwable t) {
        err("crop " + phase + " failed", t);
        if (failed) return;
        failed = true;
        main.post(new Runnable() {
            @Override public void run() { restore("crop failure"); }
        });
    }
}
