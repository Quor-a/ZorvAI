cmake_minimum_required(VERSION 3.22.1)
project(quro_uinput_bridge)

# 原生 Uinput 注入器（L3 事件面）：注册虚拟多点触摸屏并写出内核 input_event。
add_library(uinput_bridge SHARED uinput_bridge.c)

# 链接 Android log 库（__android_log_print）
find_library(log-lib log)
target_link_libraries(uinput_bridge ${log-lib})
