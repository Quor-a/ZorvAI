package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（compileOnly，不进 APK）。
 * 对应真实 API：de.robv.android.xposed.XCallback。
 */
public abstract class XCallback {
    public int priority;

    public XCallback() {
        this.priority = 50; // XposedBridge.PRIORITY_DEFAULT
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static class Param {
        // 桩：真实 Param 含更多字段，但模块代码不直接使用，故省略。
    }
}
