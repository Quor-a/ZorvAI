#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 mnnllmnative.cpp 逐字抽取「纯函数层」到 mnn_engine_logic.inc。

为什么用抽取而不是重写：
    这些函数里每一段长注释背后都是一次真机事故（minja 依赖缺失导致恒返回空串、
    ChatML generation prompt 缺失导致 0 token 输出、set_config 注入 jinja tools
    在部分构建下抛异常把整条链路打成 ok=false…）。分层重构要改的是**归属**，
    不是逻辑 —— 重写必然漂移。

只做三类机械替换：
    1. `applyStructuredChatTemplate` → `applyStructuredChatTemplateCore`（避免与
       引擎方法同名；它现在是纯函数）
    2. `static std::string buildToolsInstruction` → 去 static（要跨 TU 用）
    3. `static std::string renderChatMlFallback` → 去 static

抽取范围（以 mnnllmnative.cpp 的 1-based 行号为准）：
    jsonEscape                531-563
    appendIntVectorJson       565-574
    llmStatusToString         576-591
    contextToJson             593-622
    buildToolsInstruction     289-308
    renderChatMlFallback      310-395
    applyStructuredChatTemplate 397-529
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "llm", "mnn", "src", "main", "cpp", "mnnllmnative.cpp")
OUT = os.path.join(ROOT, "llm", "mnn", "src", "main", "cpp", "mnn_engine_logic.inc")

# 顺序即文件内顺序：先基础（JSON/状态），再模板渲染。
RANGES = [
    ("jsonEscape", 531, 563),
    ("appendIntVectorJson", 565, 574),
    ("llmStatusToString", 576, 591),
    ("contextToJson", 593, 622),
    ("buildToolsInstruction", 289, 308),
    ("renderChatMlFallback", 310, 395),
    ("applyStructuredChatTemplateCore", 397, 529),
]

HEADER = """// =============================================================================
// L4 · MNN 引擎的纯函数层（由 scripts/refactor_split_mnn.py 从 mnnllmnative.cpp
// 逐字抽取，**不要手改** —— 要改逻辑请改抽取结果对应的引擎文件，
// 若要重新抽取，请修改脚本而不是本文件）
// =============================================================================
// 内容：JSON 转义与拼装、LlmContext → JSON、内置 ChatML 工具说明与回退渲染、
//       结构化（工具调用）提示词渲染核心。
//
// 为什么这些放一个 .inc 而不是散在各 .cpp：
//   它们是**无状态纯函数**（只依赖入参 Llm*，不碰 Session），三个 TU 都要用。
//   本文件只被 mnn_engine.cpp 一个 TU include（定义符号只能出现一次），
//   其余 TU 通过 mnn_engine_impl.h 的声明调用。
// =============================================================================

"""


def read_lines(path):
    data = io.open(path, "rb").read()
    crlf = b"\r\n" in data
    text = data.decode("utf-8")
    if crlf:
        text = text.replace("\r\n", "\n")
    return text.split("\n"), crlf


def main():
    lines, _ = read_lines(SRC)
    chunks = []
    for name, start, end in RANGES:
        body = "\n".join(lines[start - 1:end]).rstrip("\n")
        # 机械替换 1：重命名（避免与引擎成员函数同名）
        body = re.sub(r"\bapplyStructuredChatTemplate\b", "applyStructuredChatTemplateCore", body)
        # 机械替换 2/3：去 static（跨 TU 使用）
        body = re.sub(r"(?m)^static\s+std::string\s+(buildToolsInstruction|renderChatMlFallback)\s*\(",
                      r"std::string \1(", body)
        chunks.append("// ═══ %s ═══\n%s" % (name, body))

    out = HEADER + "\n\n".join(chunks) + "\n"
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)

    # 自检
    def count(pat):
        return len(re.findall(pat, out))

    print("已生成 %s" % os.path.relpath(OUT, ROOT))
    print("  行数：%d" % len(out.splitlines()))
    print("  残留 JNI 类型（应 0）：%d" % count(r"\bJNIEnv\b|\bjstring\b|\bjobject\b|\bjlong\b"))
    print("  残留 applyStructuredChatTemplate（非 Core，应 0）：%d" % count(r"applyStructuredChatTemplate(?!Core)"))
    print("  static 残留（应 0）：%d" % count(r"(?m)^static "))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
