# 应用内 Linux 环境（proot + Alpine）Phase 2-4 全流程交付

**日期**：2026-07-20
**场景**：全流程交付（Phase 2 运行时解压配置 → Phase 3 工具注册 → Phase 4 编译出包）
**参与成员**：主理人沽思航（直接实现；本环境 `gstack-*` 子团队不可用，未派发）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（代码实现 + 编译出包成功，APK 已交付）
- 阻塞项数量：0（编译层）；1 项**真机验证**待 Android 设备完成（环境限制，非代码阻塞）
- 下一步：在 Android 设备上真机验证 proot 容器（ptrace 限制 / HTTPS 镜像 / apk 安装）
- 交付物：`QuroAI-debug-2026-07-20-v71.apk`（135,385,019 B）

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（出包完成）；🔶 真机验证 Pending |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（真机验证依赖设备）/ 🟢 多项 |
| 关键行动项 | 3 条（见下） |
| 建议负责人 | 主理人 / 真机测试由用户执行 |

---

## 1. 各成员核心结论

### 🧑‍💻 主理人（实现 + 编排）
- 核心判断：v70 已落 Phase 1（资产获取），本次按用户「全部开发出来」把 Phase 2-4 一次性实现并出包。
- 关键决策：
  - rootfs 解压复用项目已有的 `commons-compress:1.26.1` `TarArchiveInputStream`，正确处理 Alpine 的 **335 个符号链接**（经 `android.system.Os.symlink`），否则 `apk`/`sh` 全废。
  - **补 CA 证书缺口**：Alpine minirootfs 不含 `/etc/ssl/certs/ca-certificates.crt`，`apk` 走 HTTPS 会失败；下载 Mozilla `cacert.pem`(186KB) 打入 assets 并在 setup 拷入容器，同时注入 `SSL_CERT_FILE`/`SSL_CERT_DIR`。
  - 国内可达性：`resolv.conf` 用 119.29.29.29 / 223.5.5.5，`repositories` 用清华镜像 `v3.24/{main,community}`。
  - 工具 `linux_exec` / `linux_manage` 注册进 `buildQuroRegistry()` + `coreSpecs()` 白名单，AI 默认可见。

---

## 2. 综合审查发现（按严重度）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟡 | 验证 | 设备层 | proot 走 ptrace，需 Android 允许同 UID 内 ptrace 子进程；部分厂商 ROM/SELinux 可能限制，Windows 无法验证 | 在 Android 设备装 v71 跑 `linux_manage(action=status)` + `linux_exec("uname -a")` + `apk add` 验证 | 主理人 |
| 2 | 🟢 | 性能 | `QuroToolsLinux.kt` | 工具 `run()` 为同步阻塞，首次 `linux_exec` 触发一次性解压(~5-15s)，若在主线程调用可能短暂卡顿 | 后续改 `suspend` + `Dispatchers.IO` | 主理人 |
| 3 | 🟢 | 范围 | `QuroLinuxEnv.exec` | 仅绑 `/dev /proc /sys /tmp`，未绑 host `/system` 等；guest 用 musl 自包含，跑 apk/busybox/编译足够 | 若需调用 host 工具再补 `-b` 绑定 | 主理人 |

---

## ✅ 行动清单（具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | Android 设备装 v71，验证 proot 容器（status / uname / apk add htop） | 用户 / 主理人协助 | P0 | 设备到手即做 |
| 2 | 若首调卡顿，把 `LinuxExecTool/LinuxManageTool.run` 改为 `suspend` + `Dispatchers.IO` | 主理人 | P2 | 下个版本 |
| 3 | 如需访问 host 路径（如 `/system`），在 `exec` 增加 `-b` 绑定 | 主理人 | P3 | 按需 |

---

## ⚠️ 待完善 / 已知局限

- proot 非 100% 静态（带 libtalloc + libandroid-shmem 两个 .so），靠 `LD_LIBRARY_PATH` 自带解决；若坚持纯静态需 NDK 自编译 proot。
- 真机验证只能在 Android 设备（Windows 无法运行 aarch64 二进制）。
- `status` 调用不触发整包解压，仅 `ensureBinaries`（拷 proot+lib 并跑 `proot --version`）。
- 编译期已修两坑：`GzipInputStream` 大小写、`Kotlin 2.3.10` 不支持八进制 `0o` 字面量（改用 `0b` 二进制掩码）。

---

## 📚 成员产出索引

- 主理人（直接实现）原始产出：
  - `app/src/main/java/com/ai/assistance/quro/core/linux/QuroLinuxEnv.kt`（运行时解压 + proot 执行）
  - `app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsLinux.kt`（`linux_exec` / `linux_manage`）
  - 注册点：`QuroBuiltInTools.buildQuroRegistry()` + `QuroTool.coreSpecs()`
  - 资产：`app/src/main/assets/linux_env/ca-certificates.crt`（新增 CA 包）
  - 出包：`QuroAI-debug-2026-07-20-v71.apk`

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
> 注：本环境 `gstack-*` 子团队（产品官/安全卫士/质量门神/设计师/排障手）不可派发，故由主理人直接实现并汇编，未做独立成员评审。
