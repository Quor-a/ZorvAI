# Quro AI - 保留规则（debug 默认不混淆，此处为占位）

# 流体云（OPPO Fluid Cloud）：保留流体云相关类，避免混淆/优化误删
-keep class com.ai.assistance.quro.core.fluidcloud.** { *; }
-keep class com.ai.assistance.quro.core.tools.QuroFluidCloudTool { *; }

# 保留 JSONObject 等 JSON 相关类（流体云依赖）
-keep class org.json.** { *; }
-dontwarn org.json.**
