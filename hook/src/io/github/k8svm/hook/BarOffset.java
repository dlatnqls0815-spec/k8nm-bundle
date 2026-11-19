package io.github.k8svm.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;

import hmgapi.hook.Bridge;
import hmgapi.hook.MethodHook;

final class BarOffset {
    private static final int MAX_DY = 400;
    private static final HashSet<String> reportedErrors = new HashSet<String>();

    private static boolean installAttempted;
    private static int lastDy;

    private static Field[] resultFields;
    private static Field leftField;
    private static Field topField;
    private static Field rightField;
    private static Field bottomField;
    private static Constructor<?> boxConstructor;
    private static Constructor<?> resultConstructor;

    private BarOffset() {
    }

    static void install(ClassLoader appLoader) {
        synchronized (BarOffset.class) {
            if (installAttempted) {
                return;
            }
            installAttempted = true;
        }

        try {
            Class<?> boxClass = findClass(
                    "dev.ev6.nowplaying.BarPlacement$Box", appLoader);
            Class<?> resultClass = findClass(
                    "dev.ev6.nowplaying.BottomBarLayout$Result", appLoader);
            Class<?> layoutClass = findClass(
                    "dev.ev6.nowplaying.BottomBarLayout", appLoader);
            Method resolve = layoutClass.getDeclaredMethod(
                    "resolve", int.class, int.class, int.class, boolean.class, boxClass);

            synchronized (BarOffset.class) {
                resultFields = new Field[] {
                    resultClass.getField("row"),
                    resultClass.getField("artwork"),
                    resultClass.getField("details"),
                    resultClass.getField("previous"),
                    resultClass.getField("playPause"),
                    resultClass.getField("next")
                };
                leftField = boxClass.getField("left");
                topField = boxClass.getField("top");
                rightField = boxClass.getField("right");
                bottomField = boxClass.getField("bottom");
                boxConstructor = boxClass.getConstructor(
                        int.class, int.class, int.class, int.class);
                resultConstructor = resultClass.getDeclaredConstructor(
                        boxClass, boxClass, boxClass, boxClass, boxClass, boxClass);
                resultConstructor.setAccessible(true);
            }

            Bridge.hookMethod(resolve, new MethodHook() {
                @Override
                protected void afterHookedMethod(Param p) {
                    synchronized (BarOffset.class) {
                        try {
                            if (p.hasThrowable()) {
                                return;
                            }
                            Object original = p.getResult();
                            if (original == null) {
                                return;
                            }

                            int dy = readDy();
                            if (dy == 0) {
                                lastDy = 0;
                                return;
                            }

                            Object[] boxes = new Object[resultFields.length];
                            for (int i = 0; i < boxes.length; i++) {
                                Object box = resultFields[i].get(original);
                                boxes[i] = box;
                                if (box != null) {
                                    dy = Math.min(dy, Math.max(0, topField.getInt(box)));
                                }
                            }
                            if (dy == 0) {
                                lastDy = 0;
                                return;
                            }

                            for (int i = 0; i < boxes.length; i++) {
                                Object box = boxes[i];
                                if (box != null) {
                                    boxes[i] = boxConstructor.newInstance(
                                            leftField.getInt(box), topField.getInt(box) - dy,
                                            rightField.getInt(box), bottomField.getInt(box) - dy);
                                }
                            }

                            // 새 결과를 완성한 뒤 훅의 반환값을 교체한다.
                            Object replacement = resultConstructor.newInstance(boxes);
                            if (dy != lastDy) {
                                SvmOverlayEntry.log("bar offset " + dy);
                            }
                            p.setResult(replacement);
                            lastDy = dy;
                        } catch (Throwable failure) {
                            reportError(failure);
                        }
                    }
                }
            });
        } catch (Throwable failure) {
            try {
                SvmOverlayEntry.log("bar offset install failed: " + failure);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Class<?> findClass(String name, ClassLoader appLoader)
            throws ClassNotFoundException {
        try {
            return Class.forName(name, false, BarOffset.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError | SecurityException first) {
            return Class.forName(name, false, appLoader);
        }
    }

    private static int readDy() {
        String raw = Live.get("bar_up", "0");
        if (raw == null) {
            return 0;
        }

        String value = raw.trim();
        int dy = 0;
        int start = value.startsWith("+") ? 1 : 0;
        for (int i = start; i < value.length(); i++) {
            int digit = Character.digit(value.charAt(i), 10);
            if (digit < 0) {
                return 0;
            }
            // 자릿수마다 제한해 긴 정수도 오버플로 없이 읽는다.
            dy = Math.min(MAX_DY, dy * 10 + digit);
        }
        return dy;
    }

    private static void reportError(Throwable failure) {
        try {
            Throwable cause = failure;
            if (failure instanceof InvocationTargetException && failure.getCause() != null) {
                cause = failure.getCause();
            }

            String key = cause.getClass().getName() + ": " + cause.getMessage();
            if (reportedErrors.add(key)) {
                SvmOverlayEntry.err("bar offset hook failed", failure);
            }
        } catch (Throwable ignored) {
        }
    }
}
