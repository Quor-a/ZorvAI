#!/usr/bin/env python3
import io
VM = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt"
raw = io.open(VM, "r", encoding="utf-8", newline="").read()
crlf = "\r\n" in raw
v = raw.replace("\r\n", "\n")
old = "genAssistant.ask(appContext, effectiveCfg, sysPrompt, autoSaveMemory = autoSaveMemory.value, stream = true"
new = "genAssistant.ask(appContext, effectiveCfg, sysPrompt, autoSaveMemory = autoSaveMemory.value, subAgentEnabled = subAgentEnabled.value, stream = true"
assert old in v, "genAssistant.ask 锚点未找到"
assert new not in v, "genAssistant.ask 已含 subAgentEnabled"
v = v.replace(old, new, 1)
io.open(VM, "w", encoding="utf-8", newline="").write(v.replace("\n", "\r\n") if crlf else v)
print("ViewModel: 已给 genAssistant.ask 补 subAgentEnabled")
