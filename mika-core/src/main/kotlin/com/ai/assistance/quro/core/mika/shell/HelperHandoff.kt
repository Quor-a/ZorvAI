package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 助手进程把 Binder 交给 ZorvAI 时双方共用的约定:provider 的方法名和 Bundle 键、助手的进程名、启动命令,
 * 以及 ZorvAI 一侧收下之前要过的检查。
 */
object HelperHandoff {
    const val SHELL_UID = 2000
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "com.ai.assistance.quro.core.mika.shell.HelperMain"

    // 助手 → ZorvAI
    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"
    /** 助手把 ZorvAI 拉起来的记录(见 [HelperRestoreReport]);没拉过时不带。 */
    const val KEY_RESTORED_AT = "restoredAt"
    const val KEY_RESTORES = "restores"
    const val KEY_GAVE_UP_AT = "restoreGaveUpAt"

    // ZorvAI → 助手
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"

    fun authority(packageName: String) = "$packageName.helper"

    /** 助手的进程名(app_process 的 --nice-name);同时用来认出旧的助手实例。 */
    fun processName(packageName: String) = "$packageName:helper"

    /**
     * 经 adbd 的 exec: 服务执行的命令(adbd 用 `sh -c` 跑它)。流一关,adbd 就把 sh 那一组进程都杀掉,
     * 所以助手用 setsid 另起会话;sh 再 `read` 等着,直到 App 收到交接后关流,
     * 免得 setsid 还没生效 sh 就退出、助手被连带杀掉。
     *
     * 口令不进命令行(同为 shell 的进程能读 /proc/<pid>/cmdline):App 开流后写一行 [stdinLine],
     * sh 读进变量,再用内建 echo 经管道交给助手;助手的标准输入是这条管道,不是 adb 的流。
     * [certPin] 是 ZorvAI 签名证书的指纹,不是秘密,放在参数里。
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /** 「用电脑启动」的脚本文件名,放在 App 的外部文件目录里(电脑经 USB 调试以 shell 身份读它)。 */
    const val USB_SCRIPT = "usb-start.sh"

    /** 主用户的外部存储在 shell 里也叫 /sdcard;给用户看的命令用这个短写法。别的用户照实际路径写。 */
    private const val PRIMARY_STORAGE = "/storage/emulated/0/"

    /** 电脑上要跑的那条命令。[scriptPath] 是脚本在手机上的实际路径。 */
    fun usbCommand(scriptPath: String): String =
        "adb shell sh " + (if (scriptPath.startsWith(PRIMARY_STORAGE)) "/sdcard/" + scriptPath.removePrefix(PRIMARY_STORAGE) else scriptPath)

    /**
     * 电脑经 USB 调试跑的启动脚本:起的是和 [command] 同一个助手、同样的参数,口令换成 [nonce](写在脚本里,见 [HandoffNonces.usbNonce])。
     * adb shell 的会话一结束(命令跑完、拔线),adbd 就把它那一组进程杀掉:助手用 nohup 忽略挂断信号,再用 setsid 另起会话,放到后台的子 shell 里起
     * (子 shell 退出后它归 init 管),标准输出和错误丢掉,不再连着 adb 的流;脚本再等一会儿让 setsid 生效,
     * 看助手进程还在就说一句「正在启动」,不在(多半是口令过期或 ZorvAI 不认)就请用户打开 ZorvAI 再跑一次。
     * 只用安卓原版 sh 和 toybox 里有的命令。不是 shell 身份(没经 adb 跑)时不起:ZorvAI 只收 shell 交来的助手。
     */
    fun usbScript(apkPath: String, packageName: String, appUid: Int, certPin: String, nonce: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        val again = usbCommand(scriptPath)
        return """
            |#!/system/bin/sh
            |# Written by ZorvAI. Starts ZorvAI's phone helper from a computer: $again
            |if [ "${'$'}(id -u)" != "$SHELL_UID" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |if [ ! -f ${quote(apkPath)} ]; then
            |  echo "ZorvAI was updated or reinstalled. Open ZorvAI once, then run this command again."
            |  exit 1
            |fi
            |n=${quote(nonce)}
            |(echo "${'$'}n" | CLASSPATH=${quote(apkPath)} nohup setsid app_process /system/bin --nice-name=$name $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} >/dev/null 2>&1 &)
            |sleep 2
            |if pidof $name >/dev/null 2>&1; then
            |  echo "ZorvAI helper starting... Open ZorvAI to confirm phone control is ready."
            |  echo "It keeps running after you unplug the cable. After a phone restart, run this command again."
            |else
            |  echo "ZorvAI helper did not start. Open ZorvAI once, then run this command again."
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /**
     * 什么时候重写「用电脑启动」脚本:助手来了或走了,以及口令被整体作废([HandoffNonces.resets],如解除配对)时;
     * 发出的值是此刻助手在不在。后一种助手可能一直不在,不跟着它重写的话脚本里留的就是作废的口令。
     */
    fun usbScriptWrites(helper: Flow<Any?>, resets: Flow<Int>): Flow<Boolean> = combine(helper, resets) { h, _ -> h != null }

    /**
     * 此刻该写的脚本:助手连着([connected])就写不带口令的 [usbStub],也不去要口令;否则写带新口令的 [usbScript]。
     * [certPin]、[nonce] 只在要写带口令的脚本时才取。
     */
    fun usbFile(
        connected: Boolean, apkPath: String, packageName: String, appUid: Int, certPin: () -> String, nonce: () -> String, scriptPath: String,
    ): String =
        if (connected) usbStub(packageName, scriptPath)
        else usbScript(apkPath, packageName, appUid, certPin(), nonce(), scriptPath)

    /** 助手已连着时换上的脚本:不带口令,只说一句。进程不在了(比如手机重启过、ZorvAI 还没打开)就请用户先打开 ZorvAI。 */
    fun usbStub(packageName: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        return """
            |#!/system/bin/sh
            |# Written by ZorvAI. The phone helper is connected, so this file holds no start code.
            |if pidof $name >/dev/null 2>&1; then
            |  echo "ZorvAI helper is already running. Nothing to do."
            |else
            |  echo "ZorvAI helper is not running. Open ZorvAI once, then run this command again: ${usbCommand(scriptPath)}"
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /** 签名证书(DER)的 SHA-256 指纹;多个签名者时排序后用逗号连起来。没有证书返回 null。 */
    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) } }
            .sorted().joinToString(",")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    enum class Reject {
        /** 调用方不是 shell(uid 2000)。 */
        NotShell,
        /** 助手自报的 uid/pid 和 Binder 看到的调用方对不上。 */
        IdMismatch,
        /** 助手跑的不是当前安装的这份 APK(ZorvAI 更新过):让它退出,重新起一个。 */
        StaleApk,
        NoBinder,
        BadNonce,
        /** Binder 所在的进程不是来交接的那个。 */
        BinderElsewhere,
    }

    /** 不碰 Binder 就能做的检查;通过返回 null。口令另由 [HandoffNonces] 核对。 */
    fun check(
        callingUid: Int, callingPid: Int, reportedUid: Int, reportedPid: Int,
        helperApk: String?, appApk: String, hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

/**
 * 持久化的几枚口令:[resume] 给正在跑的助手下次交接用,[launch] 给刚从 adbd 起的新助手用,
 * [launchAt] 是 [launch] 的签发时间(开机以来的毫秒数);[usb] 写在「用电脑启动」的脚本里,
 * [usbAt] 是它的签发时间(墙上时钟的毫秒数:脚本要跨过重启还能用)。
 */
data class NonceSlots(
    val resume: String? = null, val launch: String? = null, val launchAt: Long = 0,
    val usb: String? = null, val usbAt: Long = 0,
)

interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

/**
 * ZorvAI 生成、一次有效的交接口令。任何一枚被用掉,几枚都作废,并换发一枚新的 [NonceSlots.resume] 给收下的助手;
 * 存下来是为了 ZorvAI 进程被杀后,还活着的助手拿它重新交接时新进程认得。
 * [clock] 量启动口令的时效(开机以来),[wallClock] 量「用电脑启动」口令的时效。
 */
class HandoffNonces(
    private val store: NonceStore,
    private val clock: () -> Long,
    private val wallClock: () -> Long = clock,
    private val random: () -> String = ::randomNonce,
) {
    @Synchronized
    fun issueLaunch(): String = random().also { store.save(store.load().copy(launch = it, launchAt = clock())) }

    /**
     * 写进「用电脑启动」脚本的口令。手上那枚还新(剩下的时效超过一半)就接着用,免得电脑正在跑脚本时被换掉;
     * 否则换发一枚。和别的口令一样用掉一次就作废(见 [accept])。
     */
    @Synchronized
    fun usbNonce(): String {
        val s = store.load()
        val age = wallClock() - s.usbAt
        if (s.usb != null && age in 0..USB_TTL_MS / 2) return s.usb
        return random().also { store.save(s.copy(usb = it, usbAt = wallClock())) }
    }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val age = clock() - s.launchAt
        // 启动口令只在交接等待期内有效;没用上的过期作废
        val launch = s.launch?.takeIf { age in 0..LAUNCH_TTL_MS }
        // 脚本里的口令等的是用户插线跑命令,给得长一些;过期或时钟倒退都不认
        val usb = s.usb?.takeIf { wallClock() - s.usbAt in 0..USB_TTL_MS }
        return same(nonce, s.resume) or same(nonce, launch) or same(nonce, usb)
    }

    /** 口令对就用掉它并返回换发的新口令,不对返回 null。 */
    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    private val _resets = MutableStateFlow(0)
    /** 口令被整体作废([clear])的次数:变了就说明写出去的口令(如脚本里那枚)都不能用了。 */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    @Synchronized
    fun clear() {
        store.save(NonceSlots())
        _resets.value++
    }

    private fun same(a: String, b: String?) =
        b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        /** 覆盖 App 开着流等交接、引擎再等一轮交接的总时长。 */
        const val LAUNCH_TTL_MS = 30_000L
        /**
         * 「用电脑启动」口令的时效:脚本写好后,用户可能过一阵才插线跑命令(或重启后再跑一次)。
         * ZorvAI 每次打开、看不到助手、打开引导页时都会重写脚本,用了一半就换新的。
         */
        const val USB_TTL_MS = 24 * 60 * 60_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
