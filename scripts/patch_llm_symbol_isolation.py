#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
L2 · 符号隔离改造 —— 把两个引擎模块收敛成「一个 .so 只导出 JNI_OnLoad」。

背景（实测数据，来自改造前 v1.1.0 的构建产物）：
  llm/llama -> 6 个 .so，合计 11030 个动态符号
      libllama.so 5458 / libllama-common.so 3558 / libggml-base.so 1524
      libggml-cpu.so 575 / libggml.so 96 / libLlamaWrapper.so 252
  llm/mnn   -> 2 个 .so，合计 3234 个动态符号
      libMNN.so 3018 / libMNNWrapper.so 216
  两个 wrapper 都**没有** JNI_OnLoad —— JVM 全靠「符号名自动绑定」，
  这正是导出表必须全开的根本原因。

  两栈动态符号交集 2080 个，减去 libc++ 的 std::__ndk1::* 后剩 38 个，
  全部是 libc++abi：__cxa_* 共 30 个 + operator new/delete 共 6 个 +
  __dynamic_cast + __gxx_personality_v0。
  其中 __cxa_get_globals / __cxa_get_globals_fast 返回的是**每线程一份**
  的异常全局量 __cxa_eh_globals。两个 .so 各自静态链了一份 libc++abi，
  动态链接器只会解析到「先加载的那个」；一旦两边构造自不同 NDK 版本
  （libc++abi 结构体布局变了），throw 与 catch 就会操作不同的全局量
  → 内存损坏，且崩溃栈指向 catch 侧、真因在另一个 .so，极难定位。

  没有 MNN 与 ggml/llama 之间的引擎级符号冲突（MNN 走 dlopen 拿 OpenCL，
  本构建未启用 ggml 的 Vulkan/OpenCL 后端），所以这次要消的就是上面那
  38 个 libc++abi 重复符号。

做法：
  1) 上游 MNN / llama.cpp+ggml 改为**静态**链入各自的 wrapper
     -> .so 数量 8 → 2，libc++abi 重复面归零；
  2) 两个 wrapper 挂 version script，导出表收敛到只剩 JNI_OnLoad
     -> 配合已注入的 RegisterNatives 注册表，JVM 不再需要按名查找。

脚本幂等：每个目标文件各带一个 marker，已改造则跳过。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CORE_CMAKE = os.path.join(ROOT, "llm", "core", "CMakeLists.txt")
LLAMA_CMAKE = os.path.join(ROOT, "llm", "llama", "CMakeLists.txt")
MNN_CMAKE = os.path.join(ROOT, "llm", "mnn", "CMakeLists.txt")
MNN_LOADER = os.path.join(
    ROOT, "llm", "mnn", "src", "main", "java", "com", "ai", "assistance", "mnn",
    "MNNLibraryLoader.kt",
)
MNN_GRADLE = os.path.join(ROOT, "llm", "mnn", "build.gradle.kts")

MARK = "【L2 · 符号隔离】"


# ---------------------------------------------------------------------------
# 读写工具：本仓 CMakeLists / Kotlin 普遍是 CRLF，锚点统一按 LF 匹配后再写回。
# ---------------------------------------------------------------------------
def sniff_newline(raw):
    crlf = raw.count(b"\r\n")
    lf = raw.count(b"\n") - crlf
    return "\r\n" if (crlf > 0 and crlf >= lf) else "\n"


def read(path):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
    return text, sniff_newline(raw)


def write(path, text, nl):
    with open(path, "wb") as f:
        f.write(text.replace("\n", nl).encode("utf-8"))


def rel(path):
    return os.path.relpath(path, ROOT)


def patch(path, pairs, marker=MARK):
    """pairs: list of (anchor, payload, mode)；mode in {'after','replace'}"""
    name = rel(path)
    src, nl = read(path)
    if marker in src:
        print("  跳过（已改造）: %s" % name)
        return False
    out = src
    for anchor, payload, mode in pairs:
        if anchor not in out:
            raise SystemExit("锚点未找到于 %s:\n%s" % (name, anchor[:240]))
        if out.count(anchor) != 1:
            raise SystemExit("锚点不唯一(%d) 于 %s:\n%s"
                             % (out.count(anchor), name, anchor[:240]))
        out = out.replace(anchor, payload if mode == "replace" else anchor + payload)
    write(path, out, nl)
    print("  已改造: %s  (换行=%s)" % (name, "CRLF" if nl == "\r\n" else "LF"))
    return True


# ---------------------------------------------------------------------------
# 1) llm/core/CMakeLists.txt —— 允许被 add_subdirectory 复用
# ---------------------------------------------------------------------------
CORE_MARKER = "if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)"
CORE_OLD = 'project("quro_llm_core" CXX)'
CORE_NEW = """# 两个引擎模块用 add_subdirectory 复用本层；只有在「单独构建本目录」时才
# 声明 project()。在子目录里重复 project() 会重置父作用域的一批变量，
# 是已知的 CMake 坑，因此显式加守卫。
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project("quro_llm_core" CXX)
endif()"""

# ---------------------------------------------------------------------------
# 2) llm/llama/CMakeLists.txt
# ---------------------------------------------------------------------------
LLAMA_ANCHOR = 'set(GGML_OPENMP OFF CACHE BOOL "" FORCE)\n'

LLAMA_OPTION_BLOCK = """
# ---------------------------------------------------------------------------
# 【L2 · 符号隔离】单 .so 收敛：上游 llama.cpp / ggml 改为**静态**链入 wrapper
#
# 改造前：llm 模块产出 6 个 .so（libLlamaWrapper / libllama / libllama-common /
#         libggml / libggml-base / libggml-cpu），合计导出 11030 个动态符号；
#         libllama.so 一个就 5458 个，其中包含 statically-linked libc++abi 的
#         __cxa_throw / __cxa_get_globals / operator new 等 38 个运行时符号。
#         MNN 侧的 libMNN.so 同样导出这 38 个 —— 而 __cxa_get_globals 返回的
#         是每线程一份的 __cxa_eh_globals；两份 libc++abi 若构造自不同 NDK
#         （结构体布局不同），throw 与 catch 会操作到不同的全局量，异常处理
#         直接内存损坏。崩溃栈指向 catch 侧、真因在另一个 .so，极难定位。
#
# 改造后：上游全部静态链入 LlamaWrapper，本 .so 由 version script 只导出
#         JNI_OnLoad 一个符号（见 llm/core/quro_llm.map）。llama 侧贡献的
#         跨 .so 符号面 = 0，上面那个隐患从根上消失。
#
# 静态链接**不需要** --whole-archive：ggml/src/ggml-backend-reg.cpp 在
# #ifdef GGML_USE_CPU 下直接调用 ggml_backend_cpu_reg()，是真实符号引用，
# 链接器会自动拉入 CPU 后端对象，不存在「后端注册器被丢弃」的问题。
# （静态也是 llama.cpp 在 Android 上的默认构建方式，官方支持。）
# ---------------------------------------------------------------------------
option(QURO_LLM_MONOLITHIC "把上游引擎静态链入 wrapper（单 .so 符号隔离）" ON)

if(QURO_LLM_MONOLITHIC)
    # 必须在 add_subdirectory(llama.cpp) 之前设好：llama.cpp 内部用
    # option(BUILD_SHARED_LIBS ...) 定义默认值，先占住 cache 它就不会覆盖。
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "quro: static upstream for symbol isolation" FORCE)
endif()
"""

LLAMA_LINK_ANCHOR = """target_link_libraries(
    LlamaWrapper
    android
    log
)
"""

LLAMA_LINK_BLOCK = """
# ---------------------------------------------------------------------------
# 【L2 · 符号隔离】接入 L2/L3/L5 共享层 + 收敛导出表
# ---------------------------------------------------------------------------
add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../core"
    "${CMAKE_CURRENT_BINARY_DIR}/quro_llm_core"
    EXCLUDE_FROM_ALL
)

target_link_libraries(LlamaWrapper quro_llm_core)

# 第一道闸：编译期默认隐藏。JNI_OnLoad 由 JNIEXPORT 声明为 default
# visibility，不受影响；其余（含 Java_xxx 原生方法与静态链入的上游符号）
# 一律 hidden。
target_compile_options(LlamaWrapper PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

# 第二道闸（最后一道、也是唯一作用于 .dynsym 的）：version script。
# 注意 local: * 是必须的兜底 —— 只写 global 段的话，未列出的符号默认仍是
# default visibility，等于没隔离。
target_link_options(LlamaWrapper PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/../core/quro_llm.map"
)

# 验收（必须真跑，不能只看配置）：
#   llvm-nm -D --defined-only libLlamaWrapper.so
#   期望输出只有一行：JNI_OnLoad
"""

# ---------------------------------------------------------------------------
# 3) llm/mnn/CMakeLists.txt
# ---------------------------------------------------------------------------
MNN_ANCHOR = 'set(MNN_SEP_BUILD OFF CACHE BOOL "Build backends separately" FORCE)\n'

# ---------------------------------------------------------------------------
# 6) 把 MNN 的分支引用钉成固定 SHA（构建可复现）
# ---------------------------------------------------------------------------
MNN_PIN_OLD = """quro_prepare_git_source(
    MNN_SOURCE_DIR
    QURO_MNN_BINARY_DIR
    mnn
    "https://github.com/alibaba/MNN.git"
    "master"
)"""

MNN_PIN_NEW = """# ⚠️ 必须钉固定 SHA，不能用 "master"。
# 原来写的是分支名，导致每次构建都重新 ls-remote 解析一次 master：上游一有新
# commit 就换一份全新的源码目录 + 全量重编 MNN（小时级），而且**构建不可复现** ——
# 同一个 tag 的 APK 两次构建出来的 libMNN.so 可能来自不同上游代码，出问题无法回溯。
# 本地已验证：master 短短几天内先后解析出 cda4a6f4 / bef71b97 / 024a946b 三个 SHA。
#
# 下面的 SHA 就是产出当前线上 v1.1.0 的 libMNN.so 的那一份
# （判据：llm/mnn/.cxx/quro_deps/mnn-bef71b97…-build 下有 893 个文件在最后一次
#  构建时被更新，另一个 cda4a6f4…-build 只有 128 个且早于它）。
# 换版本时改这一行，并且要同步改 build.gradle.kts 注释里记的 commit 号。
quro_prepare_git_source(
    MNN_SOURCE_DIR
    QURO_MNN_BINARY_DIR
    mnn
    "https://github.com/alibaba/MNN.git"
    "bef71b9756a2c77549eddbe33eb97290e3b16602"
)"""


MNN_OPTION_BLOCK = """
# ---------------------------------------------------------------------------
# 【L2 · 符号隔离】单 .so 收敛：上游 MNN 改为**静态**链入 wrapper。
# 理由同 llm/llama/CMakeLists.txt —— 消掉两份 libc++abi 的 38 个重复符号。
#
# ⚠️ 这里**不能**改成「保留 libMNN.so 共享、只给它加 version script」：
#    实测 MNNWrapper 从 libMNN.so 引用的 250 个符号里，有 120 个是
#    std::__ndk1::basic_string / vector 的模板实例 —— C++ ABI 面本来就跨
#    这个 .so 边界共享，local: * 会把它们一起隐藏，直接链接失败。
#    静态化后两边同处一个 .so，这个矛盾自然消失。
#
# ⚠️ MNN 的 backend 注册器是**纯 static 构造、无外部引用**，静态链接时会被
#    链接器按「未被引用」丢弃（症状就是老问题 "Can't Find type=3 backend"）。
#    因此下面链接 MNN 时必须用 --whole-archive 强制全量拉入。
# ---------------------------------------------------------------------------
option(QURO_LLM_MONOLITHIC "把上游引擎静态链入 wrapper（单 .so 符号隔离）" ON)

if(QURO_LLM_MONOLITHIC)
    # 必须在 add_subdirectory(MNN) 之前设好，否则被 MNN 的 option() 默认值盖掉。
    set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "quro: static MNN for symbol isolation" FORCE)
endif()
"""

MNN_LINK_OLD = """target_link_libraries(
    MNNWrapper
    MNN
    android
    log
    jnigraphics
)"""

MNN_LINK_NEW = """# 【L2 · 符号隔离】MNN 静态化后，backend 注册器（纯 static 构造、无外部引用）
# 会被链接器当死代码丢掉 → 必须 --whole-archive 强制全量拉入。
# 用「以 - 开头的列表项」而不是 target_link_options：后者会被 CMake 放到
# 所有 -l 之前，--whole-archive 会误夹住 liblog/libandroid/libc++_static，
# 反而把系统库整包吞进来。
target_link_libraries(
    MNNWrapper
    -Wl,--whole-archive
    MNN
    -Wl,--no-whole-archive
    android
    log
    jnigraphics
)"""

MNN_TAIL_OLD = """# 添加 16KB 页面大小支持（Android 15+ 要求）
# 这确保所有 LOAD 段对齐到 16KB 边界
target_link_options(MNNWrapper PRIVATE "-Wl,-z,max-page-size=16384")"""

MNN_TAIL_NEW = """# ---------------------------------------------------------------------------
# 【L2 · 符号隔离】接入 L2/L3/L5 共享层 + 收敛导出表
# ---------------------------------------------------------------------------
add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../core"
    "${CMAKE_CURRENT_BINARY_DIR}/quro_llm_core"
    EXCLUDE_FROM_ALL
)

target_link_libraries(MNNWrapper quro_llm_core)

# 第一道闸：编译期默认隐藏（JNI_OnLoad 由 JNIEXPORT 保住 default visibility）。
target_compile_options(MNNWrapper PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

# 第二道闸：version script。local: * 是必须的兜底 —— 只写 global 段等于没隔离。
target_link_options(MNNWrapper PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/../core/quro_llm.map"
)

# 验收（必须真跑）：
#   llvm-nm -D --defined-only libMNNWrapper.so
#   期望输出只有一行：JNI_OnLoad

# 添加 16KB 页面大小支持（Android 15+ 要求）
# 这确保所有 LOAD 段对齐到 16KB 边界
target_link_options(MNNWrapper PRIVATE "-Wl,-z,max-page-size=16384")"""

# ---------------------------------------------------------------------------
# 4) MNNLibraryLoader.kt —— MNN 静态化后 libMNN.so 不再产出
# ---------------------------------------------------------------------------
LOADER_OLD = """            try {
                // 首先加载 MNN 核心库
                System.loadLibrary("MNN")
                Log.d(TAG, "MNN library loaded successfully")
                
                // 然后加载我们的 JNI 包装库
                System.loadLibrary("MNNWrapper")
                Log.d(TAG, "MNNWrapper library loaded successfully")
                
                loaded = true
            } catch (e: UnsatisfiedLinkError) {"""

LOADER_NEW = """            try {
                // 【L2 · 符号隔离】MNN 已改为静态链入 MNNWrapper（见 llm/mnn/CMakeLists.txt），
                // 因此 libMNN.so 不再产出。这里保留一次探测：万一将来关掉
                // QURO_LLM_MONOLITHIC 回退到共享构建，老路径依然可用。
                try {
                    System.loadLibrary("MNN")
                    Log.d(TAG, "MNN library loaded successfully (separate .so)")
                } catch (e: UnsatisfiedLinkError) {
                    Log.d(TAG, "libMNN.so 不存在，按静态链接布局继续（预期行为）")
                }

                // 然后加载我们的 JNI 包装库（静态构建下它自带全部 MNN 代码）
                System.loadLibrary("MNNWrapper")
                Log.d(TAG, "MNNWrapper library loaded successfully")
                
                loaded = true
            } catch (e: UnsatisfiedLinkError) {"""


# ---------------------------------------------------------------------------
# 5) llm/mnn/build.gradle.kts —— 去掉命令行上的 MNN_BUILD_SHARED_LIBS=ON
#
# 命令行 -D 会在 CMakeLists 之前就占住 cache，虽然 CMakeLists 里的
# set(... CACHE ... FORCE) 仍能覆盖它，但两处各说各话，后人改一处会莫名失效。
# 统一由 CMakeLists 单点决定（它带 QURO_LLM_MONOLITHIC 开关）。
# ---------------------------------------------------------------------------
GRADLE_OLD = '                    "-DMNN_BUILD_SHARED_LIBS=ON",'

GRADLE_NEW = ('                    // 库类型（共享/静态）由 llm/mnn/CMakeLists.txt 决定：'
              '那里按 QURO_LLM_MONOLITHIC\n'
              '                    // 设 MNN_BUILD_SHARED_LIBS，用于 L2 符号隔离。'
              '此处不再传 -D，避免两处\n'
              '                    // 各说各话（命令行 -D 会先占住 cache，让 CMakeLists 里的设置看起来失效）。')


def main():
    print("[1/5] llm/core/CMakeLists.txt")
    patch(CORE_CMAKE, [(CORE_OLD, CORE_NEW, "replace")], marker=CORE_MARKER)

    print("[2/5] llm/llama/CMakeLists.txt")
    patch(LLAMA_CMAKE, [
        (LLAMA_ANCHOR, LLAMA_OPTION_BLOCK, "after"),
        (LLAMA_LINK_ANCHOR, LLAMA_LINK_BLOCK, "after"),
    ])

    print("[3/5] llm/mnn/CMakeLists.txt")
    patch(MNN_CMAKE, [
        (MNN_ANCHOR, MNN_OPTION_BLOCK, "after"),
        (MNN_LINK_OLD, MNN_LINK_NEW, "replace"),
        (MNN_TAIL_OLD, MNN_TAIL_NEW, "replace"),
        (MNN_PIN_OLD, MNN_PIN_NEW, "replace"),
    ])

    print("[4/5] llm/mnn/build.gradle.kts")
    patch(MNN_GRADLE, [(GRADLE_OLD, GRADLE_NEW, "replace")],
          marker="由 llm/mnn/CMakeLists.txt 决定")

    print("[5/5] MNNLibraryLoader.kt")
    if not os.path.exists(MNN_LOADER):
        print("  文件不存在，跳过")
        return 0
    src, nl = read(MNN_LOADER)
    if "静态链接布局" in src:
        print("  跳过（已改造）")
    elif LOADER_OLD in src:
        write(MNN_LOADER, src.replace(LOADER_OLD, LOADER_NEW), nl)
        print("  已改造")
    else:
        raise SystemExit("MNNLibraryLoader.kt 锚点未找到")

    print("完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
