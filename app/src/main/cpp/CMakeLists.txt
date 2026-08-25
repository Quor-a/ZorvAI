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
target_link_libraries(quroplugin ${log-lib})

# ═══════════════════════════════════════════════════════════════
# Termux PTY 终端库（libtermux-terminal.so）
# 基于 Termux 的 JNI 实现，提供伪终端（PTY）支持
# ═══════════════════════════════════════════════════════════════
set(TERMUX_JNI_DIR ${CMAKE_SOURCE_DIR}/../jni/termux)

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
)

target_link_libraries(termux-terminal ${log-lib})
