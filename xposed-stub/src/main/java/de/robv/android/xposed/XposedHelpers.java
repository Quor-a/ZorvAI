package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（compileOnly，不进 APK）。
 * 对应真实 API：de.robv.android.xposed.XposedHelpers。
 *
 * 仅暴露模块实际用到的 findAndHookMethod 重载；其余省略。
 */
public class XposedHelpers {

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) throws Throwable {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) throws Throwable {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            String className, ClassLoader classLoader,
            Object... parameterTypesAndCallback) throws Throwable {
        return null;
    }
}
