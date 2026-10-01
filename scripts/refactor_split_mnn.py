#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ── 拆分层时的三个必炸点（动手前先看这三条，都是真踩过的）───────────────
#
#  ① 私有嵌套类型不可在类外命名。
#     `MnnEngine::Impl` / `LlamaEngine::Impl` 是 private 嵌套类型，类外的自由函数
#     一旦在签名里写出它就 `error: 'Impl' is a private member of ...`。
#     本项目出现两次（`requireReady`、`requireLl`）。自由函数只收 `Session*` 即可。
#
#  ② `.inc` 里只被一个 TU include 的 `inline` 函数不会发射符号。
#     `inline int utf8CharLength(...)` 在本 TU 内没被取地址 → 编译器不发射符号
#     → 调用方在另一个 TU → 链接期 `undefined symbol`。**必须去掉 `inline`**。
#     （静态库不解析符号，所以编译期、归档期都看不出问题。）
#
#  ③ 静态库不解析符号。`libquro_llm_core.a` 里"头文件声明了但源文件没实现"，
#     编译期与归档期都不报错，直到某个 SHARED 目标第一次真引用才链接期爆炸。
#     **"core 单独编译通过"完全不能证明 core 是完整的。**
#
# 抽完请依次跑：
#   python scripts/brace_check_mnn.py <native目录>                  # 结构完整性
#   python scripts/check_jni_binding.py <*_jni.cpp> <Kotlin声明>      # JNI 绑定
#   然后 ./gradlew :app:assembleFullRelease，最后产物级验收（wrapper 导出符号数 = 1）
# ─────────────────────────────────────────────────────────────────────
"""
从 mnnllmnative.cpp 逐字抽取「纯函数层」到 mnn_engine_logic.inc。

> **注意：SRC 源文件已随本次重构从工作树删除。** 要复现抽取，先恢复源文件：
> `git show 1e05d48^:llm/mnn/src/main/cpp/mnnllmnative.cpp > llm/mnn/src/main/cpp/mnnllmnative.cpp`
> （llama 侧把路径换成 `llm/llama/src/main/cpp/llama_jni_stub.cpp`，commit 用 `04e6506^`）
> 抽完记得把恢复出来的源文件删掉，别把它留在工作树里污染构建。


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
