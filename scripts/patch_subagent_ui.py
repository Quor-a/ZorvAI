#!/usr/bin/env python3
# 确定性补齐 ViewModel / ChatScreen 中子智能体开关状态与下发（绕过 Edit 工具本仓库锁问题）。
import io

VM = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt"
CS = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt"

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

# ---- ViewModel ----
with io.open(VM, "r", encoding="utf-8", newline="") as f:
    v = f.read()

anchor_vm = ('    fun setAutoSaveMemory(on: Boolean) {\n'
             '        _autoSaveMemory.value = on\n'
             '        uiPrefs.edit { putBoolean("auto_save_memory", on) }\n'
             '    }\n'
             '\n'
             '    // 外观与对话设置：深色模式')
assert anchor_vm in v, "ViewModel setAutoSaveMemory 锚点未找到"
if "_subAgentEnabled" not in v:
    v = v.replace(anchor_vm,
                  anchor_vm[:anchor_vm.rindex("    // 外观")] + SUBAGENT_BLOCK + "\n\n    // 外观与对话设置：深色模式",
                  1)
    print("ViewModel: 已插入子智能体状态/Setter")
else:
    print("ViewModel: 子智能体状态已存在，跳过")

# genAssistant.ask（主对话路径）补 subAgentEnabled（按子串，忽略前导缩进）
GEN_SUBSTR = "autoSaveMemory = autoSaveMemory.value, stream = true"
GEN_REPL = "autoSaveMemory = autoSaveMemory.value, subAgentEnabled = subAgentEnabled.value, stream = true"
if GEN_SUBSTR in v and GEN_REPL not in v:
    v = v.replace(GEN_SUBSTR, GEN_REPL, 1)
    print("ViewModel: 已给 genAssistant.ask 补 subAgentEnabled")
else:
    print("ViewModel: genAssistant.ask 无需改动")

with io.open(VM, "w", encoding="utf-8", newline="") as f:
    f.write(v)
print("ViewModel 写入完成")

# ---- ChatScreen ----
with io.open(CS, "r", encoding="utf-8", newline="") as f:
    c = f.read().replace("\r\n", "\n")

anchor_cs = '    val autoSaveMemory by vm.autoSaveMemory.collectAsState()'
assert anchor_cs in c, "ChatScreen autoSaveMemory 状态锚点未找到"
if "val subAgentEnabled by vm.subAgentEnabled.collectAsState()" not in c:
    c = c.replace(anchor_cs, anchor_cs + "\n    val subAgentEnabled by vm.subAgentEnabled.collectAsState()", 1)
    print("ChatScreen: 已插入 subAgentEnabled 状态")
else:
    print("ChatScreen: subAgentEnabled 状态已存在，跳过")

with io.open(CS, "w", encoding="utf-8", newline="") as f:
    f.write(c)
print("ChatScreen 写入完成")
