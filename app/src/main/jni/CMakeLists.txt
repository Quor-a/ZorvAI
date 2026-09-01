cmake_minimum_required(VERSION 3.18.1)
project(quroplugin C)

set(QUICKJS_DIR ${CMAKE_SOURCE_DIR}/quickjs)

# 核心引擎：quickjs + 正则 + unicode + 工具；quickjs-libc 提供基础 IO（按需注册，不自动暴露 os）。
# 注：未定义 CONFIG_BIGNUM，故不编 libbf.c（避免 Android clang 对 __float128 的兼容问题）。
add_library(quroplugin SHARED
    quro_plugin_bridge.c
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/quickjs-libc.c
    ${QUICKJS_DIR}/libbf.c
)

target_include_directories(quroplugin PRIVATE ${QUICKJS_DIR})

target_compile_definitions(quroplugin PRIVATE
    _GNU_SOURCE
    CONFIG_VERSION="2024-01-13"
)

target_compile_options(quroplugin PRIVATE
    -O2
    -Wall
    -fPIC
    -Wno-implicit-function-declaration
    -Wno-int-to-pointer-cast
)

find_library(log-lib log)
# 16KB 页大小合规（Android 15+ / 16KB 设备必需，否则 dlopen failed / SIGSEGV）
target_link_options(quroplugin PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(quroplugin ${log-lib})

# ═══════════════════════════════════════════════════════════════
# Termux PTY 终端库（libtermux-terminal.so）
# 基于 Termux 的 JNI 实现，提供伪终端（PTY）支持
# ═══════════════════════════════════════════════════════════════
set(TERMUX_JNI_DIR ${CMAKE_SOURCE_DIR}/termux)

add_library(termux-terminal SHARED
    ${TERMUX_JNI_DIR}/termux.c
)

target_include_directories(termux-terminal PRIVATE
    ${TERMUX_JNI_DIR}
)

target_compile_options(termux-terminal PRIVATE
    -std=c11
    -Wall
    -Wextra
    -Werror
    -Os
    -fno-stack-protector
)

target_link_options(termux-terminal PRIVATE
    -Wl,--gc-sections
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(termux-terminal ${log-lib})

# ═══════════════════════════════════════════════════════════════
# qurohost — 原生终端 / CMS / 开发者环境统一后端（libqurohost.so）
# 由 Kotlin 侧 QuroHostBridge 经 ProcessBuilder 直接执行（与 libproot.so 同机制）；
# Linux 模式下经 proot 跑进沙箱，子 shell 为 Ubuntu /bin/sh；设备模式回退 /system/bin/sh。
# 注意：作为可执行 .so 直接运行，须保留 main() 入口，且为 PIE（Android SHARED 默认 PIE）。
# ═══════════════════════════════════════════════════════════════
set(QUROHOST_DIR ${CMAKE_SOURCE_DIR}/qurohost)

add_library(qurohost SHARED
    ${QUROHOST_DIR}/qurohost.c
)

target_compile_options(qurohost PRIVATE
    -std=c11
    -Wall
    -Wextra
    -O2
    -fPIE
)

target_link_options(qurohost PRIVATE
    -pie
    -Wl,--gc-sections
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(qurohost ${log-lib})

# ═══════════════════════════════════════════════════════════════
# acihost — ACI 原生能力层（libacihost.so）
# 进程内 JNI 库：把 ACI 的「列能力 / 调任意能力」两个入口暴露成 C API，
# 让原生代码无需回到 Kotlin 层即可使用 ACI 的**全部**能力
# （ACI 本身是通用 call(capability, params)，这两个入口即覆盖全部）。
# 经 JNI 回调 com.ai.assistance.quro.core.aidlaci.AciNativeBridge。
#
# ⚠ 与 qurohost 的区别：qurohost 是经 ProcessBuilder 启动的独立原生进程、
#   不在 JVM 内，无法用本库；它改走既有的 @qurohost-ctl 控制通道，
#   由 Kotlin 侧（QuroHostBridge）桥接 ACI。
# ═══════════════════════════════════════════════════════════════
set(ACI_DIR ${CMAKE_SOURCE_DIR}/aci)

add_library(acihost SHARED
    ${ACI_DIR}/aci_native.c
)

target_include_directories(acihost PRIVATE ${ACI_DIR})

target_compile_options(acihost PRIVATE
    -std=c11
    -Wall
    -Wextra
    -Wno-unused-parameter
    -O2
    -fPIC
)

# 16KB 页大小合规
target_link_options(acihost PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(acihost ${log-lib})

# ═══════════════════════════════════════════════════════════════
# fusiond — ULFA 融合层守护进程（libfusiond.so）
# 与 qurohost 同机制：SHARED 库但保留 main() 入口、PIE，经 ProcessBuilder
# 由 Kotlin 侧（FusionTerminal / QuroLinuxEnv）直接执行；
# 内部用 openpty 建真 PTY，再以绝对路径 execv proot 进入 Ubuntu rootfs。
# proot 后端解析优先级见 fusion_layer.c 的 resolve_proot()：
#   $ULFA_PROOT → $ULFA_HOME/rootfs/usr/local/bin/proot →
#   $ULFA_HOME/rootfs/usr/bin/proot → $ULFA_HOME/bin/proot → PATH
# 注：bionic 的 openpty 在 libc 内（见 fusion_layer.c 头部注释），不链 -lutil。
# ═══════════════════════════════════════════════════════════════
set(ULFA_DIR ${CMAKE_SOURCE_DIR}/ulfa)

add_library(fusiond SHARED
    ${ULFA_DIR}/fusion_layer.c
)

target_compile_options(fusiond PRIVATE
    -std=c11
    -Wall
    -Wextra
    -Wno-unused-parameter
    -O2
    -fPIE
    -D_GNU_SOURCE
)

target_link_options(fusiond PRIVATE
    -pie
    -Wl,--gc-sections
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(fusiond ${log-lib})
