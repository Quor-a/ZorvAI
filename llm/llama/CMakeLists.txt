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

quro_prepare_git_source(
    QURO_LLAMA_CPP_SOURCE_DIR
    QURO_LLAMA_CPP_BINARY_DIR
    llama_cpp
    "https://github.com/ggml-org/llama.cpp.git"
    "720d7fa4097f76e5d0eade5a92c1df87c1faf9d9"
)

add_subdirectory("${QURO_LLAMA_CPP_SOURCE_DIR}" "${QURO_LLAMA_CPP_BINARY_DIR}" EXCLUDE_FROM_ALL)

add_library(
    LlamaWrapper
    SHARED
    src/main/cpp/llama_jni_stub.cpp
)

target_compile_definitions(LlamaWrapper PRIVATE OPERIT_HAS_LLAMA_CPP=1)
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

# 16KB page size support (Android 15+ requirement)
target_link_options(LlamaWrapper PRIVATE "-Wl,-z,max-page-size=16384")
