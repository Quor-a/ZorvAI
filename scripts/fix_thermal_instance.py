# -*- coding: utf-8 -*-
"""
补上 llm/core/src/thermal.cpp 缺失的 ThermalGovernor::instance() 定义。

背景（值得记下来，因为这是个会重复出现的坑）：
  libquro_llm_core.a 是**静态库**，而静态库不解析符号 —— 只要没人链接它，
  头文件里声明了但源文件里没实现的成员函数**不会报任何错**。
  直到某个 SHARED 目标（libLlamaWrapper.so）第一次真正引用它，
  才会在链接期报 undefined symbol。所以「core 单独编译通过」不代表 core 是完整的。

本次就是这种情况：thermal.h 声明了 static ThermalGovernor& instance()，
thermal.cpp 实现了 configure/sample/last/probeAvailable/advisedThreads/
advisedBatch/sustainedThrottle/reset 共 8 个，偏偏漏了唯一的构造入口 instance()。

幂等：已存在定义则跳过。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "llm", "core", "src", "thermal.cpp")

ANCHOR = "ThermalGovernor::ThermalGovernor() : impl_(new Impl()) {"

BLOCK = """// ── 单例 ────────────────────────────────────────────────────────────────────
// 为什么用「函数内 static」而不是文件级静态对象：
//   1. 线程安全：C++11 起函数内 static 的初始化由编译器插入 guard 变量，
//      多线程首次并发进入也只会构造一次。本层会被 JNI 工作线程并发调用，
//      文件级静态对象在这里要靠「动态库加载期串行」来兜底，不可靠。
//   2. **惰性**：只有真正用到温控的进程才会构造它。文件级静态对象会在
//      .so 加载期就构造，而温控要 dlopen("libandroid.so") —— 那属于
//      「加载期做 IO」，是所有卡启动 / 加载失败的经典来源。
//   3. 销毁顺序：函数内 static 的析构由 atexit 最后处理，不会被本层其他
//      静态对象反序析构之后当成野指针继续访问。
ThermalGovernor& ThermalGovernor::instance() {
    static ThermalGovernor governor;
    return governor;
}

"""


def main():
    with open(TARGET, "rb") as f:
        raw = f.read()

    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")

    if "ThermalGovernor::instance()" in text:
        print("已存在 instance() 定义，跳过。")
        return 0

    count = text.count(ANCHOR)
    if count != 1:
        print("锚点命中 %d 次（期望 1 次），未改动：%s" % (count, ANCHOR))
        return 1

    text = text.replace(ANCHOR, BLOCK + ANCHOR, 1)

    out = text.replace("\n", "\r\n") if crlf else text
    with open(TARGET, "wb") as f:
        f.write(out.encode("utf-8"))

    print("已补入 ThermalGovernor::instance() 定义（换行保持 %s）。"
          % ("CRLF" if crlf else "LF"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
