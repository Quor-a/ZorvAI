package ai.aidl.aci.core;

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
 * 抽象方法与受保护钩子签名还原自原始 aci-core 编译产物（BaseAidlAciService.class）。
 */
public abstract class BaseAidlAciService extends Service {
    private static final String TAG = "BaseAidlAciService";

    private final List<Capability> capabilities = new java.util.ArrayList<>();
    private final ExecutorService asyncPool = Executors.newCachedThreadPool();
    private final Map<String, IAidlAciCallback> callbackMap = new ConcurrentHashMap<>();
    /** 本机 LocalSocket 高速通道监听器（抽象命名空间），AIDL 的增强替代传输。 */
    private AidlAciLocalSocketTransport.Server lsServer;
    private final IAidlAciService.Stub binder = new IAidlAciService.Stub() {
        @Override
        public AidlAciResponse call(AidlAciRequest request) {
            return handleCall(request, null);
        }

        @Override
        public void callAsync(AidlAciRequest request, IAidlAciCallback callback) {
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
        // 启动本机 LocalSocket 高速通道（抽象命名空间，端点 = 自身包名）。
        // 与 AIDL 并存：控制端优先走 socket，失败回落 AIDL，二者共用 handleCall 派发。
        try {
            lsServer = new AidlAciLocalSocketTransport.Server(getPackageName(), this::dispatch);
            lsServer.start();
        } catch (Throwable e) {
            // 监听启动失败（如端点冲突）不致命：AIDL 仍可用
            Log.w(TAG, "LocalSocket 服务启动失败，仅用 AIDL：" + e.getMessage());
            lsServer = null;
        }
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
        if (lsServer != null) {
            try {
                lsServer.stop();
            } catch (Throwable ignored) {
            }
            lsServer = null;
        }
        asyncPool.shutdownNow();
        super.onDestroy();
    }

    /**
     * LocalSocket 通道的派发入口：复用 handleCall 的鉴权 + 能力校验 + onCall 链路。
     * 同步调用（callback=null）直接返回响应；与 AIDL 的 call() 走完全相同逻辑。
     */
    public AidlAciResponse dispatch(AidlAciRequest request) {
        return handleCall(request, null);
    }

    protected abstract void onCreateCapabilities(List<Capability> capabilities);

    protected abstract AidlAciResponse onCall(AidlAciRequest request);

    protected void onCallAsync(AidlAciRequest request, IAidlAciCallback callback) {
        AidlAciResponse resp = onCall(request);
        if (request.getCallId() != null) resp.setCallId(request.getCallId());
        sendResult(callback, resp);
    }

    protected boolean onCheckPermission(AidlAciRequest request, String permission) {
        return true;
    }

    protected void onBeforeCall(AidlAciRequest request) {
    }

    protected void onAfterCall(AidlAciRequest request, AidlAciResponse response) {
    }

    /** 把请求侧 callId 回显到响应，支撑调用链关联（可观测性）。 */
    private AidlAciResponse withCallId(AidlAciResponse r, String id) {
        if (id != null) r.setCallId(id);
        return r;
    }

    private AidlAciResponse handleCall(AidlAciRequest request, IAidlAciCallback callback) {
        if (request == null) {
            AidlAciResponse err = AidlAciResponse.error(AidlAciError.REQUEST_NULL, "request is null");
            if (callback != null) sendResult(callback, err);
            return err;
        }
        final String reqCallId = request.getCallId();
        if (!hasCapability(request.getCapability())) {
            AidlAciResponse err = AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND,
                    "unknown: " + request.getCapability());
            if (callback != null) sendResult(callback, err);
            return withCallId(err, reqCallId);
        }
        // 鉴权钩子：把调用方包名传给 onCheckPermission，由受控方（子类）按白名单裁决。
        // 注意：签名为 (AidlAciRequest, String callerPkg)，与子类 override
        // onCheckPermission(req: AidlAciRequest?, callerPkg: String?) 一致；
        // 原先错传 cap.getRequirePermission() 会导致白名单永远命中不到 callerPkg。
        if (!onCheckPermission(request, request.getCallerPkg())) {
            AidlAciResponse err = AidlAciResponse.error(AidlAciError.PERMISSION_DENIED,
                    "permission denied: caller=" + request.getCallerPkg());
            if (callback != null) sendResult(callback, err);
            return withCallId(err, reqCallId);
        }
        onBeforeCall(request);
        if (callback != null) {
            final IAidlAciCallback cb = callback;
            final AidlAciRequest req = request;
            asyncPool.execute(() -> {
                try {
                    onCallAsync(req, cb);
                } catch (Throwable e) {
                    Log.e(TAG, "callAsync failed", e);
                    safeCallbackError(cb, AidlAciError.INTERNAL_ERROR,
                            e != null ? e.getMessage() : "unknown");
                }
            });
            return null;
        } else {
            AidlAciResponse resp = onCall(request);
            if (reqCallId != null) resp.setCallId(reqCallId);
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

    private void safeCallbackError(IAidlAciCallback callback, int code, String msg) {
        try {
            callback.onResult(AidlAciResponse.error(code, msg));
        } catch (RemoteException e) {
            Log.w(TAG, "safeCallbackError failed", e);
        }
    }

    protected List<Capability> getCapabilitiesList() {
        return capabilities;
    }

    protected void reportProgress(IAidlAciCallback callback, int progress, String message) {
        if (callback == null) return;
        try {
            callback.onProgress(progress, message);
        } catch (RemoteException e) {
            Log.w(TAG, "reportProgress failed", e);
        }
    }

    protected void sendResult(IAidlAciCallback callback, AidlAciResponse response) {
        if (callback == null) return;
        try {
            callback.onResult(response);
        } catch (RemoteException e) {
            Log.w(TAG, "sendResult failed", e);
        }
    }
}
