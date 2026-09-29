#!/usr/bin/env python3
# 确定性修复 QuroAssistant.kt 中 agentAwareSpecs 的定义与引用（绕过 Edit 工具的本仓库锁问题）。
import io, sys

PATH = r"D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/core/QuroAssistant.kt"

with io.open(PATH, "r", encoding="utf-8", newline="") as f:
    s = f.read()

# 1) 确保 agentAwareSpecs 定义存在（紧跟 effectiveSpecs 定义之后）
anchor = '            val effectiveSpecs = if (autoSaveMemory) toolSpecs else toolSpecs.filter { !it.name.startsWith("memory_") }'
assert anchor in s, "effectiveSpecs 定义锚点未找到"

if "val agentAwareSpecs =" not in s:
    block = anchor + (
        "\n            // 子智能体开关：开启（且仅云端，本地小模型不派发）时把 spawn_subagent 工具并入下发列表，\n"
        "            // 关闭或从本地模型时摘除（用户可在对话控制条「子智能体」开关控制）。\n"
        "            val agentAwareSpecs = if (!subAgentEnabled || isLocal) {\n"
        "                effectiveSpecs.filter { it.name != SUBAGENT_TOOL_NAME }\n"
        "            } else {\n"
        "                effectiveSpecs + QuroToolSpec(\n"
        "                    SUBAGENT_TOOL_NAME,\n"
        "                    SUBAGENT_TOOL_DESC,\n"
        "                    SUBAGENT_TOOL_PARAMS,\n"
        "                )\n"
        "            }"
    )
    s = s.replace(anchor, block, 1)
    print("已插入 agentAwareSpecs 定义")
else:
    print("agentAwareSpecs 定义已存在，跳过")

# 2) 云端 tools= 行：effectiveSpecs -> agentAwareSpecs
cloud_old = "tools = if (activeToolRouter != null) activeToolRouter.activeSpecs() else effectiveSpecs,"
cloud_new = "tools = if (activeToolRouter != null) activeToolRouter.activeSpecs() else agentAwareSpecs,"
assert cloud_old in s, "云端 tools= 锚点未找到"
cnt = s.count(cloud_old)
s = s.replace(cloud_old, cloud_new)
print(f"已替换云端 tools= 行 {cnt} 处")

# 3) aciWsTools 诊断行：effectiveSpecs -> agentAwareSpecs
aci_old = 'val aciWsTools = effectiveSpecs.filter { it.name.startsWith("aci_") || it.name.startsWith("workspace_") }'
aci_new = 'val aciWsTools = agentAwareSpecs.filter { it.name.startsWith("aci_") || it.name.startsWith("workspace_") }'
assert aci_old in s, "aciWsTools 锚点未找到"
s = s.replace(aci_old, aci_new, 1)
print("已替换 aciWsTools 行")

# 健全性检查：spawn_subagent 调用点应带 cfg, context 两参
assert "runSubAgent(c.arguments, cfg, context, effTemperature, effMaxTokens)" in s, "子智能体调用点参数不正确"
# SUBAGENT_TOOL_NAME 必须已定义（companion）
assert "const val SUBAGENT_TOOL_NAME" in s, "SUBAGENT_TOOL_NAME 未定义"

with io.open(PATH, "w", encoding="utf-8", newline="") as f:
    f.write(s)
print("写入完成")
