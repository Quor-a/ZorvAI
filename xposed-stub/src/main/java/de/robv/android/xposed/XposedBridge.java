package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（compileOnly，不进 APK）。
 * 对应真实 API：de.robv.android.xposed.XposedBridge。
 */
public class XposedBridge {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = 10000;
    public static final int PRIORITY_HIGHEST = -10000;

    public static void log(String msg) {
    }

    public static void log(Throwable t) {
    }
}
