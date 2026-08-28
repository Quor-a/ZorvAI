package com.ai.assistance.quro.core.aidlaci

import android.os.Bundle
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ACI 原生层桥接（供 C/C++ 经 JNI 调用）。
 *
 * 目的：让**原生代码**也能调用 ACI 的全部能力，而不必回到 Kotlin/Java 层写调用。
 * 由于 ACI 的接口本身就是通用的 `call(capability, params)`，本类只要把
 * 「调任意能力」和「列能力」两件事暴露成字符串进出的同步方法，
 * C 侧就能覆盖 ACI 的**全部**能力（包括将来新增的）。
 *
 * 调用方：`app/src/main/cpp/aci/aci_native.c`（libacihost.so，进程内 JNI 库）。
 *
 * 设计约定：
 * - 进出都用 JSON 字符串，避免在 JNI 层搬运 Bundle/Parcelable 这类复杂类型；
 * - 任何异常都在这里吞掉并转成 `ok=false` 的 JSON，**绝不向上抛**，
 *   否则 JNI 层一旦有 pending exception，后续调用会连锁崩溃；
 * - 全部是同步方法，由 C 侧自己决定放在哪个线程（不得在主线程调用耗时能力）。
 */
object AciNativeBridge {
    private const val TAG = "AciNativeBridge"

    @Volatile
    private var nativeLoaded = false

    /**
     * 加载 ACI 原生层 libacihost.so（幂等）。
     *
     * 加载后，C/C++ 侧即可 include "aci_native.h" 直接调用
     * [aci_available/aci_call/aci_list]。JNI_OnLoad 会把 JavaVM 存进原生层，
     * 之后任意原生线程都能 attach 回来调用 Java。
     *
     * 失败只记日志、不抛异常——原生层不可用时，ACI 的 Kotlin/Java 调用路径照常工作。
     */
    @JvmStatic
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (nativeLoaded) return true
        return try {
            System.loadLibrary("acihost")
            nativeLoaded = true
            Log.i(TAG, "libacihost.so 已加载，ACI 原生层可用")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libacihost.so 加载失败，ACI 原生层不可用: ${t.message}")
            false
        }
    }

    /** ACI 控制端是否可用。 */
    @JvmStatic
    fun isReady(): Boolean {
        return try {
            QuroAidlAciManager.getInstance() != null
        } catch (t: Throwable) {
            Log.w(TAG, "isReady 失败: ${t.message}")
            false
        }
    }

    /**
     * 调用指定受控端的任意能力。
     *
     * @param target 受控端包名
     * @param capability 能力 id
     * @param argsJson 参数 JSON 对象（键值均为标量）；可为空串
     * @param confirmed 已征得用户同意（用于 requireUserConfirm 的能力）
     * @return JSON：`{"ok":bool,"code":int,"error":string?,"data":{...}}`
     */
    @JvmStatic
    fun callJson(
        target: String,
        capability: String,
        argsJson: String,
        confirmed: Boolean,
    ): String {
        return try {
            val mgr = QuroAidlAciManager.getInstance()
            val bundle = jsonToBundle(argsJson)
            // 与控制端 aci_call 工具一致：确认令牌不作为参数传给业务，
            // 而是翻译成受控端期望的 user_confirmed，便于服务端纵深防御。
            if (confirmed) bundle.putBoolean("user_confirmed", true)

            val resp = mgr.call(target, capability, bundle)
            val out = JSONObject()
            out.put("ok", resp.isSuccess)
            out.put("code", resp.errorCode)
            if (!resp.isSuccess) out.put("error", resp.errorMessage ?: "unknown error")

            val res = resp.result
            if (res != null && !res.keySet().isEmpty()) {
                val data = JSONObject()
                for (k in res.keySet()) {
                    val v = res.get(k)
                    when (v) {
                        null -> data.put(k, JSONObject.NULL)
                        is ByteArray -> data.put(
                            k,
                            "base64:" + Base64.encodeToString(v, Base64.NO_WRAP)
                        )
                        is Number -> data.put(k, v)
                        is Boolean -> data.put(k, v)
                        else -> data.put(k, v.toString())
                    }
                }
                out.put("data", data)
            }
            out.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "callJson 失败", t)
            JSONObject()
                .put("ok", false)
                .put("code", -1)
                .put("error", t.message ?: "native bridge error")
                .toString()
        }
    }

    /**
     * 列出所有受控端及其能力。
     *
     * @return JSON：`{"ok":true,"targets":[{"package":..,"capabilities":[{"id","description","confirm"}]}]}`
     */
    @JvmStatic
    fun listJson(): String {
        return try {
            val mgr = QuroAidlAciManager.getInstance()
            val arr = JSONArray()
            mgr.getCapabilityIndex().forEach { (pkg, caps) ->
                val po = JSONObject()
                po.put("package", pkg)
                val ca = JSONArray()
                caps.forEach { c ->
                    val co = JSONObject()
                    co.put("id", c.id)
                    co.put("description", c.description ?: "")
                    co.put("confirm", c.isRequireUserConfirm)
                    ca.put(co)
                }
                po.put("capabilities", ca)
                arr.put(po)
            }
            JSONObject().put("ok", true).put("targets", arr).toString()
        } catch (t: Throwable) {
            Log.e(TAG, "listJson 失败", t)
            JSONObject()
                .put("ok", false)
                .put("error", t.message ?: "native bridge error")
                .toString()
        }
    }

    /** JSON 对象 → Bundle，按值类型映射（与控制端 aci_call 工具保持一致）。 */
    private fun jsonToBundle(argsJson: String?): Bundle {
        val b = Bundle()
        if (argsJson.isNullOrBlank()) return b
        return try {
            val o = JSONObject(argsJson)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = o.opt(k)) {
                    is Boolean -> b.putBoolean(k, v)
                    is Int -> b.putInt(k, v)
                    is Long -> b.putLong(k, v)
                    is Double -> b.putDouble(k, v)
                    is Float -> b.putFloat(k, v)
                    else -> b.putString(k, o.optString(k, ""))
                }
            }
            b
        } catch (t: Throwable) {
            Log.w(TAG, "argsJson 解析失败，按空参数继续: ${t.message}")
            b
        }
    }
}
