# -*- coding: utf-8 -*-
import io

def patch(path, repls):
    with io.open(path, "r", encoding="utf-8") as f:
        c = f.read()
    for old, new in repls:
        assert old in c, ("NOT FOUND in " + path + ":\n" + old[:120])
        c = c.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(c)
    print("patched:", path)

# ---- QuroApplication.kt boot apply ----
app = r"app/src/main/java/com/ai/assistance/quro/activity/QuroApplication.kt"
patch(app, [(
    '''            val follow = getSharedPreferences("quro_ui", Context.MODE_PRIVATE)
                .getBoolean("follow_system_language", true)
            com.ai.assistance.quro.util.QuroLocale.apply(follow)''',
    '''            val langPref = getSharedPreferences("quro_ui", Context.MODE_PRIVATE)
                .getString("app_language", "system") ?: "system"
            com.ai.assistance.quro.util.QuroLocale.apply(langPref)'''
)])

# ---- QuroChatViewModel.kt: prefs block ----
vm = r"app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt"
vm_old = '''    // 外观与对话设置：跟随系统语言（不内置国家/地区语言资源包，直接引用手机系统语言）
    // true = 跟随系统（默认）；false = 固定为应用首次启动时的系统语言。
    private val _followSystemLang = MutableStateFlow(uiPrefs.getBoolean("follow_system_language", true))
    val followSystemLangPref: StateFlow<Boolean> = _followSystemLang.asStateFlow()
    fun isFollowSystemLang(): Boolean = _followSystemLang.value
    fun setFollowSystemLang(on: Boolean) {
        _followSystemLang.value = on
        uiPrefs.edit { putBoolean("follow_system_language", on) }
        com.ai.assistance.quro.util.QuroLocale.apply(on)
    }'''
vm_new = '''    // 外观与对话设置：应用语言（"system"=跟随系统；否则为语言代码如 "en"/"ja"）。
    // 与 follow_system_language 布尔保持同步，便于旧逻辑兼容。
    private val _appLanguage = MutableStateFlow(uiPrefs.getString("app_language", "system") ?: "system")
    val appLanguagePref: StateFlow<String> = _appLanguage.asStateFlow()
    fun getAppLanguage(): String = _appLanguage.value
    private val _followSystemLang = MutableStateFlow((uiPrefs.getString("app_language", "system") ?: "system") == "system")
    val followSystemLangPref: StateFlow<Boolean> = _followSystemLang.asStateFlow()
    fun isFollowSystemLang(): Boolean = _appLanguage.value == "system"
    fun setFollowSystemLang(on: Boolean) {
        setAppLanguage(if (on) "system" else (_appLanguage.value.takeIf { it != "system" } ?: "en"))
    }
    fun setAppLanguage(code: String) {
        val c = if (code.isBlank()) "system" else code
        _appLanguage.value = c
        _followSystemLang.value = (c == "system")
        uiPrefs.edit {
            putString("app_language", c)
            putBoolean("follow_system_language", c == "system")
        }
        com.ai.assistance.quro.util.QuroLocale.apply(c)
    }'''
patch(vm, [(vm_old, vm_new)])

# ---- QuroChatViewModel.kt: system prompt language injection ----
vm_inject_old = '''        // 平台/品牌自我认知基座（永远最先，不被人格卡覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\\n\\n")'''
vm_inject_new = '''        // 平台/品牌自我认知基座（永远最先，不被人格卡覆盖）
        sb.append(QuroPlatformManifest.SYSTEM).append("\\n\\n")

        // 应用语言感知：让用户所选语言成为 AI 默认回复语言（用户改用其他语言时自然跟随）
        val appLang = _appLanguage.value
        if (appLang != "system") {
            val langName = com.ai.assistance.quro.util.QuroLocale.LANGUAGE_NAMES[appLang] ?: appLang
            sb.append("## 回复语言\\n请用 ").append(langName).append(" 回复用户（除非用户用其他语言提问）。\\n\\n")
        }'''
patch(vm, [(vm_inject_old, vm_inject_new)])
print("done")
