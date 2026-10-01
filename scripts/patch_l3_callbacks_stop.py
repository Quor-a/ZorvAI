# -*- coding: utf-8 -*-
"""L3 接口补强：给 Callbacks 增加 shouldStop。

为什么必须补：
  旧实现在 llama_jni_stub.cpp 里靠 `env->CallBooleanMethod(callback, midOnToken, jdelta)`
  的返回值来决定「是否继续生成」—— Java 侧返回 false 即 break。
  而 L3 的 `Callbacks::onToken` 是 void，表达不了这个语义。

  两条路：
    A) 把 onToken 改成返回 bool。但回调可能从引擎内部工作线程触发，
       返回值要跨线程/跨帧传回，语义脆弱；而且「停止」本来就是 cancel 的职责，
       让每个 token 回调都携带控制流会把两件事混在一起。
    B) 增加独立的 shouldStop 询问点（本方案）。引擎在每个 token 边界问一次
       「还要继续吗」，与 cancel 一样是**拉取式**的，方向单一、线程安全。

  选 B。它是 Engine 契约里 cancel 的补充：
    cancel()      = 外部（如 UI 点停止）单方面终止
    shouldStop()  = 接收方（如 JNI 回调被 Java 拒绝）请求终止
  两者在两个引擎里走同一条 break 路径。

幂等：已存在则跳过。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "llm", "core", "include", "quro", "engine_types.h")

ANCHOR = """using TokenFn = void (*)(void* user, const TokenChunk& chunk);
using ProgressFn = void (*)(void* user, int percent);

struct Callbacks {
    TokenFn onToken = nullptr;
    ProgressFn onProgress = nullptr;
    void* user = nullptr;

    bool valid() const { return onToken != nullptr; }
};"""

NEW = """using TokenFn = void (*)(void* user, const TokenChunk& chunk);
using ProgressFn = void (*)(void* user, int percent);
/// 拉取式停止询问：返回 true 表示「不要再产出了，收尾吧」。
/// 引擎应在每个 token / chunk 边界调用一次；为 nullptr 时视为永不停止。
using StopFn = bool (*)(void* user);

struct Callbacks {
    TokenFn onToken = nullptr;
    ProgressFn onProgress = nullptr;
    /// 与 cancel() 的分工：
    ///   cancel()      —— 外部单方面终止（UI 点停止、卸载模型）
    ///   shouldStop()  —— 接收方请求终止（如 JNI 层把 token 交给 Java 后，
    ///                    Java 返回 false 表示"我不要了"）
    /// 两者在引擎里走**同一条 break 路径**，所以不需要在 onToken 的返回值里
    /// 再塞一层控制流 —— 回调保持 void，语义单一。
    StopFn shouldStop = nullptr;
    void* user = nullptr;

    bool valid() const { return onToken != nullptr; }

    /// 接收方是否已请求停止。nullptr 安全。
    bool stopped() const { return shouldStop != nullptr && shouldStop(user); }
};"""


def main():
    with open(TARGET, "rb") as f:
        raw = f.read()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")

    if "StopFn" in text:
        print("已存在 StopFn，跳过。")
        return 0

    n = text.count(ANCHOR)
    if n != 1:
        print("锚点命中 %d 次（期望 1），未改动。" % n)
        return 1

    text = text.replace(ANCHOR, NEW, 1)
    out = text.replace("\n", "\r\n") if crlf else text
    with open(TARGET, "wb") as f:
        f.write(out.encode("utf-8"))
    print("已为 Callbacks 增加 shouldStop / stopped()。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
