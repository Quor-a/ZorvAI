package ai.aci.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BaseACIService —— 第三方 App 的入口（增强版 v1.1）
 *
 * ══════════════════════════════════════════════════════════════
 *  第三方开发者只需做两件事：
 *
 *  ① 继承 BaseACIService
 *  ② 重写 onCall(ACIRequest) 处理调用
 *
 *  ══════════════════════════════════════════════════════════════
 *  进阶用法：
 *
 *  • 重写 onCallAsync() 支持异步执行 + 进度回调
 *  • 重写 onCheckPermission() 做自定义鉴权
 *  • 重写 onBeforeCall() / onAfterCall() 做日志/限流
 *
 *  ══════════════════════════════════════════════════════════════
 */
public abstract class BaseACIService extends Service {

    private static final String TAG = "BaseACIService";

    // 子类注册的能力列表
    private final List<Capability> capabilities = new ArrayList<>();

    // 异步执行线程池
    private final ExecutorService asyncPool = Executors.newCachedThreadPool();

    // 异步回调管理（callId → callback）
    private final Map<String, IACICallback> callbackMap = new ConcurrentHashMap<>();

    // ═══════════════════════════════════
    // 子类必须重写
    // ═══════════════════════════════════

    /** 声明自己支持哪些能力 */
    protected abstract void onCreateCapabilities(List<Capability> capabilities);

    /** 处理同步调用 */
    protected abstract ACIResponse onCall(ACIRequest request);

    // ═══════════════════════════════════
    // 子类可选重写
    // ═══════════════════════════════════

    /**
     * 处理异步调用（默认实现：切线程后调 onCall，再回调）
     * 子类可重写此方法以实现真正的异步 + 进度上报
     */
    protected void onCallAsync(ACIRequest request, IACICallback callback) {
        asyncPool.execute(() -> {
            try {
                // 上报进度
                if (callback != null) {
                    callback.onProgress(10, "开始处理...");
                }

                ACIResponse resp = onCall(request);
                if (resp == null) resp = ACIResponse.error(ACIError.INTERNAL_ERROR, "onCall returned null");

                if (callback != null) {
                    callback.onProgress(100, "完成");
                    callback.onResult(resp);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Callback failed", e);
            } catch (Exception e) {
                Log.e(TAG, "onCallAsync exception", e);
                try {
                    if (callback != null) {
                        callback.onResult(ACIResponse.error(ACIError.INTERNAL_ERROR, e.getMessage()));
                    }
                } catch (RemoteException ignored) {}
            }
        });
    }

    /** 权限校验（默认放行） */
    protected boolean onCheckPermission(ACIRequest request, String callerPkg) {
        return true;
    }

    /** 调用前钩子 */
    protected void onBeforeCall(ACIRequest request) {
        Log.d(TAG, "[" + getClass().getSimpleName() + "] → " + request.getCapability()
                + " from=" + request.getCallerPkg());
    }

    /** 调用后钩子 */
    protected void onAfterCall(ACIRequest request, ACIResponse response) {
        Log.d(TAG, "[" + getClass().getSimpleName() + "] ← " + response.toString());
    }

    // ═══════════════════════════════════
    // AIDL 实现（SDK 内部逻辑）
    // ═══════════════════════════════════
    private final IACIService.Stub binder = new IACIService.Stub() {

        @Override
        public ACIResponse call(ACIRequest request) {
            return handleCall(request, null);
        }

        @Override
        public void callAsync(ACIRequest request, IACICallback callback) {
            if (request == null) {
                safeCallbackError(callback, ACIError.REQUEST_NULL, "Request is null");
                return;
            }

            // 权限校验
            if (!onCheckPermission(request, request.getCallerPkg())) {
                safeCallbackError(callback, ACIError.PERMISSION_DENIED, "Permission denied");
                return;
            }

            // 能力白名单
            if (!hasCapability(request.getCapability())) {
                safeCallbackError(callback, ACIError.CAPABILITY_NOT_FOUND,
                        "Capability not found: " + request.getCapability());
                return;
            }

            onBeforeCall(request);

            // 保存回调引用
            if (callback != null) {
                callbackMap.put(request.getCallId(), callback);
            }

            // 执行异步调用
            onCallAsync(request, new IACICallback.Stub() {
                @Override
                public void onResult(ACIResponse response) throws RemoteException {
                    if (response != null) {
                        response.setCallId(request.getCallId());
                    }
                    onAfterCall(request, response != null ? response : ACIResponse.error(500, "null"));
                    callbackMap.remove(request.getCallId());
                    if (callback != null) {
                        callback.onResult(response != null ? response : ACIResponse.error(500, "null"));
                    }
                }

                @Override
                public void onProgress(int progress, String message) throws RemoteException {
                    if (callback != null) {
                        callback.onProgress(progress, message);
                    }
                }
            });
        }

        @Override
        public String[] getCapabilities() {
            String[] arr = new String[capabilities.size()];
            for (int i = 0; i < capabilities.size(); i++) {
                try {
                    arr[i] = capabilities.get(i).toJSON().toString();
                } catch (Exception e) {
                    arr[i] = "{}";
                }
            }
            return arr;
        }

        @Override
        public boolean ping() {
            return true;
        }
    };

    // ═══════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════
    private ACIResponse handleCall(ACIRequest request, IACICallback callback) {
        if (request == null) {
            return ACIResponse.error(ACIError.REQUEST_NULL, ACIError.message(ACIError.REQUEST_NULL));
        }

        // 权限校验
        if (!onCheckPermission(request, request.getCallerPkg())) {
            return ACIResponse.error(ACIError.PERMISSION_DENIED, ACIError.message(ACIError.PERMISSION_DENIED));
        }

        // 能力白名单
        if (!hasCapability(request.getCapability())) {
            return ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND,
                    "Capability not found: " + request.getCapability());
        }

        onBeforeCall(request);

        // 执行
        ACIResponse response;
        try {
            response = onCall(request);
            if (response == null) {
                response = ACIResponse.error(ACIError.INTERNAL_ERROR, "onCall returned null");
            }
        } catch (Exception e) {
            Log.e(TAG, "onCall exception", e);
            response = ACIResponse.error(ACIError.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }

        response.setCallId(request.getCallId());
        onAfterCall(request, response);

        return response;
    }

    private boolean hasCapability(String id) {
        for (Capability c : capabilities) {
            if (c.getId().equals(id)) return true;
        }
        return false;
    }

    private void safeCallbackError(IACICallback cb, int code, String msg) {
        if (cb == null) return;
        try {
            cb.onResult(ACIResponse.error(code, msg));
        } catch (RemoteException ignored) {}
    }

    // ═══════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();
        onCreateCapabilities(capabilities);
        Log.i(TAG, "[" + getClass().getSimpleName() + "] ✅ created, capabilities=" + capabilities.size());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 清理异步回调
        callbackMap.clear();
        Log.i(TAG, "[" + getClass().getSimpleName() + "] unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        asyncPool.shutdownNow();
        callbackMap.clear();
        super.onDestroy();
    }

    // ═══════════════════════════════════
    // 提供给子类的工具方法
    // ═══════════════════════════════════

    /** 获取已注册的能力列表（子类可读取） */
    protected List<Capability> getCapabilitiesList() {
        return new ArrayList<>(capabilities);
    }

    /** 上报进度（在重写 onCallAsync 时使用） */
    protected void reportProgress(IACICallback callback, int progress, String message) {
        if (callback == null) return;
        try { callback.onProgress(progress, message); } catch (RemoteException ignored) {}
    }

    /** 安全返回结果（在重写 onCallAsync 时使用） */
    protected void sendResult(IACICallback callback, ACIResponse response) {
        if (callback == null) return;
        try { callback.onResult(response); } catch (RemoteException ignored) {}
    }
}
