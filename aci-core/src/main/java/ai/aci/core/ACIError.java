package ai.aci.core;

/**
 * ACI 统一错误码定义。与 ACIResponse.error(code, message) 配套使用。
 * 这些值从原始 aci-core 编译产物（ACIError.class）反推，保持与既有协议一致。
 */
public final class ACIError {
    public static final int SUCCESS = 0;
    public static final int REQUEST_NULL = 1;
    public static final int BAD_REQUEST = 2;
    public static final int PERMISSION_DENIED = 3;
    public static final int CAPABILITY_NOT_FOUND = 4;
    public static final int INTERNAL_ERROR = 5;
    public static final int SERVICE_UNAVAILABLE = 6;
    public static final int TIMEOUT = 7;
    public static final int BINDER_DIED = 8;

    private ACIError() {
        // 工具类，禁止实例化
    }

    public static String message(int code) {
        switch (code) {
            case SUCCESS: return "Success";
            case REQUEST_NULL: return "Request is null";
            case BAD_REQUEST: return "Bad request";
            case PERMISSION_DENIED: return "Permission denied";
            case CAPABILITY_NOT_FOUND: return "Capability not found";
            case INTERNAL_ERROR: return "Internal error";
            case SERVICE_UNAVAILABLE: return "Service unavailable";
            case TIMEOUT: return "Timeout";
            case BINDER_DIED: return "Binder died";
            default: return "Unknown error (" + code + ")";
        }
    }
}
