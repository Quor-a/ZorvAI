# -*- coding: utf-8 -*-
import io

path = r"app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt"
with io.open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1) import stringResource
pkg = "package com.ai.assistance.quro.ui\n"
assert pkg in content
content = content.replace(pkg, pkg + "import androidx.compose.ui.res.stringResource\n", 1)

# 2) extend signature: add language params after followSystemLang line
sig_old = '    followSystemLang: Boolean, onToggleFollowSystemLang: () -> Unit,'
sig_new = '    followSystemLang: Boolean, onToggleFollowSystemLang: () -> Unit,\n    appLanguage: String, onSetAppLanguage: (String) -> Unit,'
assert sig_old in content
content = content.replace(sig_old, sig_new, 1)

# 3) add showLangPicker var after showHistoryPicker var
var_old = "    var showHistoryPicker by remember { mutableStateOf(false) }"
assert var_old in content
content = content.replace(var_old, var_old + "\n    var showLangPicker by remember { mutableStateOf(false) }", 1)

# 4) region slice of QuroAppearanceSettingsScreen
start = content.find("private fun QuroAppearanceSettingsScreen(")
end = content.find("private fun UserProfileEditDialog(")
assert start != -1 and end != -1 and end > start
region = content[start:end]

# 4a) literal -> stringResource (appearance labels). Order: longer first to avoid prefix clash.
lit = [
    ('"外观与对话"', "stringResource(R.string.settings_appearance)"),
    ('"外观"', "stringResource(R.string.appearance_group_appearance)"),
    ('"对话"', "stringResource(R.string.appearance_group_conversation)"),
    ('"深色模式", "夜间自动降低亮度"', "stringResource(R.string.appearance_dark_mode), stringResource(R.string.appearance_dark_mode_desc)"),
    ('"字号", ""', "stringResource(R.string.appearance_font_size), \"\""),
    ('"回复提示音", ""', "stringResource(R.string.appearance_sound), \"\""),
    ('"回车发送", "关闭后回车换行"', "stringResource(R.string.appearance_enter_send), stringResource(R.string.appearance_enter_send_desc)"),
    ('"悬浮语音球", "STT → LLM → TTS 随时语音对话"', "stringResource(R.string.appearance_voice_ball), stringResource(R.string.appearance_voice_ball_desc)"),
    ('"保留对话轮数", "限制发给模型的近期轮次"', "stringResource(R.string.appearance_history_rounds), stringResource(R.string.appearance_history_rounds_desc)"),
    ('"语言"', "stringResource(R.string.appearance_group_language)"),
    ('"用户资料"', "stringResource(R.string.appearance_group_profile)"),
    ('"头像与名字"', "stringResource(R.string.appearance_profile)"),
    ('else "设置你的资料"', 'else stringResource(R.string.appearance_profile_desc)'),
]
for o, n in lit:
    assert o in region, ("LIT NOT FOUND: " + o)
    region = region.replace(o, n)

# 4b) follow-system SetRow block -> localized + language picker
fs_old = '''                SetRow(
                    Icons.Filled.Language, "跟随系统语言", "不内置语言包，使用手机系统语言",
                    followSystemLang, onToggleFollowSystemLang, scaled,
                )'''
assert fs_old in region
fs_new = '''                SetRow(
                    Icons.Filled.Language, stringResource(R.string.appearance_follow_system), stringResource(R.string.appearance_follow_system_desc),
                    followSystemLang, onToggleFollowSystemLang, scaled,
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Language, stringResource(R.string.appearance_app_language),
                    com.ai.assistance.quro.util.QuroLocale.LANGUAGE_NAMES[appLanguage] ?: appLanguage,
                    onClick = { showLangPicker = !showLangPicker }, scaled = scaled,
                )
                if (showLangPicker) {
                    val langs = com.ai.assistance.quro.util.QuroLocale.MAJOR_LANGUAGES.filter { it.first != "system" }
                    langs.forEach { (code, name) ->
                        val selected = appLanguage == code
                        Row(
                            Modifier.fillMaxWidth().clickable { onSetAppLanguage(code); showLangPicker = false }
                                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cs.primary)
                            else Spacer(Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontSize = scaled(14), color = cs.onSurface)
                        }
                    }
                }'''
region = region.replace(fs_old, fs_new)

content = content[:start] + region + content[end:]

# 5) invocation site: pass language params
inv_old = '                    onToggleFollowSystemLang = { vm.setFollowSystemLang(!followSystemLang) },'
inv_new = '''                    onToggleFollowSystemLang = { vm.setFollowSystemLang(!followSystemLang) },
                    appLanguage = vm.appLanguagePref.collectAsState().value,
                    onSetAppLanguage = { vm.setAppLanguage(it) },'''
assert inv_old in content
content = content.replace(inv_old, inv_new, 1)

with io.open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("ChatScreen patched OK")
