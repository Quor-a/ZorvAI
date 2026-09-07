# Java 项目模板

入口 `Main.java`。

## 说明

- 端侧沙箱无 JDK，**编译运行**走 ACI 构建台（`aci_call`，云端编译）；
- Android 原生 App 工程请用 `android` 模板；
- 建议：逻辑先在 `Main.java` 写清楚 → 让 AI 走 ACI 编译验证。

## 结构

```
my-java-app/
├── Main.java
└── README.md
```
