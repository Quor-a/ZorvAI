package ai.aidl.aci.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * ACI Token 管理器（应用层认证）。
 * 
 * 功能：
 * 1. 生成和分发 Token
 * 2. 存储和检索 Token
 * 3. 验证 Token 有效性
 * 
 * Token 格式：aci_token_{ownerPackage}_{timestamp}_{random}
 *   - ownerPackage = 本 AciTokenManager 所属 App 自身包名（调用方/caller 身份），用于受控端校验"是谁在调用"
 *   - 不是传入的 target packageName（被调方）。token 应标识调用方，而不是被调方。
 * 存储方式：AndroidKeyStore 加密 + SharedPreferences 存储
 */
public class AciTokenManager {
    private static final String TAG = "AciTokenManager";
    private static final String PREFS_NAME = "aci_tokens";
    private static final String KEY_PREFIX = "aci_token_";
    private static final String KS_ALIAS = "aci_token_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    
    private static volatile AciTokenManager sInstance;
    private final Context appContext;
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();
    
    private AciTokenManager(Context context) {
        this.appContext = context.getApplicationContext();
    }
    
    public static AciTokenManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AciTokenManager.class) {
                if (sInstance == null) {
                    sInstance = new AciTokenManager(context);
                }
            }
        }
        return sInstance;
    }
    
    /**
     * 为目标应用生成或获取 Token
     * @param packageName 目标应用包名
     * @return Token 字符串
     */
    public String getOrCreateToken(String packageName) {
        // 先从缓存获取
        String token = tokenCache.get(packageName);
        if (token != null) {
            return token;
        }
        
        // 从存储获取
        token = loadToken(packageName);
        if (token != null) {
            tokenCache.put(packageName, token);
            return token;
        }
        
        // 生成新 Token
        token = generateToken(packageName);
        saveToken(packageName, token);
        tokenCache.put(packageName, token);
        
        Log.i(TAG, "生成新 Token: " + packageName);
        return token;
    }
    
    /**
     * 验证 Token 是否有效
     * @param packageName 目标应用包名
     * @param token 待验证的 Token
     * @return 验证结果
     */
    public boolean verifyToken(String packageName, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        String storedToken = getOrCreateToken(packageName);
        return token.equals(storedToken);
    }
    
    /**
     * 撤销 Token
     * @param packageName 目标应用包名
     */
    public void revokeToken(String packageName) {
        tokenCache.remove(packageName);
        deleteToken(packageName);
        Log.i(TAG, "已撤销 Token: " + packageName);
    }
    
    /**
     * 生成 Token
     *
     * 注意：token 中嵌入的是【本 AciTokenManager 所属 App 自身】的包名（caller 身份，即控制端），
     * 用于受控端校验"是谁在调用"，而不是传入的 target packageName（被调方）。
     * packageName 参数仅作为本地存储 key 使用。
     * random 用 URL_SAFE + NO_PADDING，去掉结尾 '='，可直接放进 header / URL。
     */
    private String generateToken(String packageName) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String randomStr = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING);
        String owner = appContext.getPackageName();
        return KEY_PREFIX + owner + "_" + System.currentTimeMillis() + "_" + randomStr;
    }
    
    /**
     * 加密 Token
     */
    private String encryptToken(String token) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(token.getBytes());
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.encodeToString(result, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "加密 Token 失败", e);
            return token; // 失败时返回原 Token
        }
    }
    
    /**
     * 解密 Token
     */
    private String decryptToken(String encryptedToken) {
        try {
            byte[] data = Base64.decode(encryptedToken, Base64.NO_WRAP);
            byte[] iv = new byte[IV_LEN];
            byte[] encrypted = new byte[data.length - IV_LEN];
            System.arraycopy(data, 0, iv, 0, iv.length);
            System.arraycopy(data, iv.length, encrypted, 0, encrypted.length);
            
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted);
        } catch (Exception e) {
            Log.e(TAG, "解密 Token 失败", e);
            return encryptedToken; // 失败时返回原 Token
        }
    }
    
    /**
     * 获取或创建密钥
     */
    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        
        if (ks.containsAlias(KS_ALIAS)) {
            return (SecretKey) ks.getKey(KS_ALIAS, null);
        }
        
        KeyGenerator kg = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, 
            "AndroidKeyStore"
        );
        kg.init(new KeyGenParameterSpec.Builder(
            KS_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        );
        return kg.generateKey();
    }
    
    /**
     * 保存 Token
     */
    private void saveToken(String packageName, String token) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encrypted = encryptToken(token);
        prefs.edit().putString(packageName, encrypted).apply();
    }
    
    /**
     * 加载 Token
     */
    private String loadToken(String packageName) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encrypted = prefs.getString(packageName, null);
        if (encrypted != null) {
            return decryptToken(encrypted);
        }
        return null;
    }
    
    /**
     * 删除 Token
     */
    private void deleteToken(String packageName) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(packageName).apply();
    }
}
