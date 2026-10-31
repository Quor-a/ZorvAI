package com.ai.assistance.quro.core.adb.mika

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

/**
 * Android 上的 adb 身份与 TLS（移植自 Mika，去品牌化）。
 *
 * 钥匙用 AndroidKeyStore 里的 AES 密钥包装后放在 App 私有目录（noBackup），
 * 导出密钥走平台 `SSLSockets.exportKeyingMaterial`（API 30+，正好与无线调试的系统要求一致）。
 */
object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "quro_adb_key_wrap"

    // 设备端导出时不带 context;TLS 1.3 里空 context 与不带 context 的结果相同(RFC 8446 §7.5)
    val exporter = KeyingMaterialExporter { socket, label, length ->
        SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length) ?: throw SSLException("keying material export failed")
    }

    fun keyStore(context: Context): AdbKeyStore =
        WrappedKeyFile(File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")) { wrappingKey() }

    fun identity(context: Context): AdbIdentity = AdbIdentity.loadOrCreate(keyStore(context), "quro@${Build.MODEL}")

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    /** 删除钥匙(「解除配对」用);设备那边的授权记录要由用户在系统设置里撤销。 */
    fun forget(context: Context) {
        keyStore(context).clear()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
