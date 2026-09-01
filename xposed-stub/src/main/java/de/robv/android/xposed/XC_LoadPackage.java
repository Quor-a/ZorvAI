package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（compileOnly，不进 APK）。
 * 对应真实 API：de.robv.android.xposed.XC_LoadPackage。
 */
public final class XC_LoadPackage {
    private XC_LoadPackage() {
    }

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
