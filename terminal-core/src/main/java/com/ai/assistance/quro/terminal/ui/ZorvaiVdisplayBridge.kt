package com.ai.assistance.quro.terminal.ui

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * DSH 虚拟屏桥 → ZorvAI 自有虚拟屏（反射跨模块）。
 *
 * 背景：DSH 前端面板建屏/操作走上游 VdisplayController（保持不动），而 AI 经 androidBridge
 * 调用的 vdisplay* 此前全是 `vdisplay-not-wired` stub。用户要求「显示屏用 ZorvAI 的、
 * 功能仍走上游」：这些 bridge 调用改为落到 ZorvAI 的 QuroVirtualScreen（Shizuku 建屏 +
 * 自带悬浮窗显示），这样屏幕画面由 ZorvAI 显示组件呈现，而 DSH 面板功能不受影响。
 *
 * 为什么反射：QuroVirtualScreen 在 :app 模块，本类在 :terminal-core，app 单向依赖
 * terminal-core，不能编译期反向依赖。运行时两者在同一个 APK，类可见。
 * 反射失败一律 fail-closed 返回结构化错误，绝不假装成功。
 */
internal object ZorvaiVdisplayBridge {
    private const val TAG = "ZorvaiVdisplayBridge"
    private const val CLS = "com.ai.assistance.quro.core.display.QuroVirtualScreen"

    private fun instance(): Any? = try {
        val c = Class.forName(CLS)
        c.getField("INSTANCE").get(null)
    } catch (t: Throwable) {
        Log.w(TAG, "QuroVirtualScreen 不可达: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun call(inst: Any, name: String, vararg args: Any?): Any? {
        val types = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        // 精确签名失败时退化为按名字找（Kotlin 默认参数会生成合成方法）
        val m = try {
            inst.javaClass.getMethod(name, *types)
        } catch (_: NoSuchMethodException) {
            inst.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
        } ?: throw NoSuchMethodException(name)
        return m.invoke(inst, *args)
    }

    /** 读 state（StateFlow）里的 displayId / ready / error。 */
    private fun stateJson(inst: Any): JSONObject {
        val flow = call(inst, "getState") ?: return JSONObject().put("ready", false)
        // Kotlin `val state: StateFlow<UiState>` → getter 返回 StateFlow，需再 getValue() 取当前 UiState
        val st = try {
            flow.javaClass.getMethod("getValue").invoke(flow)
        } catch (_: Throwable) {
            flow // 万一实现直接返回 UiState，兼容
        }
        fun prop(name: String): Any? = try {
            st.javaClass.getMethod(name).invoke(st)
        } catch (_: Throwable) {
            null
        }
        val ready = prop("getReady") as? Boolean ?: false
        val displayId = (prop("getDisplayId") as? Number)?.toInt() ?: -1
        val error = prop("getError") as? String
        return JSONObject()
            .put("ready", ready)
            .put("displayId", displayId)
            .put("topActivity", prop("getTopActivity") as? String ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }

    fun status(): String = runCatching {
        val inst = instance() ?: return fail("zorvai-screen-unavailable")
        val json = stateJson(inst).put("ok", true).put("source", "zorvai")
        json.toString()
    }.getOrElse { fail("status-failed", it) }

    fun create(context: Context): String = runCatching {
        val inst = instance() ?: return fail("zorvai-screen-unavailable")
        // ensureDisplay 在 app 主线程/IO 均可；它内部用 Shizuku AIDL 建屏并自动拉起悬浮窗
        val display = call(inst, "ensureDisplay", context.applicationContext)
        if (display == null) {
            val err = runCatching {
                inst.javaClass.getMethod("getLastError").invoke(inst) as? String
            }.getOrNull()
            return JSONObject().put("ok", false).put("code", "zorvai-display-failed")
                .put("error", err ?: JSONObject.NULL).toString()
        }
        val json = stateJson(inst).put("ok", true).put("source", "zorvai")
        Log.i(TAG, "ZorvAI 虚拟屏已创建 displayId=${json.optInt("displayId")}")
        json.toString()
    }.getOrElse { fail("create-failed", it) }

    fun destroy(): String = runCatching {
        val inst = instance() ?: return fail("zorvai-screen-unavailable")
        call(inst, "release")
        JSONObject().put("ok", true).put("source", "zorvai").toString()
    }.getOrElse { fail("destroy-failed", it) }

    /** 让 ZorvAI 悬浮窗显示 DSH 选中的目标：ZorvAI 只有一块自有屏，alias 仅做记录。 */
    fun select(alias: String): String = runCatching {
        val inst = instance() ?: return fail("zorvai-screen-unavailable")
        stateJson(inst).put("ok", true).put("source", "zorvai").put("alias", alias).toString()
    }.getOrElse { fail("select-failed", it) }

    /** 几何交给 ZorvAI 悬浮窗自身管理（它按主屏分辨率 1:1 建屏），不接受 DSH CSS 几何。 */
    fun bounds(bounds: String): String = runCatching {
        val inst = instance() ?: return fail("zorvai-screen-unavailable")
        stateJson(inst).put("ok", true).put("source", "zorvai")
            .put("note", "bounds managed by zorvai overlay").toString()
    }.getOrElse { fail("bounds-failed", it) }

    private fun fail(code: String, t: Throwable? = null): String {
        Log.w(TAG, "$code: ${t?.javaClass?.simpleName}: ${t?.message}")
        return JSONObject().put("ok", false).put("code", code)
            .put("error", t?.message ?: JSONObject.NULL).toString()
    }
}
