package ai.aci.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ACI 受控端 Service 基类。受控方继承本类，实现 onCreateCapabilities / onCall 即可暴露能力。
 * 抽象方法与受保护钩子签名还原自原始 aci-core 编译产物（BaseACIService.class）。
 */
public abstract class BaseACIService extends Service {
    private static final String TAG = "BaseACIService";

    private final List<Capability> capabilities = new java.util.ArrayList<>();
    private final ExecutorService asyncPool = Executors.newCachedThreadPool();
    private final Map<String, IACICallback> callbackMap = new ConcurrentHashMap<>();
    private final IACIService.Stub binder = new IACIService.Stub() {
        @Override
        public ACIResponse call(ACIRequest request) {
            return handleCall(request, null);
        }

        @Override
        public void callAsync(ACIRequest request, IACICallback callback) {
            handleCall(request, callback);
        }

        @Override
        public String[] getCapabilities() {
            List<String> json = new java.util.ArrayList<>();
            for (Capability c : capabilities) {
                try {
                    json.add(c.toJSON().toString());
                } catch (Exception e) {
                    Log.w(TAG, "capability toJSON failed", e);
                }
            }
            return json.toArray(new String[0]);
        }

        @Override
        public boolean ping() {
            return true;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        onCreateCapabilities(capabilities);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        asyncPool.shutdownNow();
        super.onDestroy();
    }

    protected abstract void onCreateCapabilities(List<Capability> capabilities);

    protected abstract ACIResponse onCall(ACIRequest request);

    protected void onCallAsync(ACIRequest request, IACICallback callback) {
        ACIResponse resp = onCall(request);
        sendResult(callback, resp);
    }

    protected boolean onCheckPermission(ACIRequest request, String permission) {
        return true;
    }

    protected void onBeforeCall(ACIRequest request) {
    }

    protected void onAfterCall(ACIRequest request, ACIResponse response) {
    }

    private ACIResponse handleCall(ACIRequest request, IACICallback callback) {
        if (request == null) {
            ACIResponse err = ACIResponse.error(ACIError.REQUEST_NULL, "request is null");
            if (callback != null) sendResult(callback, err);
            return err;
        }
        if (!hasCapability(request.getCapability())) {
            ACIResponse err = ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND,
                    "unknown: " + request.getCapability());
            if (callback != null) sendResult(callback, err);
            return err;
        }
        // 鉴权钩子：把调用方包名传给 onCheckPermission，由受控方（子类）按白名单裁决。
        // 注意：签名为 (ACIRequest, String callerPkg)，与子类 override
        // onCheckPermission(req: ACIRequest?, callerPkg: String?) 一致；
        // 原先错传 cap.getRequirePermission() 会导致白名单永远命中不到 callerPkg。
        if (!onCheckPermission(request, request.getCallerPkg())) {
            ACIResponse err = ACIResponse.error(ACIError.PERMISSION_DENIED,
                    "permission denied: caller=" + request.getCallerPkg());
            if (callback != null) sendResult(callback, err);
            return err;
        }
        onBeforeCall(request);
        if (callback != null) {
            final IACICallback cb = callback;
            final ACIRequest req = request;
            asyncPool.execute(() -> {
                try {
                    onCallAsync(req, cb);
                } catch (Throwable e) {
                    Log.e(TAG, "callAsync failed", e);
                    safeCallbackError(cb, ACIError.INTERNAL_ERROR,
                            e != null ? e.getMessage() : "unknown");
                }
            });
            return null;
        } else {
            ACIResponse resp = onCall(request);
            onAfterCall(request, resp);
            return resp;
        }
    }

    private boolean hasCapability(String id) {
        for (Capability c : capabilities) {
            if (c.getId() != null && c.getId().equals(id)) return true;
        }
        return false;
    }

    private Capability findCapability(String id) {
        for (Capability c : capabilities) {
            if (c.getId() != null && c.getId().equals(id)) return c;
        }
        return null;
    }

    private void safeCallbackError(IACICallback callback, int code, String msg) {
        try {
            callback.onResult(ACIResponse.error(code, msg));
        } catch (RemoteException e) {
            Log.w(TAG, "safeCallbackError failed", e);
        }
    }

    protected List<Capability> getCapabilitiesList() {
        return capabilities;
    }

    protected void reportProgress(IACICallback callback, int progress, String message) {
        if (callback == null) return;
        try {
            callback.onProgress(progress, message);
        } catch (RemoteException e) {
            Log.w(TAG, "reportProgress failed", e);
        }
    }

    protected void sendResult(IACICallback callback, ACIResponse response) {
        if (callback == null) return;
        try {
            callback.onResult(response);
        } catch (RemoteException e) {
            Log.w(TAG, "sendResult failed", e);
        }
    }
}
