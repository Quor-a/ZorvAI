#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抽取 ChatScreen.kt 设置面板里的硬编码中文为 stringResource，并生成 values-en / values-zh 翻译资源。
仅替换带引号的字符串字面量（不会误伤注释），且每键都会写入默认 values/strings.xml 以保证编译通过。
"""
import io, os, re

BASE = r"D:\Calw OS-project\QuroAI"
CHAT = os.path.join(BASE, "app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt")
STRINGS = os.path.join(BASE, "app/src/main/res/values/strings.xml")

# (中文, key, 英文)
M = [
    ("设置", "settings_title", "Settings"),
    ("偏好、外观与功能，随手可调。", "settings_sub", "Preferences, appearance and features, all within reach."),
    ("外观与对话", "settings_appearance_label", "Appearance & Conversation"),
    ("深色模式 · 字号 · 提示音 · 回车发送 · 语音球", "settings_appearance_sub", "Dark mode · Font size · Sound · Enter to send · Voice ball"),
    ("语音", "settings_group_voice", "Voice"),
    ("语音服务", "settings_voice_service", "Voice Service"),
    ("合成 / 识别 / 设置", "settings_voice_service_sub", "TTS / ASR / Settings"),
    ("功能", "settings_group_features", "Features"),
    ("模型配置", "settings_model_config", "Model Config"),
    ("推理引擎、参数与能力范围", "settings_model_config_sub", "Inference engine, params and capability scope"),
    ("技能管理", "settings_skills", "Skill Manager"),
    ("自定义 SKILL：新增 / 编辑 / 启用 / 停用", "settings_skills_sub", "Custom SKILL: add / edit / enable / disable"),
    ("功能模型配置", "settings_feature_model_config", "Function Model Config"),
    ("上下文总结 / 记忆 / 人格孵化 / 视频 / 图片", "settings_feature_model_config_sub", "Summary / Memory / Persona / Video / Image"),
    ("权限", "settings_permission", "Permissions"),
    ("L1 无障碍 / L2 Shizuku / L3 设备管理员 / L4 ROOT", "settings_permission_sub", "L1 A11y / L2 Shizuku / L3 Device Admin / L4 ROOT"),
    ("LSPosed 模块", "settings_lsposed", "LSPosed Module"),
    ("钩子注入 / 作用域管理", "settings_lsposed_sub", "Hook injection / scope management"),
    ("USB / 无线调试", "settings_usb_debug", "USB / Wireless Debug"),
    ("ADB：被电脑控制 · 本机客户端 · TCP 监听", "settings_usb_debug_sub", "ADB: PC control · local client · TCP listen"),
    ("默认应用", "settings_default_app", "Default Apps"),
    ("桌面启动器 / 浏览器 / 相册 / 视频 / 邮箱 / 文档 / 短信 / 拨号", "settings_default_app_sub", "Launcher / Browser / Gallery / Video / Mail / Docs / SMS / Dialer"),
    ("GitHub 管理", "settings_github", "GitHub Manager"),
    ("登录后管理仓库 / Issue / Star / 通知，对话框内可搜 GitHub", "settings_github_sub", "Manage repos / Issues / Stars / notifications after login; search GitHub in chat"),
    ("离线模型下载", "settings_model_hub", "Offline Model Download"),
    ("内置多官方镜像直链，一键下载 GGUF 本地推理权重", "settings_model_hub_sub", "Built-in official mirror links, one-tap GGUF weight download"),
    ("系统状态", "settings_system_status", "System Status"),
    ("设备 / 权限能力 / 模块运行态 / 人格心跳", "settings_system_status_sub", "Device / permission capability / module state / persona heartbeat"),
    ("组件画廊", "settings_component_gallery", "Component Gallery"),
    ("可视化组件库：卡片 / 按钮 / 输入 / 交互 / 覆盖层", "settings_component_gallery_sub", "Visual component library: card / button / input / interaction / overlay"),
    ("插件运行时", "settings_plugins", "Plugin Runtime"),
    ("Web 应用式插件 Demo", "settings_plugins_sub", "Web-app style plugin demo"),
    ("灵魂注入", "settings_persona", "Soul Injection"),
    ("灵魂注入 · 灵魂卡 · 记忆库", "settings_persona_sub", "Soul injection · soul card · memory library"),
    ("MCP 服务", "settings_mcp", "MCP Service"),
    ("把内置工具以 MCP 协议暴露给本机客户端", "settings_mcp_sub", "Expose built-in tools via MCP to local clients"),
    ("ACI 管理中心", "settings_aci", "ACI Manager"),
    ("已发现第三方 App / 绑定状态 / 能力清单 / 手动注册刷新重绑", "settings_aci_sub", "Discovered 3rd-party apps / bind state / capability list / manual re-bind"),
    ("真实 PTY 终端（实验）", "settings_pty", "Real PTY Terminal (Experimental)"),
    ("伪终端：vim/top/REPL 可交互、SIGINT 正常；出问题请关闭回退管道", "settings_pty_sub", "Pseudo-terminal: vim/top/REPL interactive, SIGINT works; disable fallback pipe if issues"),
    ("破坏性命令二次确认（授权）", "settings_destructive", "Destructive Command Confirm (Auth)"),
    ("开启后 rm -rf / dd / mkfs 等危险命令需再次发送或 confirm 才执行", "settings_destructive_sub", "When on, dangerous cmds like rm -rf / dd / mkfs need re-send or confirm"),
    ("通知", "settings_group_notification", "Notifications"),
    ("AI 回复通知", "settings_ai_reply_notify", "AI Reply Notification"),
    ("离开软件时系统弹窗通知 / 桌面卡片", "settings_ai_reply_notify_sub", "System popup / desktop card when away from app"),
    ("数据", "settings_group_data", "Data"),
    ("导出对话", "settings_export", "Export Chat"),
    ("导出为文本", "settings_export_sub", "Export as text"),
    ("清理存储", "settings_cleanup", "Clean Storage"),
    ("分类清理日志、缓存、AI产物等", "settings_cleanup_sub", "Clean logs, cache, AI artifacts by category"),
    ("文件管理", "settings_file_manager", "File Manager"),
    ("浏览沙箱目录 · 在系统文件管理器中打开", "settings_file_manager_sub", "Browse sandbox dir · open in system file manager"),
    ("清除全部对话", "settings_clear_all", "Clear All Chats"),
    ("关于", "settings_group_about", "About"),
    ("关于 Zorv AI", "settings_about", "About Zorv AI"),
    ("项目地址 / 开源许可 / 开发者", "settings_about_sub", "Project URL / open-source license / developer"),
]

# 1) 改写 ChatScreen.kt：仅替换带引号的中文字面量为 stringResource(R.string.key)
with io.open(CHAT, "r", encoding="utf-8") as f:
    src = f.read()

for zh, key, en in M:
    lit = '"' + zh + '"'
    repl = "stringResource(R.string." + key + ")"
    if lit in src:
        src = src.replace(lit, repl)
    else:
        print("WARN not found literal:", lit)

with io.open(CHAT, "w", encoding="utf-8") as f:
    f.write(src)

# 2) 默认 values/strings.xml 追加新键（中文），已存在则跳过
with io.open(STRINGS, "r", encoding="utf-8") as f:
    sxml = f.read()
added = []
for zh, key, en in M:
    if ('name="%s"' % key) not in sxml:
        added.append('    <string name="%s">%s</string>' % (key, zh))
if added:
    insert = "\n".join(added) + "\n"
    sxml = sxml.replace("</resources>", insert + "</resources>")
    with io.open(STRINGS, "w", encoding="utf-8") as f:
        f.write(sxml)
print("default strings.xml added", len(added))

# 原有 34 个默认键的英文翻译（用于 values-en）
EXTRA = {
    "app_name": "Zorv AI",
    "quro_accessibility_desc": "Zorv AI accessibility service: for UI automation and screen content reading and other advanced capabilities. This version only completes permission declarations and does not collect any private data.",
    "quro_device_admin_label": "Zorv AI Device Admin",
    "quro_device_admin_desc": "Used for only two operations: lock screen, disable or restore camera. Does not wipe data or modify lock screen password.",
    "ime_label": "Zorv AI Keyboard",
    "sc_voice_short": "Voice Ball",
    "sc_voice_long": "Launch floating voice ball",
    "sc_voice_disabled": "Voice ball disabled",
    "sc_chat_short": "Mini Chat",
    "sc_chat_long": "Enter chat directly",
    "sc_chat_disabled": "Chat disabled",
    "sc_browser_short": "Browser Console",
    "sc_browser_long": "ZorvAI Browser Console",
    "sc_browser_disabled": "Browser console disabled",
    "settings_appearance": "Appearance & Conversation",
    "appearance_group_appearance": "Appearance",
    "appearance_group_conversation": "Conversation",
    "appearance_dark_mode": "Dark Mode",
    "appearance_dark_mode_desc": "Lower brightness at night",
    "appearance_font_size": "Font Size",
    "appearance_sound": "Reply Sound",
    "appearance_enter_send": "Enter to Send",
    "appearance_enter_send_desc": "New line on enter when off",
    "appearance_voice_ball": "Floating Voice Ball",
    "appearance_voice_ball_desc": "STT → LLM → TTS voice chat anytime",
    "appearance_history_rounds": "Keep Conversation Rounds",
    "appearance_history_rounds_desc": "Limit recent rounds sent to model",
    "appearance_group_language": "Language",
    "appearance_follow_system": "Follow System Language",
    "appearance_follow_system_desc": "No built-in language pack, use phone system language",
    "appearance_app_language": "App Language",
    "appearance_group_profile": "User Profile",
    "appearance_profile": "Avatar & Name",
    "appearance_profile_desc": "Set your profile",
}

# 3) 生成 values-en / values-zh（英文 / 中文全量）
def build_resources(translated):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    with io.open(STRINGS, "r", encoding="utf-8") as f:
        defxml = f.read()
    defaults = dict(re.findall(r'name="([^"]+)">([^<]*)</string>', defxml))
    mm = {key: (zh, en) for zh, key, en in M}
    keys = list(defaults.keys())
    for key in mm:
        if key not in keys:
            keys.append(key)
    for key in keys:
        if translated:  # en
            val = mm[key][1] if key in mm else EXTRA.get(key, defaults[key])
        else:  # zh
            val = mm[key][0] if key in mm else defaults[key]
        val = val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        lines.append('    <string name="%s">%s</string>' % (key, val))
    lines.append('</resources>')
    return "\n".join(lines) + "\n"

for lang, translated, sub in [("en", True, "values-en"), ("zh", False, "values-zh")]:
    d = os.path.join(BASE, "app/src/main/res", sub)
    os.makedirs(d, exist_ok=True)
    with io.open(os.path.join(d, "strings.xml"), "w", encoding="utf-8") as f:
        f.write(build_resources(translated))
    print("wrote", sub, "strings.xml")

print("DONE")
