# -*- coding: utf-8 -*-
"""
把 llama_jni_stub.cpp 的**非 JNI 逻辑**逐字抽出来，产出一个被 include 的实现片段。

为什么用「抽取」而不是「重写」：
  那 2000 行里的每一段长注释背后都是一次真机事故 —— KV 前缀复用的退化保护、
  llama_batch.seq_id 的指针覆写（free 栈地址 → SIGABRT）、llama_decode 返回码
  1/2 的语义、BOS 双补/丢失、EOG 首 token 空输出……重写一遍必然漂移。
  分层重构要改的是**归属**，不是逻辑。

所以本脚本做的事：
  1. 从原文件里按锚点切出「辅助逻辑区段」与「采样链构造」两块，原样保留；
  2. 只做三类**机械替换**：
       · LlamaSessionNative → Session（会话类型的公开名字）
       · jint → int32_t（引擎层不得依赖 JNI 类型；impl.h 里已按 int32_t 声明）
       · 去掉 impl.h 已声明的那些函数的 static（它们要跨 TU 被 chat.cpp 调用）
     以及删除 jbooleanToBool（它是 JNI 布尔转换，属于 L2，不该出现在引擎层）；
  3. 输出 llm/llama/src/main/cpp/llama_engine_logic.inc

输出是 .inc（不是 .cpp）：它被 llama_engine.cpp 在 namespace 内 include，
不参与独立编译，因此不需要在 CMake 源列表里登记。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "llm", "llama", "src", "main", "cpp", "llama_jni_stub.cpp")
OUT = os.path.join(ROOT, "llm", "llama", "src", "main", "cpp", "llama_engine_logic.inc")

# impl.h 已声明 → 必须去掉 static 才能跨 TU 链接
EXPORTED = [
    "rebuildSamplerForSession",
    "createSamplerChain",
    "tokenizeText",
    "tokenizeTextToVector",
    "buildToolCallGrammarConfig",
    "resetToolCallState",
    "initializeChatTemplatesForSession",
    "buildChatMessages",
    "tokenToPiece",
    "prefillToolCallGenerationPrompt",
    "applyThermalAdvice",
    "ensureBackendInit",
    "utf8CharLength",
    "positiveOrDefaultUInt",
    "positiveOrDefaultInt",
    "abortCallback",
]


def slice_between(text, start_anchor, end_anchor):
    i = text.find(start_anchor)
    if i < 0:
        raise SystemExit("找不到起始锚点：%r" % start_anchor[:60])
    j = text.find(end_anchor, i)
    if j < 0:
        raise SystemExit("找不到结束锚点：%r" % end_anchor[:60])
    return text[i:j]


def main():
    with open(SRC, "rb") as f:
        raw = f.read()
    text = raw.decode("utf-8").replace("\r\n", "\n")

    # ── 片段 1：采样链构造（含惰性文法触发）──
    sampler = slice_between(
        text,
        "static llama_sampler * createSamplerChain(",
        "static jstring stringToJstring",
    )
    # 该片段以 "#endif" 收尾（原文件用条件编译包着），新文件不需要
    sampler = sampler.rstrip()
    if sampler.endswith("#endif"):
        sampler = sampler[: -len("#endif")].rstrip()

    # ── 片段 2：会话级辅助逻辑 ──
    logic = slice_between(
        text,
        "// ─────────────────────────── L5 · 温控自适应 ───────────────────────────",
        "} // namespace\n\nextern \"C\" JNIEXPORT jboolean JNICALL\nJava_com_ai_assistance_llama_LlamaNative_nativeIsAvailable",
    )
    logic = logic.rstrip()

    body = "// ═══ 采样链构造 ═══\n" + sampler + "\n\n// ═══ 会话级辅助逻辑 ═══\n" + logic + "\n"

    # ── 删除 SET_ERR 宏 ──
    # 它要被 llama_engine_generate.cpp / llama_engine_chat.cpp 一起用，
    # 所以上移到 llama_engine_impl.h；留在 .inc 里会重复定义。
    body = re.sub(
        r"// 记录失败原因（同时打 LOGE，保留 logcat 链路）\n#define SET_ERR[\s\S]*?\} while \(0\)\n\n?",
        "",
        body,
    )

    # ── 机械替换 1：会话类型改名 ──
    body = body.replace("LlamaSessionNative", "Session")

    # ── 机械替换 2：引擎层不得出现 JNI 标量类型 ──
    body = body.replace("(jint value, uint32_t defaultValue)", "(int32_t value, uint32_t defaultValue)")
    body = body.replace("(jint value, int32_t defaultValue)", "(int32_t value, int32_t defaultValue)")

    # ── 删除 jbooleanToBool（属于 L2）──
    body = re.sub(
        r"static bool jbooleanToBool\([^)]*\)\s*\{[^}]*\}\n\n?",
        "",
        body,
    )

    # ── 机械替换 3：去 static（仅 impl.h 已声明者）──
    for fn in EXPORTED:
        body = re.sub(r"(?m)^static\s+([^\n]*\b%s\s*\()" % re.escape(fn), r"\1", body)

    # ── 机械替换 4：去 inline ──
    # 本 .inc 只被 llama_engine.cpp 一个 TU include，而调用方在
    # llama_engine_generate.cpp。inline 函数在本 TU 内没被取地址时编译器**不发射符号**
    # → 另一个 TU 的引用在链接期炸 `undefined symbol`（编译期完全看不出来，
    # 因为静态库不解析符号）。这和 ThermalGovernor::instance() 漏定义是同一类坑。
    # 只处理 EXPORTED 名单里的函数：内部崩溃处理函数留 inline 无妨（同 TU 使用）。
    for fn in EXPORTED:
        body = re.sub(r"(?m)^inline\s+([^\n]*\b%s\s*\()" % re.escape(fn), r"\1", body)

    header = (
        "// 本文件由 scripts/refactor_split_llama.py 从 llama_jni_stub.cpp 自动抽取，\n"
        "// **不要手改** —— 改逻辑请改 llama_engine.cpp / llama_engine_chat.cpp，\n"
        "// 若要重新抽取，请修改脚本而不是本文件。\n"
        "//\n"
        "// 内容：llama.cpp 引擎的会话级辅助逻辑（温控调档、采样链重建、分词、\n"
        "// 聊天模板初始化、工具调用文法抽取、KV 预填充辅助、native 崩溃落盘）。\n"
        "// 全部逐字来自旧实现，仅做分层归属调整（见脚本头部说明）。\n"
    )

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(header + "\n" + body)

    print("已生成 %s" % os.path.relpath(OUT, ROOT))
    print("  行数：%d" % len(body.splitlines()))
    print("  残留 LlamaSessionNative：%d（应为 0）" % body.count("LlamaSessionNative"))
    print("  残留 jint/jboolean：%d（应尽量为 0）" % (body.count("jint") + body.count("jboolean")))
    print("  static 残留（应只含崩溃处理等内部函数）：%d" % len(re.findall(r"(?m)^static ", body)))
    for m in re.finditer(r"(?m)^static\s+[^\n]*?(\w+)\s*\(", body):
        print("    · %s" % m.group(1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
