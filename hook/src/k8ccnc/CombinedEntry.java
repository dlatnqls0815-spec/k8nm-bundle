package k8ccnc;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

/**
 * One Seogaemo hook registration for both parts (the host keeps one jar and one entry per module):
 * the ccNC theme (blackr, personal use only) and the K8 around-view right panel.
 * Each part is guarded so a failure in one never stops the other or the stock app.
 */
public final class CombinedEntry extends ModEntry {
    private static final String CAMERA = "com.hkmc.system.app.rearcamera";
    private ModEntry theme, svm;

    @Override
    public void zygoteInit(ModContext ctx) {
        try {
            ModEntry t = new gl3ccnc.host.ThemeEntry();
            t.zygoteInit(ctx);
            theme = t;
        } catch (Throwable e) { ctx.error("ccnc-svm: theme zygoteInit failed, theme off", e); }
        try {
            ModEntry s = new io.github.k8svm.hook.SvmOverlayEntry();
            s.zygoteInit(ctx);
            svm = s;
        } catch (Throwable e) { ctx.error("ccnc-svm: panel zygoteInit failed, panel off", e); }
        ctx.log("ccnc-svm: loaded theme=" + (theme != null) + " panel=" + (svm != null));
    }

    @Override
    public void appLoaded(ModContext ctx, LoadedApp app) {
        String proc = String.valueOf(app.processName);
        // The theme never ran in the camera app; keep it out of there.
        if (theme != null && !proc.startsWith(CAMERA)) {
            try { theme.appLoaded(ctx, app); } catch (Throwable e) { ctx.error("ccnc-svm: theme failed in " + proc, e); }
        }
        if (svm != null) {
            try { svm.appLoaded(ctx, app); } catch (Throwable e) { ctx.error("ccnc-svm: panel failed in " + proc, e); }
        }
    }
}
