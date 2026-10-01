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

# ⚠️ 必须钉固定 SHA，不能用 "master"。
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

# 【QuroAI 构建补丁】qwenVideoProcess 符号修复。
# MNN 上游已内置该符号，无需额外 stub。保留注释以供追溯。
# 若未来上游再次移除，可恢复下方 stub 定义。

# 包含 JNI 绑定代码（只包含我们需要的文件）
# ---------------------------------------------------------------------------
# 【L2/L3/L4 分层拆分】单文件 1785 行的 mnnllmnative.cpp 已删除，拆为：
#
#   L2  mnn_jni.cpp                  JNI 薄层：解参（含 kotlin.Pair 反射）→ 句柄取引擎
#                                    → 调引擎 → 回转。**不 include 任何 MNN 头**，
#                                    不出现一次 llm-> / MNN:: 调用
#   L3  mnn_engine.cpp               生命周期 / 分词 / 配置 / 统计
#       mnn_engine_generate.cpp      流式生成（合并了旧实现里两份 235 行的重复函数）
#       mnn_engine_chat.cpp          聊天模板 / 结构化工具调用 / 上下文与配置导出
#       mnn_engine_registry.cpp      自注册进 L3 引擎工厂（旧构架下此处是死代码）
#   L4  mnn_engine_logic.inc         纯函数层（JSON 转义、LlmContext→JSON、
#                                    ChatML 工具说明与回退渲染、结构化渲染核心），
#                                    由脚本从旧实现逐字抽取，见 scripts/refactor_split_mnn.py
#
# 拆分原则（不可违反）：**搬家不改逻辑**。那些长注释背后都是真机事故
#   （minja 依赖缺失导致恒返回空串、ChatML 缺 generation prompt 导致 0 token、
#    set_config 注入 jinja tools 在某些构建抛异常把整链路打成 ok=false、
#    UTF-8 跨 token 边界、`<eop>` 结束标记…），重写必然漂移。
#
# 附带的结构性修复：cancel / lastError / 音频回调三张**以裸 Llm* 为键的全局 map**
#   收进引擎实例字段 —— 旧代码自己注释了"指针会被复用，残留的错误串会串味"。
# ---------------------------------------------------------------------------
add_library(
    MNNWrapper
    SHARED
    src/main/cpp/mnnnetnative.cpp
    src/main/cpp/mnnmodulennative.cpp
    src/main/cpp/mnn_jni.cpp
    src/main/cpp/mnn_engine.cpp
    src/main/cpp/mnn_engine_generate.cpp
    src/main/cpp/mnn_engine_chat.cpp
    src/main/cpp/mnn_engine_registry.cpp
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
# 【L2 · 符号隔离】MNN 静态化后，backend 注册器（纯 static 构造、无外部引用）
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
)

# 【L2 · 符号隔离】修正 A：MNN 静态化后**不能**再显式链接 llm 对象库。
#
# MNN_SEP_BUILD=OFF 时 llm 是 OBJECT 库，它的对象已经通过 MNN_OBJECTS_TO_LINK
# 进了 libMNN.a。这里再 target_link_libraries(MNNWrapper llm) 会让同一批
# .o 在链接行上出现两次（一次显式、一次来自 --whole-archive 的 libMNN.a），
# 直接报 duplicate symbol：MNN::Transformer::Embedding / DiskEmbedding / Llm
# 等一长串。共享构建时两者是分开的 .so 所以看不出来，静态化后才暴露。
# 全量静态时交给 libMNN.a 就够了，故仅保留非 monolithic 分支。
if(TARGET llm AND NOT QURO_LLM_MONOLITHIC)
    target_link_libraries(MNNWrapper llm)
endif()

# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# 【L2 · 符号隔离】修正 B：从 libMNN.a 里剔除 MNN 自带的 JNIHelper.cpp.o
#
# 背景：--whole-archive 是**必需**的，不能省。因为 MNN 的 Vulkan 后端是这样
# 注册的（source/backend/vulkan/runtime/VulkanRuntime.cpp:290）：
#
#     static bool gResistor = []() {
#         MNNInsertExtraRuntimeCreator(MNN_FORWARD_VULKAN, new VulkanRuntimeCreator, true);
#         return false;
#     }();
#
#   匿名静态初始化、没有任何外部引用 —— 静态链接时这个翻译单元会被链接器
#   按「未被引用」整体丢掉，Vulkan 后端就静默消失了（症状正是老问题
#   "Can't Find type=3 backend"）。CPU/OpenCL 那批是 Backend.cpp 里直接
#   函数调用（registerBackend() → registerCPURuntimeCreator() …）所以不会被丢，
#   但 Vulkan 这批必须靠 --whole-archive 兜住。
#
# 代价：MNN 的 source/utils/JNIHelper.cpp 自己也定义了 JNI_OnLoad
#   （JNIHelper.cpp:58，作用是缓存 JavaVM 给 MNN 的 JNI demo 用）。
#   --whole-archive 把它一起拉进来，就与本 wrapper 的 JNI_OnLoad 冲突。
#
# 为什么不干脆用 -Wl,--allow-multiple-definition：
#   那是个全局开关，会把这个 .so 里**所有**重复强符号都变成静默取第一个。
#   我们的目的恰恰是让符号问题暴露出来，用它等于把警报器关掉。
#
# 为什么可以直接删这个成员（已核实）：
#   JNIHelper.cpp 只导出 AttachCurrentThread / DetachCurrentThread / JNI_OnLoad，
#   三者在 MNN 全仓（source / include / express）都没有任何引用点 ——
#   唯一的调用方 mnnportraitnative.cpp 本构建不编译。
#   删掉它既不缺符号，也顺手去掉一段死代码。
#
# 若将来 MNN 把 JNIHelper 用起来了，这里会以「undefined symbol」直接报错，
# 是安全失败模式（不会静默变成运行时崩溃）。
# ---------------------------------------------------------------------------
# 注意：这里必须用「自定义目标 + 依赖」而不是 add_custom_command(TARGET MNN POST_BUILD)。
# CMake 规定 add_custom_command 的 TARGET 形式只能在**创建该目标的那个目录**里调用；
# MNN 的目标是 add_subdirectory(${MNN_SOURCE_DIR}) 在子目录里建的，本文件里调它会直接
# configure 失败："TARGET 'MNN' was not created in this directory."
# 改成自定义目标后，谁依赖它谁就等它跑完 —— 让 MNNWrapper 依赖它即可。
if(QURO_LLM_MONOLITHIC AND TARGET MNN)
    add_custom_target(quro_strip_mnn_jnihelper
        COMMAND "${CMAKE_AR}" d "$<TARGET_FILE:MNN>" "JNIHelper.cpp.o"
        COMMENT "quro L2: 从 libMNN.a 剔除 JNIHelper.cpp.o（其 JNI_OnLoad 与 wrapper 冲突）"
        VERBATIM
    )
    add_dependencies(quro_strip_mnn_jnihelper MNN)
    add_dependencies(MNNWrapper quro_strip_mnn_jnihelper)
endif()

# 添加 16KB 页面大小支持（Android 15+ 要求）
# 这确保所有 LOAD 段对齐到 16KB 边界
target_link_options(MNNWrapper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(MNN PRIVATE "-Wl,-z,max-page-size=16384")
if(TARGET llm)
    target_link_options(llm PRIVATE "-Wl,-z,max-page-size=16384")
endif()

