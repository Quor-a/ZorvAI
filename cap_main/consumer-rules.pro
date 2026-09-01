# cap_main consumer proguard rules
# 保留能力 Handler / 注册表入口，避免 release 混淆后 CapabilityRegistry 注册失效
-keep class com.ai.assistance.quro.capmain.** { *; }
