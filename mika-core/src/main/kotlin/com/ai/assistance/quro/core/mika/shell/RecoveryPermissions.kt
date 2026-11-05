package com.ai.assistance.quro.core.mika.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 经已配对的无线调试连接,以 shell 身份跑一条命令,返回它的输出。连接断了就抛异常。 */
fun interface ShellCommandRunner {
    suspend fun exec(command: String): String
}

/**
 * 手机操作的几项能力:默认打开(连接可用时 ZorvAI 自己补上),都能在设置里关掉,解除配对时一并收回。
 * [RestoreAfterReboot] 和 [AutoWirelessDebugging] 用的是同一份修改系统设置的授权(都只用来打开无线调试),
 * 两项都关掉才收回。
 */
enum class RecoveryCapability {
    Accessibility, BatteryWhitelist, RestoreAfterReboot,
    /** 要重新启动内置助手、无线调试却关着时替用户打开(见 [WirelessDebuggingAutoOn])。 */
    AutoWirelessDebugging;

    /** 要靠修改系统设置的授权。 */
    val writesSecureSettings: Boolean get() = this == RestoreAfterReboot || this == AutoWirelessDebugging
}

/**
 * 每项能力的两件事分开记:[applied] 是 ZorvAI 已经替用户在系统里打开、此刻有效的;
 * [declined] 是用户亲手关掉的,之后不再自动打开。两边都没有的就是「没动过」,按默认打开。
 */
data class RecoveryConsent(
    val applied: Set<RecoveryCapability> = emptySet(),
    val declined: Set<RecoveryCapability> = emptySet(),
) {
    /** 用户要不要这一项:没亲手关掉就是要(开关显示的就是它)。 */
    fun wants(c: RecoveryCapability): Boolean = c !in declined

    fun isApplied(c: RecoveryCapability): Boolean = c in applied

    /** 用户要、却还没生效的几项:连接一可用就补上。 */
    val pending: List<RecoveryCapability> get() = RecoveryCapability.entries.filter { wants(it) && !isApplied(it) }

    fun chose(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(declined = if (on) declined - c else declined + c)

    fun withApplied(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(applied = if (on) applied + c else applied - c)
}

interface RecoveryConsentStore {
    val consent: StateFlow<RecoveryConsent>
    fun save(consent: RecoveryConsent)
}

sealed interface PermissionResult {
    data object Done : PermissionResult
    /** 手机操作还没设置好,没有能用的连接。 */
    data object NoRunner : PermissionResult
    data class Failed(val detail: String) : PermissionResult
}

/** 这几项能力对应的 shell 命令(原版 pm / cmd deviceidle / settings 的写法)。 */
object PermissionCommands {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val READ_ACCESSIBILITY = "settings get secure enabled_accessibility_services"
    const val EXIT_MARK = "__quro_exit="

    fun accessibilityService(pkg: String): String {
        // 包名(applicationId)可能带后缀(并存安装的版本),服务类却仍在 App 的命名空间里:包名是类名前缀时用简写,否则写完整类名
        val service = "com.ai.assistance.quro.core.mika.a11y.ZorvAIAccessibilityService"
        return "$pkg/${if (service.startsWith("$pkg.")) service.removePrefix(pkg) else service}"
    }
    fun grantWriteSecureSettings(pkg: String) = "pm grant $pkg $WRITE_SECURE_SETTINGS"
    fun revokeWriteSecureSettings(pkg: String) = "pm revoke $pkg $WRITE_SECURE_SETTINGS"
    fun whitelistAdd(pkg: String) = "cmd deviceidle whitelist +$pkg"
    fun whitelistRemove(pkg: String) = "cmd deviceidle whitelist -$pkg"

    /** 写回整张无障碍服务列表。空了就删掉这一项并关总开关,免得留下一个空字符串。 */
    fun writeAccessibility(list: String): List<String> =
        if (list.isEmpty()) listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0")
        else listOf("settings put secure enabled_accessibility_services ${quote(list)}", "settings put secure accessibility_enabled 1")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** exec 服务拿不到退出码:在输出末尾补一行标记。pm、cmd 失败时多半只在输出里写一句,不能只看有没有输出。 */
    fun checked(command: String): String = "$command 2>&1; echo $EXIT_MARK\$?"

    fun exitCode(output: String): Int? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.toIntOrNull()
}

/** `enabled_accessibility_services` 是冒号分隔的组件名;组件名有「包名/.类名」和「包名/完整类名」两种写法。 */
object AccessibilityList {
    fun entries(raw: String?): List<String> {
        val v = raw?.trim().orEmpty()
        if (v == "null") return emptyList() // `settings get` 对没写过的项输出 null
        return v.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun contains(raw: String?, component: String): Boolean = entries(raw).any { same(it, component) }

    fun with(raw: String?, component: String): String {
        val list = entries(raw)
        return (if (list.any { same(it, component) }) list else list + component).joinToString(":")
    }

    fun without(raw: String?, component: String): String = entries(raw).filterNot { same(it, component) }.joinToString(":")

    private fun same(a: String, b: String) = expand(a) == expand(b)

    private fun expand(c: String): String {
        if ('/' !in c) return c
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else c
    }
}

/**
 * 按用户的选择打开或收回这几项能力。都要靠已配对的连接(shell 身份)去改系统设置,[runner] 为 null 就是还没有能用的连接。
 * 用户的选择([RecoveryConsent.declined])和系统里是否生效([RecoveryConsent.applied])分开记:
 * 打开的选择没连接时也先记下,等 [applyPending] 补上;命令失败时两样都保持原样,界面如实显示。
 * 几个入口串着执行,自动补上和用户拨开关不会交错。
 */
class RecoveryPermissions(
    private val runner: () -> ShellCommandRunner?,
    private val store: RecoveryConsentStore,
    private val packageName: String,
) {
    private val lock = Mutex()
    private val _autoFailed = MutableStateFlow<Set<RecoveryCapability>>(emptySet())

    /** 自动补上时没成功的几项(不打扰用户,只在设置页那一项下面说一句);之后成功了就去掉。 */
    val autoFailed: StateFlow<Set<RecoveryCapability>> = _autoFailed.asStateFlow()

    /** 用户拨开关:打开直接执行,不再确认;关掉马上收回,并记下「用户亲手关掉」。 */
    suspend fun set(capability: RecoveryCapability, on: Boolean): PermissionResult = lock.withLock {
        val now = store.consent.value
        val shell = runner()
        if (shell == null) {
            return@withLock when {
                // 先记下要,连接可用时自动补上;界面提示先完成设置
                on -> PermissionResult.NoRunner.also { store.save(now.chose(capability, true)) }
                // 没生效过,没有要收回的
                !now.isApplied(capability) -> PermissionResult.Done.also { store.save(now.chose(capability, false)) }
                else -> PermissionResult.NoRunner
            }
        }
        val result = run(shell, listOf(capability), on)
        if (result == PermissionResult.Done) {
            store.save(store.consent.value.chose(capability, on).withApplied(capability, on))
            _autoFailed.update { it - capability }
        }
        result
    }

    /**
     * 连接刚可用时调:把用户没关掉、也还没生效的几项打开。不弹任何东西;失败的记进 [autoFailed],
     * 下次连接可用时再试。没有连接就什么都不做。
     */
    suspend fun applyPending(): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        var failure: PermissionResult? = null
        for (c in store.consent.value.pending) {
            val r = run(shell, listOf(c), on = true)
            if (r == PermissionResult.Done) store.save(store.consent.value.withApplied(c, true))
            else if (failure == null) failure = r
            _autoFailed.update { if (r == PermissionResult.Done) it - c else it + c }
        }
        failure ?: PermissionResult.Done
    }

    /** 一直看着内置助手的状态:每次变成运行中(连接刚可用)就 [applyPending]。 */
    suspend fun applyWhenRunning(status: Flow<BuiltinShellStatus>) {
        status.collect { if (it == BuiltinShellStatus.Running) applyPending() }
    }

    /**
     * 解除配对时用:收回 ZorvAI 在这里打开过的每一项,外加修改系统设置的授权(无论记录如何都收一次,收不存在的授权无害)。
     * 用户自己在系统里开的无障碍或白名单不是这里给的,不动。「已生效」无论成败都清空:配对没了,这些能力也不再生效;
     * 用户的选择留着:再配对时没关掉的几项照样自动打开,亲手关掉的仍然关着。
     */
    suspend fun revokeAll(): PermissionResult = lock.withLock {
        val now = store.consent.value
        // 修改系统设置的授权两项共用,收一次就够:只留 RestoreAfterReboot 那一条
        val granted = now.applied.filterNot { it.writesSecureSettings }.toSet() + RecoveryCapability.RestoreAfterReboot
        store.save(now.copy(applied = emptySet()))
        _autoFailed.value = emptySet()
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        run(shell, granted.toList(), on = false)
    }

    /**
     * 让 ZorvAI 的无障碍服务重新运行起来(服务没在运行时用,见 [AccessibilityRepair]),别的服务一个不动:
     * 列表里没有 ZorvAI 就直接加上;有却没绑定,就先去掉,等 [gapMs] 让系统处理完,再重读列表加回去。
     * 去掉之后一定会加回去,中途被取消也一样,免得把服务留在关着的状态;加回失败时写回原来的列表,仍报失败。不改 [RecoveryConsent]:这不是打开或收回能力。
     */
    suspend fun restartAccessibility(gapMs: Long): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        val me = PermissionCommands.accessibilityService(packageName)
        try {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            // 不在列表里(被强行停止时系统会拿掉):直接加上;在列表里却没绑定:去掉再加回,系统才会重新绑定
            if (!AccessibilityList.contains(current, me)) return@withLock runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me)))
            val removed = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.without(current, me)))
            if (removed != PermissionResult.Done) return@withLock removed
            withContext(NonCancellable) {
                delay(gapMs)
                val now = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
                val back = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(now, me)))
                // 加回失败时把原来的列表(连同总开关)写回:宁可回到「有却没绑定」,也不让 ZorvAI 从列表里消失;
                // 写回只是兜底,这次仍按加回失败报
                // (读到的原样带着换行;它本来就有 ZorvAI,with 只把它整理成干净的列表,内容不变)
                if (back != PermissionResult.Done) runCatching { runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me))) }
                back
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 每项各自执行,一项失败不挡住后面的;返回第一处失败。 */
    private suspend fun run(shell: ShellCommandRunner, capabilities: List<RecoveryCapability>, on: Boolean): PermissionResult {
        var failure: PermissionResult? = null
        for (c in capabilities) {
            val r = try {
                runAll(shell, commands(shell, c, on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (r != PermissionResult.Done && failure == null) failure = r
        }
        return failure ?: PermissionResult.Done
    }

    private suspend fun commands(shell: ShellCommandRunner, c: RecoveryCapability, on: Boolean): List<String> = when (c) {
        RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging -> when {
            on -> listOf(PermissionCommands.grantWriteSecureSettings(packageName))
            // 另一项还要用这份授权:只记下关掉,不收回
            RecoveryCapability.entries.any { it != c && it.writesSecureSettings && store.consent.value.isApplied(it) } -> emptyList()
            else -> listOf(PermissionCommands.revokeWriteSecureSettings(packageName))
        }
        RecoveryCapability.BatteryWhitelist ->
            listOf(if (on) PermissionCommands.whitelistAdd(packageName) else PermissionCommands.whitelistRemove(packageName))
        RecoveryCapability.Accessibility -> {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            val me = PermissionCommands.accessibilityService(packageName)
            PermissionCommands.writeAccessibility(if (on) AccessibilityList.with(current, me) else AccessibilityList.without(current, me))
        }
    }

    private suspend fun runAll(shell: ShellCommandRunner, commands: List<String>): PermissionResult {
        for (cmd in commands) {
            val out = shell.exec(PermissionCommands.checked(cmd))
            if (PermissionCommands.exitCode(out) != 0) return PermissionResult.Failed("$cmd: ${out.trim()}")
        }
        return PermissionResult.Done
    }
}
