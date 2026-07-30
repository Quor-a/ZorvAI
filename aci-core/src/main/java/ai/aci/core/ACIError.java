package ai.aci.core;

/**
 * ACI 标准错误码
 */
public final class ACIError {
    private ACIError() {}

    public static final int SUCCESS                = 0;
    public static final int REQUEST_NULL           = -1;
    public static final int BAD_REQUEST            = 400;
    public static final int PERMISSION_DENIED      = 403;
    public static final int CAPABILITY_NOT_FOUND   = 404;
    public static final int INTERNAL_ERROR         = 500;
    public static final int SERVICE_UNAVAILABLE    = 503;
    public static final int TIMEOUT                = 504;
    public static final int BINDER_DIED            = 505;

    public static String message(int code) {
        switch (code) {
            case SUCCESS:               return "OK";
            case REQUEST_NULL:          return "Request is null";
            case BAD_REQUEST:           return "Bad request format";
            case PERMISSION_DENIED:     return "Permission denied";
            case CAPABILITY_NOT_FOUND:   return "Capability not found";
            case INTERNAL_ERROR:         return "Internal server error";
            case SERVICE_UNAVAILABLE:    return "Service unavailable";
            case TIMEOUT:                return "Request timeout";
            case BINDER_DIED:            return "Binder connection died";
            default:                      return "Unknown error (" + code + ")";
        }
    }
}
