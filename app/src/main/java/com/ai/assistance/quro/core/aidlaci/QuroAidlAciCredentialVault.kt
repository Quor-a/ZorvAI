package com.ai.assistance.quro.core.aidlaci

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ACI 凭证保险库（原创，P0 治理项）。
 *
 * 让 http_request 等敏感能力「安全地托管凭证」，而不是每次由 LLM 以明文传入
 * Bearer Token / API Key / Cookie。外部只需传引用名 "$vault:NAME"，
 * 真实凭证以 AndroidKeyStore 主密钥 AES-GCM 加密后落盘 filesDir/aci_credentials.json。
 *
 * 设计：
 * - 主密钥存于 AndroidKeyStore（别名 aci_cred_kv1），永不导出，即使文件被读也无法解密；
 * - 每条凭证独立随机 IV（GCM 12B）+ 加密，Base64 存储；
 * - resolve() 识别 "$vault:NAME" 返回明文；非该前缀原样返回，兼容旧明文用法。
 *
 * 注意：本保险库仅防护「文件被读取」场景，主密钥受 Android 锁屏/硬件密钥库保护；
 * rooted 设备不在威胁模型内（与 AndroidKeyStore 通用前提一致）。
 */
object QuroAidlAciCredentialVault {
    private const val KS_ALIAS = "aci_cred_kv1"
    private const val FILE = "aci_credentials.json"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val PREFIX = "\$vault:"

    private fun key(context: Context): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KS_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            kg.generateKey()
        }
        return ks.getKey(KS_ALIAS, null) as SecretKey
    }

    private fun encrypt(context: Context, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(context))
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(enc, 0, out, iv.size, enc.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, blob: String): String {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, IV_LEN)
        val enc = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }

    /** 托管一条凭证（同名覆盖）。value 为真实明文凭证。 */
    fun store(context: Context, name: String, value: String) {
        val file = File(context.filesDir, FILE)
        val root = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val arr = root.optJSONArray("creds") ?: JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("name") == name) { arr.remove(i); break }
        }
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("blob", encrypt(context, value))
        arr.put(obj)
        root.put("creds", arr)
        file.writeText(root.toString())
    }

    /** 解析：以 "$vault:NAME" 开头的输入返回托管明文；否则原样返回（兼容旧明文用法）。 */
    fun resolve(context: Context, input: String?): String? {
        if (input == null) return null
        if (input.startsWith(PREFIX)) {
            val name = input.substring(PREFIX.length)
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return input
            val arr = runCatching { JSONObject(file.readText()).optJSONArray("creds") }.getOrNull() ?: return input
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name") == name) {
                    return runCatching { decrypt(context, o.optString("blob")) }.getOrNull() ?: input
                }
            }
            return input
        }
        return input
    }

    /** 已托管凭证名列表（供 UI / 调试展示，不暴露明文）。 */
    fun list(context: Context): List<String> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        val arr = runCatching { JSONObject(file.readText()).optJSONArray("creds") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
    }

    /** 删除一条托管凭证，成功返回 true。 */
    fun delete(context: Context, name: String): Boolean {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return false
        val root = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val arr = root.optJSONArray("creds") ?: return false
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("name") == name) {
                arr.remove(i)
                root.put("creds", arr)
                file.writeText(root.toString())
                return true
            }
        }
        return false
    }
}
