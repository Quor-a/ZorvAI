package ai.aidl.aci.core;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/**
 * ACI Token 验证器（应用层认证）。
 * 
 * Token 认证机制：
 * 1. 控制端在调用时在 params 中添加 "_aci_token" 字段
 * 2. 受控端在 onCheckPermission 中验证 Token
 * 3. Token 通过 AndroidKeyStore 加密存储，安全可靠
 * 
 * 使用方式：
 * - 控制端：AciTokenManager.getToken(context, targetPackage) 获取 Token 并设置到 params
 * - 受控端：AciTokenVerifier.verify(context, request) 验证 Token
 */
public class AciTokenVerifier {
    private static final String TAG = "AciTokenVerifier";
    private static final String TOKEN_KEY = "_aci_token";
    private static final String TOKEN_PREFIX = "aci_token_";
    
    /**
     * 从请求中提取 Token
     */
    public static String extractToken(AidlAciRequest request) {
        if (request == null || request.getParams() == null) {
            return null;
        }
        return request.getParams().getString(TOKEN_KEY);
    }
    
    /**
     * 验证 Token 是否有效
     * @param context 上下文
     * @param request ACI 请求
     * @param expectedToken 期望的 Token（受控端配置）
     * @return 验证结果
     */
    public static TokenResult verify(Context context, AidlAciRequest request, String expectedToken) {
        String token = extractToken(request);
        
        // 如果没有 Token，返回需要 Token
        if (token == null || token.isEmpty()) {
            return TokenResult.missing("请求缺少 ACI Token，请在调用时添加 _aci_token 参数");
        }
        
        // 验证 Token
        if (expectedToken != null && !expectedToken.isEmpty()) {
            if (token.equals(expectedToken)) {
                return TokenResult.success("Token 验证通过");
            } else {
                return TokenResult.invalid("Token 无效或已过期");
            }
        }
        
        // 如果没有配置期望 Token，允许通过（兼容模式）
        return TokenResult.success("Token 验证通过（兼容模式）");
    }
    
    /**
     * 验证 Token（简化版，使用默认验证逻辑）
     */
    public static TokenResult verify(Context context, AidlAciRequest request) {
        // 默认验证逻辑：检查 Token 是否存在且非空
        String token = extractToken(request);
        if (token == null || token.isEmpty()) {
            return TokenResult.missing("请求缺少 ACI Token");
        }
        
        // 基本格式验证
        if (token.startsWith(TOKEN_PREFIX) && token.length() > TOKEN_PREFIX.length()) {
            return TokenResult.success("Token 格式正确");
        }
        
        return TokenResult.invalid("Token 格式无效");
    }
    
    /**
     * 生成 Token
     * @param packageName 目标应用包名
     * @return Token 字符串
     */
    public static String generateToken(String packageName) {
        return TOKEN_PREFIX + packageName + "_" + System.currentTimeMillis();
    }
    
    /**
     * Token 验证结果
     */
    public static class TokenResult {
        private final boolean success;
        private final String message;
        private final ResultCode code;
        
        private TokenResult(boolean success, String message, ResultCode code) {
            this.success = success;
            this.message = message;
            this.code = code;
        }
        
        public static TokenResult success(String message) {
            return new TokenResult(true, message, ResultCode.SUCCESS);
        }
        
        public static TokenResult missing(String message) {
            return new TokenResult(false, message, ResultCode.MISSING);
        }
        
        public static TokenResult invalid(String message) {
            return new TokenResult(false, message, ResultCode.INVALID);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
        
        public ResultCode getCode() {
            return code;
        }
        
        public enum ResultCode {
            SUCCESS,
            MISSING,
            INVALID
        }
    }
}
