cmake_minimum_required(VERSION 3.22.1)

project("mnn_jni")

# ---------------------------------------------------------------------------
# 【#1116】原生层永远以 -O3 编译，即使 app 走 debug 构建。
# AGP 对 debug buildType 默认传 CMAKE_BUILD_TYPE=Debug → MNN 全部 -O0，
# 手机上推理慢几十倍，用户侧表现为「点了没反应 / 卡死」。与 llm/llama 保持一致。
# ---------------------------------------------------------------------------
# ⚠️ 必须用「普通 set」而非 CACHE FORCE：NDK toolchain 的同名普通变量会遮蔽 cache 值。
set(CMAKE_C_FLAGS_DEBUG "-O3 -g")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g")
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/quro_git_source.cmake")

quro_prepare_git_source(
    MNN_SOURCE_DIR
    QURO_MNN_BINARY_DIR
    mnn
    "https://github.com/alibaba/MNN.git"
    "master"
)

quro_prepare_git_source(
    KLEIDIAI_SRC_DIR
    QURO_KLEIDIAI_BINARY_DIR
    kleidiai
    "https://github.com/ARM-software/kleidiai.git"
    "v1.16.0"
)

# 使用 CMake Policy CMP0077 来让 set() 覆盖 option()
if(POLICY CMP0077)
    cmake_policy(SET CMP0077 NEW)
endif()

# 设置标志以避免 MNN 的 POST_BUILD 命令问题
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "Build from command" FORCE)

# 启用 LLM 支持及相关优化
set(MNN_BUILD_LLM ON CACHE BOOL "Build LLM support" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "Use low memory mode" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "Support transformer fuse" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "CPU weight dequant gemm" FORCE)

# 启用 GPU 后端支持
set(MNN_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(MNN_OPENCL ON CACHE BOOL "Enable OpenCL backend" FORCE)
set(MNN_OPENGL ON CACHE BOOL "Enable OpenGL backend" FORCE)
set(MNN_SUPPORT_RENDER ON CACHE BOOL "Enable render backend" FORCE)

# 禁用分离编译 - 将所有后端编入 libMNN.so (解决 "Can't Find type=3 backend" 问题)
set(MNN_SEP_BUILD OFF CACHE BOOL "Build backends separately" FORCE)

# 禁用不需要的功能以加快编译
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "Build benchmark" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "Build test" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "Build tools" FORCE)
set(MNN_BUILD_QUANTOOLS OFF CACHE BOOL "Build quantools" FORCE)
set(MNN_EVALUATION OFF CACHE BOOL "Build evaluation" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "Build converter" FORCE)
set(MNN_BUILD_TRAIN OFF CACHE BOOL "Build train" FORCE)

# 关掉 LLM 引擎自带的 demo 可执行文件（含 qwen3_tts_demo）。
# 这些 demo 默认 ON 且无视 MNN_BUILD_DEMO；其中 qwen3_tts_demo.cpp 引用
# audio/audio.hpp（仅 MNN_BUILD_AUDIO=ON 才加 include 路径），本构建未开 AUDIO
# 会直接 fatal error。App 只用 llm 引擎库，不需要这些 demo，关掉即可。
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "Build LLM demo" FORCE)

# 添加 MNN 主项目
add_subdirectory(${MNN_SOURCE_DIR} ${QURO_MNN_BINARY_DIR})

# 【QuroAI 构建补丁】把 qwenVideoProcess 兜底桩挂到 MNN 的 llm 目标。
# 上游 omni.cpp 在 LLM_SUPPORT_VISION/MNN_BUILD_OPENCV 关闭时把 qwenVideoProcess 整函数
# 编译掉，但同文件 videoProcess 仍调用它，导致 libMNN.so 链接 undefined symbol。
# 本项目 tarball 拉取 MNN、不含 OpenCV 子模块，无法开启 OpenCV，故在此提供稳定符号。
# 桩与 omni.cpp.o 同属 llm 目标（链接进 libMNN.so），直接消解引用。
if(TARGET llm)
    target_sources(llm PRIVATE ${CMAKE_CURRENT_LIST_DIR}/src/main/cpp/quro_omni_video_stub.cpp)
endif()

# 包含 JNI 绑定代码（只包含我们需要的文件）
add_library(
    MNNWrapper
    SHARED
    src/main/cpp/mnnnetnative.cpp
    src/main/cpp/mnnmodulennative.cpp
    src/main/cpp/mnnllmnative.cpp
    # mnnportraitnative.cpp 使用的是不同的包名，这里不需要
)

# 包含 MNN 头文件
target_include_directories(MNNWrapper PRIVATE
    ${MNN_SOURCE_DIR}/include
    ${MNN_SOURCE_DIR}/source
    ${MNN_SOURCE_DIR}/express
    ${MNN_SOURCE_DIR}/transformers/llm/engine/include
    ${MNN_SOURCE_DIR}/transformers/llm/engine/src
    ${MNN_SOURCE_DIR}/3rd_party
)

# 添加编译选项以解决 TLS 问题
target_compile_options(MNNWrapper PRIVATE -fno-emulated-tls)
target_compile_options(MNN PRIVATE -fno-emulated-tls)
if(TARGET llm)
    target_compile_options(llm PRIVATE -fno-emulated-tls)
endif()

# 链接 MNN 库
target_link_libraries(
    MNNWrapper
    MNN
    android
    log
    jnigraphics
)

# 如果 LLM 库存在，也链接它
if(TARGET llm)
    target_link_libraries(MNNWrapper llm)
endif()

# 添加 16KB 页面大小支持（Android 15+ 要求）
# 这确保所有 LOAD 段对齐到 16KB 边界
target_link_options(MNNWrapper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(MNN PRIVATE "-Wl,-z,max-page-size=16384")
if(TARGET llm)
    target_link_options(llm PRIVATE "-Wl,-z,max-page-size=16384")
endif()

