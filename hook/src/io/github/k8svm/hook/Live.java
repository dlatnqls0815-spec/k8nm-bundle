package io.github.k8svm.hook;

/** Settings that change without a head unit restart: apply.sh copies them to world-readable persist.k8svm.* props (persist: survive restarts, so Seogaemo sees no drift). */
final class Live {
    private static java.lang.reflect.Method get;

    static String get(String key, String fallback) {
        try {
            if (get == null) get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            String v = (String) get.invoke(null, "persist.k8svm." + key, "");
            return v == null || v.length() == 0 ? fallback : v.trim();
        } catch (Throwable t) {
            return fallback;
        }
    }

    static boolean on(String key, boolean fallback) {
        String v = get(key, "");
        if ("on".equals(v) || "true".equals(v)) return true;
        if ("off".equals(v) || "false".equals(v)) return false;
        return fallback;
    }
}
