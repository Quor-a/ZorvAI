package com.ai.assistance.quro.genui.app.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import com.ai.assistance.quro.genui.app.store.GenStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MoBridge —— AI 的界面 ↔ 设备真实功能 的唯一通道。
 *
 * 协议：页面 → {id, api:"ns.method", args:{...}} ；native → {id, ok, val}
 * 页面侧的 Promise 包装见 [JS_WRAPPER]（随文档头写入，先于 AI 的任何脚本）。
 *
 * 安全边界（v0.1）：
 * - net.proxy 仅 https，限 5 次/分钟，走原生 OkHttp（AI 页面自身处于 about:blank origin）
 * - notify 需系统通知权限
 * - 文件系统不暴露
 */
object MoBridgeHost {

    /**
     * 注入给 AI 页面的 Promise 包装（在 AI 任何脚本执行前写入，见 A2UIRenderer.begin）。
     *
     * 注意：JS 对象字面量里同名键「后者覆盖前者」，历史上 ui 键被写过两次，
     * 导致 ui.widget 被静默丢弃（8 种原生组件整条链路不可达）。此处只在末尾出现一次 ui，
     * 且 wrapper 必须能安全重复注入（中断续写/回放时会再次写入同一文档）。
     */
    const val JS_WRAPPER = """
<script>
(function(){
  // 幂等：重复注入时保留已有实例，避免续写/replay 场景下丢失未决 Promise
  if (window.__moReady) return;
  window.__moReady = true;

  var seq = 0, pend = {};
  function call(api, args){
    return new Promise(function(res, rej){
      var id = ++seq;
      pend[id] = {res: res, rej: rej};
      try { __moHost.postMessage(JSON.stringify({id:id, api:api, args:args||{}})); }
      catch(e){ delete pend[id]; rej(e); }
    });
  }
  // 原生侧回写：{id, ok, val}
  window.__moResolve = function(json){
    try {
      var m = (typeof json === 'string') ? JSON.parse(json) : json;
      var p = pend[m.id];
      if (!p) return;
      delete pend[m.id];
      if (m.ok) p.res(m.val); else p.rej(new Error(typeof m.val === 'string' ? m.val : 'MoBridge 调用失败'));
    } catch(e){}
  };
  // XMLHttpRequest 以 JSON 字符串投递，避免结构化克隆的兼容问题
  try { __moHost.onmessage = function(e){ window.__moResolve(e.data); }; } catch(e){}

  function noop(){ return Promise.resolve({ok:true}); }

  window.MoBridge = {
    call: call,
    time: {
      now:    function(){ return call('time.now'); },
      format: function(epochMs, tpl){ return call('time.format', {epochMs:epochMs, tpl:tpl}); }
    },
    store: {
      get:  function(k){ return call('store.get', {key:k}); },
      put:  function(k,v){ return call('store.put', {key:k, value:v}); },
      keys: function(){ return call('store.keys'); },
      del:  function(k){ return call('store.del', {key:k}); }
    },
    notify: {
      send: function(title, body){ return call('notify.send', {title:title, body:body}); }
    },
    haptics: {
      tap: function(light){ return call('haptics.tap', {light:light===true}); }
    },
    clipboard: {
      write: function(text){ return call('clipboard.write', {text:text}); }
    },
    net: {
      proxy: function(url, method, headers, body){
        return call('net.proxy', {url:url, method:method||'GET', headers:headers||{}, body:body});
      }
    },
    device: {
      info: function(){ return call('device.info'); }
    },
    // ---- 原生组件 + 标题（唯一一次定义 ui） ----
    ui: {
      title:  function(text){ try{ document.title = text; }catch(e){} return noop(); },
      widget: function(kind, data){ return call('ui.widget', {kind:kind, data:data||{}}); }
    }
  };
})();
</script>
"""

    // ---------- 供 Agent 工具层（BuiltinTools）复用的静态能力 ----------

    fun deviceInfoStatic(context: Context): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val network = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        } catch (e: Exception) { "none" }
        return JSONObject()
            .put("model", Build.MODEL)
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("battery", JSONObject().put("pct", if (level >= 0) level * 100 / scale else -1).put("charging", plugged != 0))
            .put("network", network)
    }

    fun notifyStatic(context: Context, title: String, body: String): JSONObject {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) throw IllegalStateException("通知权限未授权，请在系统设置中开启")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "mo_bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "GenUI · 界面通知", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, chId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(64)).setContentText(body.take(200))
            .setAutoCancel(true).build()
        nm.notify(UUID.randomUUID().hashCode(), n)
        return JSONObject().put("ok", true)
    }
}

/**
 * 原生侧分发器：解析页面消息 → 后台线程执行真实能力 → UI 线程回写结果。
 */
class MoBridgeDispatcher(
    private val context: Context,
    private val webView: WebView,
    private val onCall: (String) -> Unit,     // 供状态行展示 bridge 调用计数/名称
    private val onWidget: (kind: String, payload: String) -> Unit = { _, _ -> } // 原生组件请求
) {
    private val store = GenStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private var netCallsThisMinute = 0
    private var minuteWindow = 0L

    /**
     * WebView 的 onPostMessage 在 UI 线程回调；重活转 IO，结果回 UI 线程。
     *
     * 回写有两条路（都走，互为兜底）：
     *  1) reply.postMessage → 触发页面侧 __moHost.onmessage（JS_WRAPPER 已注册处理器）
     *  2) evaluateJavascript 直接调 window.__moResolve —— 某些 WebView 版本上
     *     WebMessageListener 的 reply 代理在流式 document.write 期间可能不可靠
     */
    fun dispatch(raw: String, reply: JavaScriptReplyProxy) {
        val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = msg.optInt("id", -1)
        val api = msg.optString("api")
        val args = msg.optJSONObject("args") ?: JSONObject()
        onCall(api)

        io.execute {
            val replyJson = try {
                val value = handle(api, args)
                JSONObject().put("id", id).put("ok", true).put("val", value ?: JSONObject.NULL)
            } catch (e: Exception) {
                JSONObject().put("id", id).put("ok", false).put("val", e.message ?: "error")
            }.toString()
            webView.post {
                runCatching { reply.postMessage(replyJson) }
                // 兜底通道：直接调用页面侧解析器
                runCatching {
                    webView.evaluateJavascript(
                        "try{window.__moResolve(" +
                            JSONObject.quote(replyJson) + ")}catch(e){}", null
                    )
                }
            }
        }
    }

    private fun handle(api: String, a: JSONObject): Any? = when (api) {

        "time.now" -> JSONObject()
            .put("epoch", System.currentTimeMillis())
            .put("iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(Date()))
            .put("text", SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date()))

        "time.format" -> JSONObject().put("text",
            SimpleDateFormat(a.optString("tpl", "yyyy-MM-dd HH:mm"), Locale.CHINA)
                .format(Date(a.optLong("epochMs", System.currentTimeMillis()))))

        "store.get" -> JSONObject().put("value", store.getPageValue(a.optString("key")) ?: JSONObject.NULL)

        "store.put" -> { store.putPageValue(a.optString("key"), a.opt("value")); JSONObject().put("ok", true) }

        "store.keys" -> JSONObject().put("keys", store.pageKeys())

        "store.del" -> { store.deletePageValue(a.optString("key")); JSONObject().put("ok", true) }

        "notify.send" -> {
            requireNotifyPermission()
            sendNotification(a.optString("title", "GenUI"), a.optString("body", ""))
            JSONObject().put("ok", true)
        }

        "haptics.tap" -> {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val ms = if (a.optBoolean("light")) 12L else 35L
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            JSONObject().put("ok", true)
        }

        "clipboard.write" -> {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("gen", a.optString("text")))
            JSONObject().put("ok", true)
        }

        "net.proxy" -> {
            checkRate()
            val url = a.optString("url")
            require(url.startsWith("https://")) { "net.proxy 仅支持 https" }
            val method = a.optString("method", "GET").uppercase()
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).build()
            val mime = "application/json".toMediaTypeOrNull()
            var builder = Request.Builder().url(url)
            val headers = a.optJSONObject("headers") ?: JSONObject()
            headers.keys().forEach { k -> builder = builder.header(k, headers.optString(k)) }
            builder = if (method == "GET") builder.get()
            else builder.method(method, a.optString("body").toRequestBody(mime))
            client.newCall(builder.build()).execute().use { resp ->
                JSONObject()
                    .put("status", resp.code)
                    .put("body", resp.body?.string()?.take(200_000) ?: "")
            }
        }

        "device.info" -> JSONObject()
            .put("model", Build.MODEL)
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("battery", readBattery())
            .put("network", readNetwork())

        "ui.widget" -> {
            // AI 的页面请求"原生组件"：端上用 Compose BottomSheet 渲染真实原生控件
            val kind = a.optString("kind")
            val payload = a.optJSONObject("data")?.toString() ?: a.optString("data", "{}")
            webView.post { onWidget(kind, payload) }
            JSONObject().put("ok", true)
        }

        else -> throw IllegalArgumentException("未知 API: $api")
    }

    // ---------- 权限与限流 ----------

    private fun requireNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) throw IllegalStateException("通知权限未授权，请在系统设置中开启")
    }

    private fun checkRate() {
        val now = System.currentTimeMillis() / 60_000
        if (now != minuteWindow) { minuteWindow = now; netCallsThisMinute = 0 }
        require(++netCallsThisMinute <= 5) { "net.proxy 限流：每分钟 5 次" }
    }

    private fun sendNotification(title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "mo_bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "GenUI · 界面通知", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, chId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(64)).setContentText(body.take(200))
            .setAutoCancel(true).build()
        nm.notify(UUID.randomUUID().hashCode(), n)
    }

    private fun readBattery(): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return JSONObject().put("pct", if (level >= 0) level * 100 / scale else -1)
            .put("charging", plugged != 0)
    }

    private fun readNetwork(): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    } catch (e: Exception) { "none" }
}
