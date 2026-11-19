package io.github.k8svm.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import static io.github.k8svm.hook.SvmOverlayEntry.*;

/** Owns navigation requests on the main looper. The camera process owns both menu selections. */
final class NavStart implements Application.ActivityLifecycleCallbacks {
    private static final String ACTIVITY = "com.mnsoft.navi.NaviApp";
    private static final String DRVM = "com.hkmc.intent.action.DRVM_SHORTCUT";
    private static final String STOPPED = "stopped";
    private static final int PROBE_MS = 2000;
    private final Context context;
    private final String mode, bootId;
    private SharedPreferences prefs;
    private String outcome, pending;
    private Activity foreground;
    private boolean suppressReturn, sameVisit;
    private long attempt = -1, deadline;
    private Runnable check, timeout;

    NavStart(Context context, String mode, String bootId) {
        this.context = context;
        this.mode = mode;
        this.bootId = bootId;
        if ("auto".equals(mode)) {
            if (bootId == null) {
                outcome = STOPPED;
                log("nav auto suspended: boot ID unavailable");
                return;
            }
            try {
                prefs = context.getSharedPreferences("k8svm_nav", Context.MODE_PRIVATE);
                if (bootId.equals(prefs.getString("boot", null))) {
                    outcome = prefs.getString("result", null);
                    if (outcome != null && !"power".equals(outcome) && !"drvm".equals(outcome)
                            && !STOPPED.equals(outcome)) {
                        outcome = STOPPED;
                        log("nav auto suspended: unrecognized boot result");
                    }
                    if (outcome != null) log("nav auto restored for this boot: " + outcome);
                }
            } catch (RuntimeException e) {
                outcome = STOPPED;
                err("nav auto suspended: cannot read boot result", e);
            }
        }
    }

    static String effectiveMode(String saved) {
        // 0.1.8 could save its default "off" along with a width change. 0.1.9 uses "disabled" for opt-out.
        return saved;  // 0.1.10: field test showed power/drvm/menus cannot wake the SVM
    }

    static boolean supports(String name) {
        return "auto".equals(name) || "power".equals(name) || "drvm".equals(name);
    }

    void register() {
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(this);
        log("nav lifecycle ready " + mode);
    }

    @Override public void onActivityResumed(final Activity activity) {
        if (!ACTIVITY.equals(activity.getClass().getName()) || foreground == activity) return;
        if (foreground != null) onActivityPaused(foreground);
        changed();
        foreground = activity;
        if (suppressReturn || pending != null) {
            suppressReturn = false;
            log("nav skip camera return");
            return;
        }
        if (STOPPED.equals(outcome)) return;
        // Even a brief R/screen/view transition cancels an already armed foreground edge.
        final long entry = state.navInterruptions();
        final long readyUntil = SystemClock.uptimeMillis() + 3000;
        log("nav foreground " + state.describe());
        if (!state.canWaitForIdle(entry)) {
            log("nav skip " + state.describe());
            return;
        }
        check = new Runnable() {
            @Override public void run() {
                long rev = state.revision();
                if (foreground != activity || !state.canWaitForIdle(entry)) {
                    log("nav skip " + state.describe());
                } else if (state.at(rev, 1) && SystemClock.uptimeMillis() <= readyUntil) {
                    String candidate = mode;
                    if ("auto".equals(mode)) candidate = outcome == null ? "power" : outcome;
                    send(candidate, rev);
                } else if (SystemClock.uptimeMillis() < readyUntil) {
                    main.postDelayed(this, 200);  // wait for initial listener reports, no vehicle request
                } else {
                    log("nav skip initial status timeout " + state.describe());
                }
            }
        };
        main.postDelayed(check, 400);
    }

    private boolean arm(String candidate, long revision) {
        if (!state.at(revision, 1) || foreground == null) {
            log("nav skip before send " + state.describe());
            return false;
        }
        pending = candidate;
        attempt = revision;
        sameVisit = true;
        int wait = "auto".equals(mode) && outcome == null ? PROBE_MS : 6000;
        deadline = SystemClock.uptimeMillis() + wait;
        timeout = new Runnable() {
            @Override public void run() { timedOut(); }
        };
        main.postDelayed(timeout, wait);
        log("nav attempt " + candidate + " view=1");
        return true;
    }

    private void send(String candidate, long revision) {
        if (!state.at(revision, 1) || foreground == null) return;
        Object manager = null;
        java.lang.reflect.Method power = null;
        try {
            if ("power".equals(candidate)) {
                Class<?> pgs = context.getClassLoader().loadClass("com.hkmc.pgs.PgsManager");
                manager = pgs.getConstructor(Context.class).newInstance(context);
                power = pgs.getMethod("requestRearCameraPowerOn", boolean.class);
            }
        } catch (Throwable t) {
            if (arm(candidate, revision)) err("nav " + candidate + " unavailable", t);
            return;
        }
        if (!arm(candidate, revision)) return;
        try {
            if (!state.at(revision, 1)) { finish("cancelled before call"); return; }
            if (power != null) power.invoke(manager, true);
            else context.sendBroadcast(new Intent(DRVM).setPackage(CAMERA));
            log("nav call returned " + candidate);
        } catch (Throwable t) {
            // An exception may follow a partially delivered request. Observe the same window before fallback.
            err("nav " + candidate, t);
        }
    }

    void changed() {
        if (pending == null) return;
        if (state.observedStart(attempt, deadline)) {
            String winner = pending;
            if (foreground == null) suppressReturn = true;
            finish("observed 1->21");
            if ("auto".equals(mode) && outcome == null) remember(winner);
        } else if (!state.at(attempt, 1)) {
            finish("cancelled");
        }
    }

    private void timedOut() {
        // A listener may have captured the edge while this runnable was already queued.
        changed();
        if (pending == null) return;
        String tried = pending;
        long revision = attempt;
        boolean canContinue = sameVisit && foreground != null && state.at(revision, 1);
        finish("timeout");
        if (!canContinue || !"auto".equals(mode) || outcome != null) return;
        if ("power".equals(tried)) send("drvm", revision);
        else remember(STOPPED);
    }

    private void remember(String result) {
        outcome = result;
        log(STOPPED.equals(result) ? "nav auto both failed; stopped for this boot"
                : "nav auto selected " + result + " for this boot");
        try {
            if (!prefs.edit().putString("boot", bootId).putString("result", result).commit()) {
                log("nav could not save boot result to disk");
            }
        } catch (RuntimeException e) {
            err("nav could not save boot result to disk", e);
        }
    }

    private void finish(String reason) {
        main.removeCallbacks(timeout);
        timeout = null;
        attempt = -1;
        log("nav " + pending + " " + reason + " " + state.describe());
        pending = null;
    }

    @Override public void onActivityPaused(Activity activity) {
        if (activity != foreground) return;
        // Closing the camera should not immediately reopen it on the underlying map.
        suppressReturn = state.view() > 1 || state.observedStart(attempt, deadline);
        sameVisit = false;
        foreground = null;
        if (check != null) main.removeCallbacks(check);
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) { onActivityPaused(a); }
}
