# -*- coding: utf-8 -*-
"""L3 接口补强：进度回调需要携带 stage/current/total。

为什么必须补：
  旧实现回调 Java 的是 `onProgress(String stage, int current, int total)`
  —— 三参数。而 L3 的 `ProgressFn` 只有 `int percent`。

  这不是"少一个参数"的小事：Kotlin 侧拿 stage 判断是不是 prefill、
  拿 current/total 决定是否显示进度条。压成 percent 会丢：

    · stage —— Kotlin 侧无法区分阶段（以后 decode 阶段也可能要上报）；
    · current/total —— Kotlin 侧 `LOCAL_PREFILL_PROGRESS_TOKEN_THRESHOLD`
      是按 **token 数**判断的（多轮只新增几十 token 时不上屏进度条，
      消除"每轮弹 正在处理提示词 X%"）。百分比反推 token 数会失真。

  进度条抖动的观感问题曾经真机上被投诉过，所以这里不允许有损换算。

幂等：已存在则跳过。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "llm", "core", "include", "quro", "engine_types.h")

ANCHOR = "using ProgressFn = void (*)(void* user, int percent);"

NEW = """/// 进度载荷。stage 指向的缓冲**仅在该次回调有效**，与 TokenChunk 同规矩。
struct ProgressChunk {
    const char* stage = nullptr;   // 阶段名，如 "prefill"
    int current = 0;               // 已处理 token 数（不是百分比）
    int total = 0;                 // 本轮要处理的 token 总数
};

/// 不要把它压成百分比：上层要按 token 数决定「值不值得显示进度条」
/// （多轮对话只新增几十 token 时不该弹进度），百分比反推 token 数会失真。
using ProgressFn = void (*)(void* user, const ProgressChunk& chunk);"""


def main():
    with open(TARGET, "rb") as f:
        raw = f.read()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")

    if "ProgressChunk" in text:
        print("已存在 ProgressChunk，跳过。")
        return 0

    n = text.count(ANCHOR)
    if n != 1:
        print("锚点命中 %d 次（期望 1），未改动。" % n)
        return 1

    text = text.replace(ANCHOR, NEW, 1)
    out = text.replace("\n", "\r\n") if crlf else text
    with open(TARGET, "wb") as f:
        f.write(out.encode("utf-8"))
    print("已为 L3 增加 ProgressChunk，ProgressFn 改为携带 stage/current/total。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
