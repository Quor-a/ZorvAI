# =============================================================================
# llm/core · L2/L3/L5 共享层
# =============================================================================
# 产出 libquro_llm_core.a（**静态库**，被两个引擎模块各自链入一份）。
#
# 为什么是静态库而不是共享库：
#   1. 这一层的符号（句柄表、温控状态机、内存仲裁）必须是**每进程一份**。
#      做成 .so 只会多一个 dlopen 依赖；做成 static 直接编进各自 .so，
#      而 :llm 进程里实际只会加载一个引擎，语义正好。
#   2. 静态库不产生导出符号，天然不参与跨 .so 的符号竞争 ——
#      与 version script 的目标一致。
#
# 本层**不依赖** MNN / llama.cpp 的任何头文件，因此可以单独编译验证：
#   cmake -B build -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 llm/core
# =============================================================================
cmake_minimum_required(VERSION 3.22.1)

# 两个引擎模块用 add_subdirectory 复用本层；只有在「单独构建本目录」时才
# 声明 project()。在子目录里重复 project() 会重置父作用域的一批变量，
# 是已知的 CMake 坑，因此显式加守卫。
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project("quro_llm_core" CXX)
endif()

# 与两个引擎模块保持一致的优化级别：Debug 构建也强制 -O3。
# （温控采样、句柄查表都在热路径上，-O0 会让它们变成可观测的开销。）
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_library(quro_llm_core STATIC
    src/engine.cpp
    src/engine_factory.cpp
    src/handle_registry.cpp
    src/thermal.cpp
    src/resource.cpp
    src/memory_arbiter.cpp
    src/backend_policy.cpp
    src/jni_support.cpp
    src/think_splitter.cpp
)

target_include_directories(quro_llm_core PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/include"
)

target_compile_features(quro_llm_core PUBLIC cxx_std_17)

# -fvisibility=hidden：即使将来本层被编成共享库，也默认不导出。
# version script 是最后一道闸，这是第一道。
target_compile_options(quro_llm_core PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_compile_definitions(quro_llm_core PRIVATE
    # 明确声明：本层从不使用异常跨 JNI 边界（engine.h 的契约）。
    -DQURO_LLM_NO_EXCEPTIONS_ACROSS_JNI=1
)

# 位置无关代码：静态库会被链进 SHARED 目标，必须开 PIC。
set_target_properties(quro_llm_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

# 导出 include 目录给上层（两个引擎模块的 target_link_libraries 里自动生效）。
# 注意：这里**不**链接 android / log —— 由最终 SHARED 目标统一链接，
# 避免静态库重复声明依赖。但本层源文件用了 <android/log.h>，
# 所以需要保证上层链接了 liblog。两个引擎模块都已经链接了。
