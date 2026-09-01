package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（compileOnly，不进 APK）。
 * 对应真实 API：de.robv.android.xposed.IXposedHookLoadPackage。
 */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
