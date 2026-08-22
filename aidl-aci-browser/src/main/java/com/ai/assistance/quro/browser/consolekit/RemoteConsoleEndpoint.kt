package com.ai.assistance.quro.browser.consolekit

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.IAidlAciService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 跨进程端点：经 ACI 绑定第 2 / 第 3 / … 个受控 App 的 `console_ui` / `console_action` 能力。
 *
 * 它与 [LocalConsoleEndpoint] 实现同一份 [AciConsoleContract]，所以「手动控制台 UI」完全复用、零改动 ——
 * 只要目标 App 暴露了 console_ui / console_action（任意业务后端都行），本端点就能驱动它。
 * 这正是用户要求的「开发第二个第三个软件也能用」：控制台 UI 不用为每个 App 重写。
 *
 * @param targetPackage 目标受控 App 的包名。
 * @param serviceClassName 目标 App 的 ACI Service 完整类名（默认 `aci.QuroControlledAidlAciService`）。
 */
class RemoteConsoleEndpoint(
    private val context: Context,
    private val targetPackage: String,
    private val serviceClassName: String = "aci.QuroControlledAidlAciService"
) : AciConsoleContract {

    @Volatile private var binder: IAidlAciService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            binder = IAidlAciService.Stub.asInterface(b)
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    /** 绑定目标 App 的 ACI Service（建议在面板首次展开时调用）。返回是否发起绑定。 */
    fun bind(): Boolean {
        val intent = Intent().setClassName(targetPackage, "$targetPackage.$serviceClassName")
        return try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (_: Throwable) { false }
    }

    /** 解绑（页面销毁时调用）。 */
    fun unbind() {
        try { context.unbindService(conn) } catch (_: Throwable) {}
        binder = null
    }

    private fun connected(): IAidlAciService? = binder

    override fun getSnapshot(): JSONObject {
        val svc = connected() ?: return JSONObject().put("error", "未连接到 $targetPackage（请先 bind()）")
        val req = AidlAciRequest("console_ui", android.os.Bundle())
        runCatching { req.setCallerPkg(context.packageName) }
        val resp = runCatching { svc.call(req) }.getOrNull()
            ?: return JSONObject().put("error", "console_ui 无响应")
        val raw = resp.result?.getString("snapshot") ?: resp.result?.getString("json")
        return try { JSONObject(raw ?: "{}") } catch (_: Throwable) { JSONObject().put("error", "快照解析失败") }
    }

    override fun sendAction(action: String, payload: Map<String, String>): JSONObject {
        val svc = connected() ?: return JSONObject().put("error", "未连接到 $targetPackage（请先 bind()）")
        val b = android.os.Bundle().apply {
            putString("action", action)
            putString("payload", JSONObject().apply { payload.forEach { (k, v) -> put(k, v) } }.toString())
        }
        val req = AidlAciRequest("console_action", b)
        runCatching { req.setCallerPkg(context.packageName) }
        val resp = runCatching { svc.call(req) }.getOrNull()
            ?: return JSONObject().put("error", "console_action 无响应")
        val r = resp.result
        return try {
            val ok = r?.getBoolean("ok") ?: true
            val act = r?.getString("action") ?: action
            JSONObject().put("ok", ok).put("action", act)
        } catch (_: Throwable) { JSONObject().put("ok", true).put("action", action) }
    }
}
