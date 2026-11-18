package com.ai.assistance.quro.core.xposed

/**
 * LSPosed 模块的单一常量源：作用域清单、RemotePreferences 组名与全部开关键。
 *
 * 设计约束：
 * · 这个文件同时被「UI 进程」和「被 Hook 的 system_server / 厂商 App 进程」加载，
 *   因此**不得**引用 android.content.Context、资源 ID、Application 单例或任何 app 侧运行时；
 *   保持纯 Kotlin 常量，避免把整个应用初始化链拖进别人的进程。
 * · 键名是本工程自定的协议名，与任何第三方项目无关；Hook 回调在每次拦截入口读当前值，
 *   所以开关切换后下一次触发即生效，不依赖框架热更新。
 */
object QuroModuleConfig {

    const val SELF_PACKAGE = "com.ai.assistance.quro"

    /** XposedService.getRemotePreferences / XposedModule.getRemotePreferences 共用的组名。 */
    const val PREFS_GROUP = "quro_module"

    const val LOG_TAG = "QuroModule"

    // ---- 作用域目标（必须与 META-INF/xposed/scope.list 逐行一致）----
    const val SYSTEM = "system"
    const val SYSTEM_UI = "com.android.systemui"
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    const val BREENO = "com.heytap.speechassist"
    const val COLOR_DIRECT = "com.coloros.colordirectservice"
    const val AIMEMORY = "com.oplus.aimemory"
    const val XIAOAI = "com.miui.voiceassist"
    const val MIUI_HOME = "com.miui.home"
    const val MIUI_GLOBAL_HOME = "com.mi.android.globallauncher"

    val DECLARED_SCOPE: List<String> = listOf(
        SYSTEM,
        SYSTEM_UI,
        GOOGLE,
        BREENO,
        COLOR_DIRECT,
        AIMEMORY,
        XIAOAI,
        MIUI_HOME,
        MIUI_GLOBAL_HOME,
    )

    /**
     * 小布 / 超级小爱接管所需的作用域。
     *
     * 注意这里是**并集**（任一台机器上只可能命中其中一个助手），
     * 真正判定需求见 [SwitchDef.scopeGroups] 的分组语义。
     */
    val VENDOR_ASSISTANT_SCOPE: List<String> = listOf(BREENO, XIAOAI, COLOR_DIRECT)

    /** Gemini / 一圈即搜所需的作用域。 */
    val GEMINI_SCOPE: List<String> = listOf(SYSTEM, SYSTEM_UI, GOOGLE, COLOR_DIRECT)

    /** 无障碍保护所需的作用域。 */
    val ACCESSIBILITY_SCOPE: List<String> = listOf(SYSTEM)

    // ---- 分组需求（组间与、组内或）----

    /** 小布 / 超级小爱：二选一；ColorOS 识屏服务有则必需。 */
    val VENDOR_ASSISTANT_GROUPS: List<List<String>> = listOf(
        listOf(BREENO, XIAOAI),
        listOf(COLOR_DIRECT),
    )

    /** Gemini / 一圈即搜：system 与 SystemUI 必需；Google App、识屏服务按本机是否安装。 */
    val GEMINI_GROUPS: List<List<String>> = listOf(
        listOf(SYSTEM),
        listOf(SYSTEM_UI),
        listOf(GOOGLE),
        listOf(COLOR_DIRECT),
    )

    val MIUI_LAUNCHERS: Set<String> = setOf(MIUI_HOME, MIUI_GLOBAL_HOME)

    // ---- RemotePreferences 键 ----
    object Keys {
        /** 三个主能力（对应「系统助手与增强」分组）。 */
        const val VENDOR_ASSISTANT_TAKEOVER = "hook_vendor_assistant_takeover"
        const val GEMINI_CIRCLE_TO_SEARCH = "hook_gemini_circle_to_search"
        const val ACCESSIBILITY_PROTECTION = "hook_accessibility_protection"

        /** 厂商助手接管细分项。 */
        const val VENDOR_CUSTOM_MODEL = "hook_vendor_custom_model"
        const val VENDOR_REQUIRE_PREFIX = "hook_vendor_require_prefix"

        /** 系统助手配置。 */
        const val AUTO_DEFAULT_ASSISTANT = "hook_auto_default_assistant"
        const val POWER_ASSISTANT_TARGET = "hook_power_assistant_target"

        /** Gemini / 一圈即搜细分项。 */
        const val HOTWORD_SELF_HEAL = "hook_hotword_self_heal"
        const val LOCKSCREEN_VOICE_COMMAND = "hook_lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "hook_screen_on_voice_command"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "hook_gesture_bar_circle_to_search"
    }

    /** 长按电源键目标；缺省保留厂商助手，不因升级改写用户既有选择。 */
    object PowerTarget {
        const val VENDOR = "vendor"
        const val GEMINI = "gemini"
        const val QURO = "quro"
        val ALL: List<String> = listOf(VENDOR, GEMINI, QURO)
    }

    /**
     * 一个可由 UI 写入、由 Hook 进程只读的布尔开关。
     *
     * [scopeGroups] 的语义是「组之间与、组内或」：
     *  · 一个开启的开关通常横跨 ColorOS 与 HyperOS 两套实现，例如小布与超级小爱；
     *    用户手上只会有其中一套，所以它们是**同一组的两个备选**，命中一个就够。
     *  · 「系统进程 + SystemUI」这种缺一不可的，拆成两组。
     *  · 本机没安装的包（例如 ColorOS 上根本没有 `com.miui.voiceassist`）直接不参与判定，
     *    否则永远报缺，开关永远显示红字 —— 这正是旧版「已开启但缺少作用域」刷屏的原因。
     */
    data class SwitchDef(
        val key: String,
        val title: String,
        val summary: String,
        val defaultEnabled: Boolean = false,
        val scopeGroups: List<List<String>> = emptyList(),
        val group: String = "",
    ) {
        /** 声明面上的全部作用域（去重）；工具与契约测试用它核对 scope.list。 */
        val scope: List<String> get() = scopeGroups.flatten().distinct()
    }

    /**
     * 作用域目标在本机是否存在。
     *
     * `system` 不是包名（PackageManager 里对应 `android`），但 system_server 一定存在，
     * 所以它永远算「存在」，不参与安装过滤。
     */
    fun isTargetPresent(pkg: String, installed: Set<String>): Boolean =
        pkg == SYSTEM || pkg in installed

    /**
     * 计算某组需求里**真正还差**的作用域。
     *
     * @param enabled  框架已勾选的作用域
     * @param installed 本机已安装的包名（见 [installedPackages]）
     * @return 需要用户去勾选的包名；为空表示这组需求已满足或本机用不到
     */
    fun unmetScope(
        groups: List<List<String>>,
        enabled: Collection<String>,
        installed: Set<String>,
    ): List<String> = groups.flatMap { group ->
        val present = group.filter { isTargetPresent(it, installed) }
        when {
            // 本机没有这一套组件（ColorOS 上没有小米助手）→ 不需要勾，也不算缺
            present.isEmpty() -> emptyList()
            // 组内任一已勾选即满足（小布 / 超级小爱二选一）
            present.any { it in enabled } -> emptyList()
            else -> present
        }
    }.distinct()

    /** 某个开关在当前环境下的缺失作用域。 */
    fun unmetScope(def: SwitchDef, enabled: Collection<String>, installed: Set<String>): List<String> =
        unmetScope(def.scopeGroups, enabled, installed)

    /**
     * 从 PackageManager 取已安装应用包名。
     *
     * 这里用 `getInstalledPackages` 的轻量重载而不是 `getInstalledApplications`：
     * 只需要包名集合，避免把图标/标签一起拉起来拖慢设置页首帧。
     */
    @Suppress("DEPRECATION")
    fun installedPackages(pm: android.content.pm.PackageManager): Set<String> = runCatching {
        pm.getInstalledPackages(0).mapNotNull { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** 「系统助手与增强」三条主能力（截图第 3 张）。顺序即展示顺序。 */
    val MAIN_SWITCHES: List<SwitchDef> = listOf(
        SwitchDef(
            key = Keys.VENDOR_ASSISTANT_TAKEOVER,
            title = "小布与超级小爱接管",
            summary = "通过系统助手入口调用 Zorv AI Runtime。需要 LSPosed 启用本模块、对应助手作用域，以及支持的 ColorOS 或 HyperOS。",
            defaultEnabled = true,
            scopeGroups = VENDOR_ASSISTANT_GROUPS,
            group = "系统助手与增强",
        ),
        SwitchDef(
            key = Keys.GEMINI_CIRCLE_TO_SEARCH,
            title = "Gemini 与一圈即搜",
            summary = "在 ColorOS 创建或修复系统能力与触发入口。需要 LSPosed、对应系统与 Google 应用作用域。",
            defaultEnabled = true,
            scopeGroups = GEMINI_GROUPS,
            group = "系统助手与增强",
        ),
        SwitchDef(
            key = Keys.ACCESSIBILITY_PROTECTION,
            title = "无障碍保护",
            summary = "通过 system_server 维护服务启用状态。需要 LSPosed 的系统作用域；普通模式仍可手动开启无障碍。",
            defaultEnabled = true,
            scopeGroups = listOf(listOf(SYSTEM)),
            group = "系统助手与增强",
        ),
    )

    /** 设置页「系统助手接管」分组的细分开关。 */
    val ASSISTANT_SWITCHES: List<SwitchDef> = listOf(
        SwitchDef(
            key = Keys.AUTO_DEFAULT_ASSISTANT,
            title = "自动设置默认数字助理",
            summary = "开机、解锁或启动失败时校正默认数字助理配置；关闭时不写系统配置。",
            scopeGroups = listOf(listOf(SYSTEM)),
            group = "系统助手接管",
        ),
        SwitchDef(
            key = Keys.VENDOR_CUSTOM_MODEL,
            title = "厂商助手使用自选模型",
            summary = "接管小布 / 超级小爱后使用 Zorv AI 当前配置的模型与服务商。",
            defaultEnabled = true,
            scopeGroups = VENDOR_ASSISTANT_GROUPS,
            group = "小布 / 超级小爱兼容入口",
        ),
        SwitchDef(
            key = Keys.VENDOR_REQUIRE_PREFIX,
            title = "仅带前缀时接管",
            summary = "只有以约定前缀开头的请求才交给 Zorv AI，其余仍由厂商助手原生处理。",
            scopeGroups = VENDOR_ASSISTANT_GROUPS,
            group = "小布 / 超级小爱兼容入口",
        ),
    )

    /** 设置页「Gemini」分组的细分开关。 */
    val GEMINI_SWITCHES: List<SwitchDef> = listOf(
        SwitchDef(
            key = Keys.HOTWORD_SELF_HEAL,
            title = "息屏后维持 Hey Google 检测",
            summary = "息屏后检查热词会话，必要时恢复监听；亮屏或恢复成功即取消待执行任务。",
            defaultEnabled = true,
            scopeGroups = listOf(listOf(SYSTEM)),
            group = "Gemini",
        ),
        SwitchDef(
            key = Keys.LOCKSCREEN_VOICE_COMMAND,
            title = "锁屏唤起自动语音输入",
            summary = "锁屏唤起助手浮窗但只显示输入框时，补发一次语音命令。",
            defaultEnabled = true,
            scopeGroups = listOf(listOf(GOOGLE)),
            group = "Gemini",
        ),
        SwitchDef(
            key = Keys.SCREEN_ON_VOICE_COMMAND,
            title = "亮屏唤起自动语音输入",
            summary = "解锁态唤起助手浮窗时同样补发一次语音命令。",
            defaultEnabled = true,
            scopeGroups = listOf(listOf(GOOGLE)),
            group = "Gemini",
        ),
        SwitchDef(
            key = Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
            title = "手势条长按 / 双指识屏触发一圈即搜",
            summary = "拦截底部手势条长按与双指取词，改走系统 contextual_search 入口。",
            defaultEnabled = true,
            scopeGroups = listOf(listOf(SYSTEM), listOf(SYSTEM_UI)),
            group = "一圈即搜",
        ),
    )

    val ALL_SWITCHES: List<SwitchDef> = MAIN_SWITCHES + ASSISTANT_SWITCHES + GEMINI_SWITCHES

    /**
     * 非布尔的字符串配置键：同样由 Hook 进程读取，但不出现在开关表里
     * （开关表只放 boolean，UI 才能用统一的 Switch 渲染）。
     * 契约测试用它区分「读错键」与「本来就不是开关」。
     */
    val STRING_KEYS: Set<String> = setOf(Keys.POWER_ASSISTANT_TARGET)

    fun switchOf(key: String): SwitchDef? = ALL_SWITCHES.firstOrNull { it.key == key }

    fun defaultOf(key: String): Boolean = switchOf(key)?.defaultEnabled ?: false

    /** 作用域包名的可读标签，用于「模块启用与作用域」列表。 */
    fun scopeLabel(pkg: String): String = when (pkg) {
        SYSTEM -> "system（system_server）"
        SYSTEM_UI -> "系统界面 SystemUI"
        GOOGLE -> "Google App / Gemini"
        BREENO -> "小布助手（ColorOS）"
        COLOR_DIRECT -> "ColorOS 识屏服务"
        AIMEMORY -> "小布记忆"
        XIAOAI -> "超级小爱（HyperOS）"
        MIUI_HOME -> "小米桌面"
        MIUI_GLOBAL_HOME -> "小米国际版桌面"
        else -> pkg
    }
}
