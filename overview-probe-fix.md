# 修复 runProot() probe() 前置检查

## 问题
用户报告"所有环境已经安装但是AI说没有"。根因是 `QuroLinuxEnv.runProot()` 中的 `probe()` 前置检查在宿主侧解析 rootfs 内符号链接时可能误判，导致即使 proot 环境完全可用，所有命令也被阻断返回 "Linux 环境不可用"。

## 修复内容
- `QuroLinuxEnv.kt`：移除 `runProot()` 中的 probe() 前置检查，直接执行命令
- 已修复的调用链：
  - `QuroLinuxEnv.run()` → `runProot()`（不再被阻断）
  - `QuroLinuxEnv.runWithLog()` → `runProotWithLog()`（之前已修复）
  - `CmsEnvProvisioner.isReady()`（之前已修复）
  - `CmsEnvProvisioner.provision()`（之前已修复）
  - `CmsTerminalDeployer.bootstrap()` 中的 probe 仍保留（用于自动拉起安装，非前置阻断）

## CMS v2 开发者环境适配状态
- ✅ bootstrap.sh 已完全适配 Ubuntu 24.04（apt-get 替代 apk）
- ✅ 所有环境安装脚本已适配 Ubuntu 24.04（PYTHON/NODE/JAVA/RUST/GO/SSH/MCP 等）
- ✅ 安装路径正确：`/root/cms/<moduleId>`（proot 内）对应 `<homePath>/cms/<moduleId>`（宿主侧）
- ✅ bootstrap 路径：`/root/cms/_bootstrap/`（proot 内）对应 `<homePath>/cms/_bootstrap/`（宿主侧）
- ✅ apt 源配置：HTTP 清华镜像，避免 SSL 证书问题
- ✅ 每个环境安装脚本开头添加 `dpkg --configure -a` 修复数据库

## 构建
- APK：`C:\Users\admin\Desktop\ZorvAI-QuroAI-release-20260826-v16.apk`（346MB）
- 构建时间：26s
