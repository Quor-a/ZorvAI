# Proguard rules for :terminal-core
# 终端核心库（proot + Ubuntu + ANSI 终端模拟 + canvas 渲染）。
# AIDL 接口与 JNI（pty）符号需保留。
-keep class com.ai.assistance.quro.terminal.** { *; }
-keep interface com.ai.assistance.quro.terminal.** { *; }
