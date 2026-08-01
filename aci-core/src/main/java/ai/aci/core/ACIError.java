package ai.aci.core;

/**
 * ACI 标准错误码
 *
 * 命名空间约定：
 * - 0 / 400 / 403 / 404 / 500 / 503 / 504 / 505  → ACI 内核标准码（传输/绑定层）
 * - 15xx → 服务/协议治理层（绑定未就绪、协商失败、调用失败等）
 * - 24xx → 请求语义层（坏参数、缺少字段等客户端可纠正错误）
 * - 25xx → HTTP 传输层（受控端 http_request 透传的客户端/网络/TLS 错误）
 * 控制端（如 ZorvAI 主程序）的 QuroAciErrors 复用同一命名空间，避免与内核码冲突。
 */
public final class ACIError {
    private ACIError() {}

    // ── 内核标准码（传输 / 绑定层，保持向后兼容）──
    public static final int SUCCESS                = 0;
    public static final int REQUEST_NULL           = -1;
    public static final int BAD_REQUEST            = 400;
    public static final int PERMISSION_DENIED      = 403;
    public static final int CAPABILITY_NOT_FOUND   = 404;
    public static final int INTERNAL_ERROR         = 500;
    public static final int SERVICE_UNAVAILABLE    = 503;
    public static final int TIMEOUT                = 504;
    public static final int BINDER_DIED            = 505;

    // ── 15xx：服务 / 协议治理层 ──
    public static final int SERVICE_UNBOUND        = 1503;  // 控制端调用时受控服务尚未绑定就绪
    public static final int PROTOCOL_NEGOTIATE_FAIL= 1505;  // 协议版本协商失败（无共同版本）
    public static final int CALL_FAILED            = 1506;  // 调用执行失败（非语义错误，泛化）

    // ── 24xx：请求语义层 ──
    public static final int BAD_REQUEST_PARAM      = 2400;  // 参数缺失/类型错误/不可解析
    public static final int MISSING_FIELD          = 2401;  // 必填字段缺失
    public static final int UNSUPPORTED_PROTOCOL   = 2403;  // 请求的协议版本不被支持

    // ── 25xx：HTTP 传输层（http_request 透传）──
    public static final int HTTP_CLIENT_ERROR      = 2500;  // 4xx 客户端错误（透传 statusCode）
    public static final int HTTP_SERVER_ERROR      = 2510;  // 5xx 服务端错误（透传 statusCode）
    public static final int HTTP_DNS_FAILED        = 2521;  // DNS 解析失败
    public static final int HTTP_TLS_ERROR         = 2522;  // TLS/证书校验失败
    public static final int HTTP_CONNECT_FAILED    = 2523;  // 连接失败（拒绝/超时/不可达）
    public static final int HTTP_TOO_LARGE         = 2524;  // 响应体超限（前文上限）
    public static final int HTTP_UNKNOWN           = 2599;  // 其他未归类 HTTP 错误

    // ── ACI 协议版本常量 ──
    public static final String PROTOCOL_V1         = "aci-protocol-v1";
    public static final String PROTOCOL_LATEST     = PROTOCOL_V1;

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
            case SERVICE_UNBOUND:        return "ACI service not bound / not ready";
            case PROTOCOL_NEGOTIATE_FAIL:return "Protocol version negotiation failed";
            case CALL_FAILED:            return "ACI call failed";
            case BAD_REQUEST_PARAM:      return "Bad request parameter";
            case MISSING_FIELD:          return "Required field missing";
            case UNSUPPORTED_PROTOCOL:   return "Unsupported ACI protocol version";
            case HTTP_CLIENT_ERROR:      return "HTTP client error (4xx)";
            case HTTP_SERVER_ERROR:      return "HTTP server error (5xx)";
            case HTTP_DNS_FAILED:        return "HTTP DNS resolution failed";
            case HTTP_TLS_ERROR:         return "HTTP TLS / certificate verification failed";
            case HTTP_CONNECT_FAILED:    return "HTTP connection failed";
            case HTTP_TOO_LARGE:         return "HTTP response body too large";
            case HTTP_UNKNOWN:           return "Unknown HTTP error";
            default:                      return "Unknown error (" + code + ")";
        }
    }

    public static boolean isAciProtocol(int code) {
        return code >= 1500 && code < 2600;
    }
}
