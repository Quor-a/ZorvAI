cmake_minimum_required(VERSION 3.22.1)

project("llama_jni")

# ---------------------------------------------------------------------------
# 【#1116】原生层永远以 -O3 编译，即使 app 走 debug 构建。
#
# 背景：AGP 对 debug buildType 默认传 CMAKE_BUILD_TYPE=Debug → ggml/llama 全部
# -O0。后果有两条，都是致命的：
#   1) 手机 CPU 上推理慢几十倍 → 用户侧表现为「点了没反应 / 卡死」；
#   2) ggml 的 SIMD kernel 在 -O0 下不做寄存器分配，局部量全落栈，栈帧膨胀数倍，
#      在工作线程（默认栈仅 1MB 级）里跑 mul_mat 极易越界 → SIGSEGV。
# 真机崩溃栈正是 libggml-cpu.so 的 mul_mat。因此这里强制覆盖 Debug 的优化级别。
# 保留 -g，方便日后 addr2line 还原符号。
# ---------------------------------------------------------------------------
# ⚠️ 必须用「普通 set」而非 set(... CACHE ... FORCE)：
# Android NDK 的 android.toolchain.cmake 已用普通 set 定义了 CMAKE_*_FLAGS_DEBUG，
# 普通变量会遮蔽 cache 变量 —— 用 CACHE FORCE 只会改 CMakeCache.txt 好看，
# 生成出来的 build.ninja 里依旧没有任何 -O 选项（Clang 默认 = -O0）。踩过一次，勿改回去。
set(CMAKE_C_FLAGS_DEBUG "-O3 -g")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g")
# 双保险：CMAKE_*_FLAGS 对所有 config 生效，出现在 FLAGS 最前面。
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/quro_git_source.cmake")

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# 【#1116】关闭 OpenMP，改用 ggml 自带 threadpool。
#
# 对齐 PocketPal 所用的 llama.rn —— 它在 Android 上明确 set(GGML_OPENMP OFF)。
# 原因：
#   1) 真机崩溃栈经 libomp.so!__kmp_invoke_micro_task 进入 mul_mat，libomp 的
#      worker 线程栈大小/亲和性不受我们控制；
#   2) libomp 会按自己的策略开满核，绕过我们在 Kotlin 侧对线程数的封顶，
#      导致主线程 CPU 饿死（UI 冻结）；
#   3) ggml 自带 threadpool 严格遵守 n_threads，行为可预期，还少打一个 .so。
# ---------------------------------------------------------------------------
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

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

quro_prepare_git_source(
    QURO_LLAMA_CPP_SOURCE_DIR
    QURO_LLAMA_CPP_BINARY_DIR
    llama_cpp
    "https://github.com/ggml-org/llama.cpp.git"
    "720d7fa4097f76e5d0eade5a92c1df87c1faf9d9"
)

add_subdirectory("${QURO_LLAMA_CPP_SOURCE_DIR}" "${QURO_LLAMA_CPP_BINARY_DIR}" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# 【L2/L3/L4 分层拆分】单文件 2074 行的 llama_jni_stub.cpp 已删除，拆为：
#
#   L2  llama_jni.cpp                JNI 薄层：解参 → 句柄取引擎 → 调引擎 → 回转
#                                    **不 include llama.h**，不出现一次 llama_* 调用
#   L3  llama_engine.cpp             Engine 契约实现（生命周期/分词/分阶段推理/统计）
#       llama_engine_generate.cpp    一体化 generate（KV 前缀复用，行为与旧实现逐字一致）
#       llama_engine_chat.cpp        聊天模板 / 工具调用文法 / 采样参数
#       llama_engine_registry.cpp    自注册进 L3 引擎工厂（旧构架下此处是死代码）
#   L4  llama_engine_logic.inc       会话级辅助逻辑（由脚本从旧实现逐字抽出，
#                                    见 scripts/refactor_split_llama.py）
#
# 拆分原则（不可违反）：**搬家不改逻辑**。那些长注释背后都是一次真机事故
#   （BOS 双补、seq_id free 栈地址 SIGABRT、llama_decode 返回 1 = 失败、
#   KV 前缀退化的重复 cell 投毒、UTF-8 跨 token 边界…），重写必然漂移。
# ---------------------------------------------------------------------------
add_library(
    LlamaWrapper
    SHARED
    src/main/cpp/llama_jni.cpp
    src/main/cpp/llama_engine.cpp
    src/main/cpp/llama_engine_generate.cpp
    src/main/cpp/llama_engine_chat.cpp
    src/main/cpp/llama_engine_registry.cpp
)

target_compile_definitions(LlamaWrapper PRIVATE QURO_HAS_LLAMA_CPP=1)
target_include_directories(LlamaWrapper PRIVATE "${QURO_LLAMA_CPP_SOURCE_DIR}")
target_include_directories(LlamaWrapper PRIVATE "${QURO_LLAMA_CPP_SOURCE_DIR}/common")
target_include_directories(LlamaWrapper PRIVATE "${QURO_LLAMA_CPP_SOURCE_DIR}/include")
target_include_directories(LlamaWrapper PRIVATE "${QURO_LLAMA_CPP_SOURCE_DIR}/ggml/include")
target_include_directories(LlamaWrapper PRIVATE "${QURO_LLAMA_CPP_SOURCE_DIR}/vendor")
target_link_libraries(LlamaWrapper llama llama-common)

target_link_libraries(
    LlamaWrapper
    android
    log
)

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

# 16KB page size support (Android 15+ requirement)
target_link_options(LlamaWrapper PRIVATE "-Wl,-z,max-page-size=16384")
