# CMS 引擎 bootstrap.sh 修复概述

## 修复时间
2026-08-26 01:05 ~ 01:08

## 问题背景
用户提供了详细的 CMS 引擎依赖修复报告，指出 bootstrap.sh 存在以下问题：
1. Node.js 18 的 externalized builtins 机制与 proot 不兼容
2. 关键共享库缺失（libcurl/libz/libssl/libsqlite3）
3. CA 证书文件缺失导致 curl SSL 错误
4. xz 解压工具缺失

## 修复内容

### 1. 共享库手动安装（Phase 2.5）
- 添加 16 个关键共享库的手动安装
- 使用 `apt-get download` + `dpkg-deb -x` 避免 proot 下 apt 不可靠
- 库列表：libcurl4, libnghttp2-14, libssh-4, zlib1g, libssl3t64, libsqlite3-0 等

### 2. Node.js 官方二进制（Phase 3）
- 放弃 `apt-get install nodejs`（可能安装 Node.js 18）
- 改用 Node.js v20.19.0 官方独立二进制
- 从 npmmirror 下载，避免国际网络问题

### 3. CA 证书手动合并（Phase 6.5）
- 手动 `cat /usr/share/ca-certificates/mozilla/*.crt` 合并证书
- 避免 `update-ca-certificates` 在 proot 下 mktemp 失败

### 4. xz-utils 安装（Phase 6）
- 添加 xz-utils 安装，解决 `tar 报 xz: Cannot exec` 问题

## 文件变更
- `app/src/main/assets/cms/bootstrap.sh` — 完全重写，整合用户修复方案
- `CMS引擎依赖修复报告.md` — 保存用户提供的完整修复报告

## 构建结果
- ✅ Release APK 构建成功（13s）
- 📦 APK：`C:\Users\admin\Desktop\ZorvAI-QuroAI-release-20260826-v17.apk`（346MB）

## 关键经验
> **Python 是 proot 环境下最好的自救工具** — curl 挂了用 python 下载，node 挂了用 python 装。只要 Python 能跑，就能修复一切。