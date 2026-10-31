cmake_minimum_required(VERSION 3.18.1)
project(vtlib C)

# ═══════════════════════════════════════════════════════════════
# Termux PTY 终端库（libtermux.so）
# 上游来源：termux-app terminal-emulator/src/main/jni/termux.c（Apache-2.0）
# 已去品牌化：包名 com.termux.terminal.JNI → com.ai.assistance.quro.vt.JNI
# ═══════════════════════════════════════════════════════════════
set(TERMUX_JNI_DIR ${CMAKE_SOURCE_DIR}/termux)

add_library(termux SHARED
    ${TERMUX_JNI_DIR}/termux.c
)

target_include_directories(termux PRIVATE
    ${TERMUX_JNI_DIR}
)

target_compile_options(termux PRIVATE
    -std=c11
    -Wall
    -Wextra
    -Werror
    -Os
    -fno-stack-protector
)

target_link_options(termux PRIVATE
    -Wl,--gc-sections
    "-Wl,-z,max-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(termux ${log-lib})
