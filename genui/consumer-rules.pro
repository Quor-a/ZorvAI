# 生成式 UI 库消费端混淆规则
# Room 实体与 DAO 不能混淆
-keep class com.zorv.genui.store.** { *; }
-keep class com.zorv.genui.protocol.** { *; }
# WebMessageListener / postMessage 桥相关类保留
-keep class com.zorv.genui.host.** { *; }
# org.json 在 Android 内置，无需保留
