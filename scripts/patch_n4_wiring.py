#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
N4 收尾：
  ① 把 supported 的真实语义写成注释（上游函数名有误导性，不写清楚必被误改）
  ② 驱动层接线 —— 否则 setThinkingMode 又是"有实现没人调"
"""
import sys

ROOT = r"D:\Calw OS-project\QuroAI"

# ── ① llama_engine.h：supported 语义澄清 ──
ENGINE_H = ROOT + r"\llm\llama\src\main\cpp\llama_engine.h"
ENGINE_H_OLD = """    /// 开关思考段（与 MNN 的 setThinkingMode 语义对齐）。
    /// supported 输出该 GGUF 内嵌模板是否真的支持 enable_thinking；
    /// **它不影响返回值** —— 配置写入成功即成功，模板是否采用由模板自己决定，
    /// 这一点与 MNN 侧的 set_config 行为保持一致。
    bool setThinkingMode(bool enabled, bool* supported, std::string* err);

    /// 当前 GGUF 模板是否支持 enable_thinking（懒探测，结果缓存在会话里）。
    bool supportsThinking(std::string* err);"""
ENGINE_H_NEW = """    /// 开关思考段（与 MNN 的 setThinkingMode 语义对齐）。
    ///
    /// 🔎 supported / supportsThinking() 的**真实语义**（别被上游函数名骗了）：
    ///   上游 API 叫 common_chat_templates_support_enable_thinking，但它返回的是
    ///   params.supports_thinking，而后者由 autoparser 解析模板得出 ——
    ///     chat.cpp:2869  supports_thinking = (autoparser.reasoning.mode != NONE)
    ///   **与 enable_thinking 的取值毫无关系**。所以它问的是
    ///   「这个模板**有**思考段结构」，等价于 MNN 侧 MnnModelCapabilities 的
    ///   emitsThinkBlock（模板会产出 <think> 段），
    ///   而**不是** supportsThinkingToggle（模板里出现 "enable_thinking" 字面）。
    ///
    ///   这个区分是有血债的：MNN 路径的注释记着 v1.0.50 那次修正 —— 拿 toggle
    ///   语义当开启依据，会把「开了 think 却吐纯文本推理、不吐 <think> 标签」的
    ///   小模型推进明文推理模式，思考段剥离逻辑完全失效，推理独白直接混进正文。
    ///   所以驱动层必须用 emits 语义来决策，两端同一口径。
    ///
    /// **supported 不影响返回值** —— 配置写入成功即成功，模板是否采用由模板
    /// 自己决定，这一点与 MNN 侧的 set_config 行为保持一致。
    bool setThinkingMode(bool enabled, bool* supported, std::string* err);

    /// 本模型模板是否**会产出思考段**（懒探测，结果缓存在会话里）。
    /// 语义见 setThinkingMode 的说明；驱动层用它决定要不要开思考。
    bool supportsThinking(std::string* err);"""
ENGINE_H_OLD = ENGINE_H_OLD.replace("\n", "\n")
ENGINE_H_NEW = ENGINE_H_NEW

# ── ② llama_engine_chat.cpp：探测函数注释澄清 ──
CHAT_CPP = ROOT + r"\llm\llama\src\main\cpp\llama_engine_chat.cpp"
CHAT_OLD = """/// 探测 + 缓存。返回模板是否支持 enable_thinking。"""
CHAT_NEW = """/// 探测 + 缓存。返回模板**是否会产出思考段** —— 注意：不是"模板认识
/// enable_thinking 这个变量"。
/// 🔎 别被上游函数名骗了：common_chat_templates_support_enable_thinking 返回的是
///    params.supports_thinking，它由 autoparser 解析模板得出
///    （chat.cpp: supports_thinking = autoparser.reasoning.mode != NONE），
///    **与 enable_thinking 的取值毫无关系**。等价于 MNN 侧的
///    MnnModelCapabilities.emitsThinkBlock（模板里有 <think> 段），
///    而不是 supportsThinkingToggle（模板里出现 "enable_thinking" 字面）。
///    驱动层正是靠这个值决定要不要开思考 —— 与 MNN 路径同一口径。"""

# ── ③ 驱动层接线：llama 会话创建后按同一口径开关思考 ──
DRIVER = ROOT + r"\app\src\full\java\com\ai\assistance\quro\core\network\QuroLocalEngineNative.kt"
DRIVER_OLD = """        return try {
            generateLlama(session, model, modelName, messages, temperature, maxTokens, onToken, toolSpecsJson, onThinking, isCanceled)"""
DRIVER_NEW = """        // 🧠 思考开关：与 MNN 路径**同一套口径**（见 createMnnSessionStatic 里
        // v1.0.50 那条长注释）——只有当模型模板**真的会产出思考段**时才显式打开
        // enable_thinking，其余一律关掉，避免「开了 think 却吐明文推理」的模型
        // 把推理独白混进正文。
        //
        // 判定值来自 llama.cpp 的 autoparser 真解析模板
        // （supports_thinking = reasoning.mode != NONE），语义等价于 MNN 的
        // MnnModelCapabilities.emitsThinkBlock —— **不是**「模板含 enable_thinking
        // 字面」那个 toggle（那正是明文推理污染的元凶）。
        //
        // 这一步此前完全缺失：MNN 路径有，llama 路径没有，于是同一个模型走两条
        // 后端时会得到不一样的思考行为。现在两端同开关、同判据、同日志措辞。
        val emitsThinkBlock = runCatching { session.supportsThinking() }.getOrDefault(false)
        runCatching { session.setThinkingMode(emitsThinkBlock) }
        QuroDiag.log(
            "LocalEngine",
            if (emitsThinkBlock) "🧠 llama thinking 模式已开启（模板含 reasoning 段）"
            else "· llama thinking 未开启（模板无 reasoning 段，避免明文推理污染）"
        )

        return try {
            generateLlama(session, model, modelName, messages, temperature, maxTokens, onToken, toolSpecsJson, onThinking, isCanceled)"""

PATCHES = [
    (ENGINE_H, [(ENGINE_H_OLD, ENGINE_H_NEW)]),
    (CHAT_CPP, [(CHAT_OLD, CHAT_NEW)]),
    (DRIVER, [(DRIVER_OLD, DRIVER_NEW)]),
]


def main():
    staged, failed = [], False
    for path, pairs in PATCHES:
        raw = open(path, "rb").read()
        crlf = b"\r\n" in raw
        s = raw.decode("utf-8").replace("\r\n", "\n")
        for old, new in pairs:
            n = s.count(old)
            if n != 1:
                print("FAIL %s: hits=%d (need 1)\n     -> %s"
                      % (path.split("\\")[-1], n, old.split("\n")[0][:70]))
                failed = True
                continue
            s = s.replace(old, new, 1)
        staged.append((path, s, crlf))
    if failed:
        print("\n整体放弃写入（防半装载）。")
        return 1
    for path, s, crlf in staged:
        out = s.replace("\n", "\r\n") if crlf else s
        open(path, "wb").write(out.encode("utf-8"))
        print("PATCHED %-30s (%s)" % (path.split("\\")[-1], "CRLF" if crlf else "LF"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
