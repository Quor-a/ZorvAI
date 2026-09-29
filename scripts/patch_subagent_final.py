#!/usr/bin/env python3
# 确定性补齐子智能体开关（ViewModel 状态/Setter + 两处 ask 调用；ChatScreen 状态行），
# 并统一 QuroAssistant.kt 的行尾为 CRLF。保留各文件原始行尾，避免整文件被 git 标记改动。
import io

VM = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt"
CS = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt"
QA = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/core/QuroAssistant.kt"

SUBAGENT_BLOCK = (
    "    // 子智能体（Sub-Agent）开关：默认开启，用户可在对话控制条「子智能体」开关控制。\n"
    "    // 开启时主智能体可派发独立子智能体分担聚焦子任务；关闭后 spawn_subagent 工具不再下发给模型。\n"
    "    private val _subAgentEnabled = MutableStateFlow(uiPrefs.getBoolean(\"sub_agent_enabled\", true))\n"
    "    val subAgentEnabled: StateFlow<Boolean> = _subAgentEnabled.asStateFlow()\n"
    "\n"
    "    fun setSubAgentEnabled(on: Boolean) {\n"
    "        _subAgentEnabled.value = on\n"
    "        uiPrefs.edit { putBoolean(\"sub_agent_enabled\", on) }\n"
    "    }"
)


def load(path):
    raw = io.open(path, "r", encoding="utf-8", newline="").read()
    crlf = "\r\n" in raw
    return raw, raw.replace("\r\n", "\n"), crlf


def save(path, text, crlf):
    out = text.replace("\n", "\r\n") if crlf else text
    io.open(path, "w", encoding="utf-8", newline="").write(out)


# ---- ViewModel ----
raw, v, crlf = load(VM)
anchor_vm = ('    fun setAutoSaveMemory(on: Boolean) {\n'
             '        _autoSaveMemory.value = on\n'
             '        uiPrefs.edit { putBoolean("auto_save_memory", on) }\n'
             '    }\n'
             '\n'
             '    // 外观与对话设置：深色模式')
assert anchor_vm in v, "ViewModel setAutoSaveMemory 锚点未找到"
if "_subAgentEnabled" not in v:
    cut = anchor_vm.rindex("    // 外观")
    v = v.replace(anchor_vm, anchor_vm[:cut] + SUBAGENT_BLOCK + "\n\n    // 外观与对话设置：深色模式", 1)
    print("ViewModel: 已插入子智能体状态/Setter")
else:
    print("ViewModel: 子智能体状态已存在，跳过")

GEN_SUBSTR = "autoSaveMemory = autoSaveMemory.value, stream = true"
GEN_REPL = "autoSaveMemory = autoSaveMemory.value, subAgentEnabled = subAgentEnabled.value, stream = true"
if GEN_SUBSTR in v and GEN_REPL not in v:
    v = v.replace(GEN_SUBSTR, GEN_REPL, 1)
    print("ViewModel: 已给 genAssistant.ask 补 subAgentEnabled")
else:
    print("ViewModel: genAssistant.ask 无需改动")
save(VM, v, crlf)
print("ViewModel 写入完成")

# ---- ChatScreen ----
raw, c, crlf = load(CS)
anchor_cs = '    val autoSaveMemory by vm.autoSaveMemory.collectAsState()'
assert anchor_cs in c, "ChatScreen autoSaveMemory 状态锚点未找到"
if "val subAgentEnabled by vm.subAgentEnabled.collectAsState()" not in c:
    c = c.replace(anchor_cs, anchor_cs + "\n    val subAgentEnabled by vm.subAgentEnabled.collectAsState()", 1)
    print("ChatScreen: 已插入 subAgentEnabled 状态")
else:
    print("ChatScreen: subAgentEnabled 状态已存在，跳过")
save(CS, c, crlf)
print("ChatScreen 写入完成")

# ---- QuroAssistant：统一行尾为 CRLF（修复此前 LF 插入造成的混用），并核验关键改动 ----
raw, q, crlf = load(QA)
# 若此前是 CRLF 但内部混用了 LF（插入块），直接整体规整为 CRLF
q2 = q  # 已规范为 LF
assert "val agentAwareSpecs =" in q2, "QuroAssistant agentAwareSpecs 定义缺失"
assert "tools = if (activeToolRouter != null) activeToolRouter.activeSpecs() else agentAwareSpecs," in q2, "云端 tools= 未改用 agentAwareSpecs"
assert "runSubAgent(c.arguments, cfg, context, effTemperature, effMaxTokens)" in q2, "子智能体调用点参数不正确"
assert "companion object" in q2 and 'const val SUBAGENT_TOOL_NAME' in q2, "子智能体常量未定义"
save(QA, q2, crlf=True)  # QuroAssistant 原始为 CRLF，统一刷回 CRLF
print("QuroAssistant: 行尾已规整为 CRLF，关键改动核验通过")
