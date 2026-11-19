package io.github.k8svm.hook;

/** Shared between the local binder callbacks and main-loop work in ONE app process. */
final class VehicleState {
    private boolean reverseKnown, reverse, screen, reverseListener, avmListener;
    private int view = -1;
    private long revision;
    private long navInterruptions;
    private long openedFromRevision = -1, openedAt;
    private boolean parkOk;
    private long parkSince;

    synchronized void listeners(boolean system, boolean avm) {
        reverseListener = system;
        avmListener = avm;
    }

    synchronized void reverse(boolean on) {
        if (on) parkOk = false;
        if (!reverseKnown || on != reverse) {
            revision++;
            if (on) navInterruptions++;
        }
        reverseKnown = true;
        reverse = on;
    }

    synchronized void screen(boolean on) {
        if (screen != on) {
            revision++;
            if (!on) navInterruptions++;
        }
        screen = on;
    }

    synchronized int changeView(int next) {
        int previous = view;
        if (next != view) {
            // Capture the safe edge on the listener thread, even if the main loop handles it later.
            parkOk = next == 2 && at(1);
            parkSince = android.os.SystemClock.uptimeMillis();
            if (next == 21 && at(1)) {
                openedFromRevision = revision;
                openedAt = android.os.SystemClock.uptimeMillis();
            }
            revision++;
            if (next != 1) navInterruptions++;
        }
        view = next;
        return previous;
    }

    synchronized long revision() { return revision; }
    /** View 2 opened from camera off while not reversing, still not reversing, and settled. */
    synchronized boolean parkPanel(long settleMs) {
        return parkOk && at(2) && android.os.SystemClock.uptimeMillis() - parkSince >= settleMs;
    }
    synchronized boolean observedStart(long ticket, long deadline) {
        return ticket >= 0 && ticket == openedFromRevision && openedAt <= deadline;
    }
    synchronized boolean needsReverseStatus() { return !reverseKnown; }
    synchronized boolean inReverse() { return reverse; }
    synchronized boolean confirmPark(boolean parked) {
        if (!parked || reverseKnown) return false;
        reverse(false);
        return true;
    }
    synchronized long navInterruptions() { return navInterruptions; }
    synchronized boolean canWaitForIdle(long entry) {
        return entry == navInterruptions && reverseListener && avmListener && screen && !reverse
                && (view == -1 || view == 1);
    }
    synchronized int view() { return view; }
    synchronized boolean at(int expected) {
        return reverseListener && avmListener && reverseKnown && !reverse && screen && view == expected;
    }
    synchronized boolean at(long ticket, int expected) {
        return revision == ticket && at(expected);
    }
    synchronized String describe() {
        return "view=" + view + " reverse=" + (reverseKnown ? String.valueOf(reverse) : "unknown")
                + " screen=" + screen + " listeners=" + reverseListener + "/" + avmListener;
    }
}
