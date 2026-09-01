package de.robv.android.xposed;

/**
 * 最小化 Xposed API 桩（compileOnly，不进 APK）。
 * 对应真实 API：de.robv.android.xposed.XC_MethodHook。
 */
public abstract class XC_MethodHook extends XCallback {

    public XC_MethodHook() {
        super();
    }

    public XC_MethodHook(int priority) {
        super(priority);
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam extends XCallback.Param {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;

        public void setResult(Object result) {
            this.result = result;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }
    }

    public static final class Unhook {
        public Unhook() {
        }
    }
}
