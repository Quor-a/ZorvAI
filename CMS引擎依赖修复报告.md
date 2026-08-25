# CMS 引擎依赖修复报告

> **修复时间**：2026-08-26 00:40 ~ 00:53  
> **环境**：Zorv AI · proot + Ubuntu 24.04 ARM64  
> **触发原因**：调用 `cms_call(run_node)` 时报错 `Cannot load externalized builtin: cjs-module-lexer`

---

## 🔍 修了什么

| 问题 | 根因 | 修法 |
|------|------|------|
| `libcurl.so.4` 缺失 | 共享库未安装 | 手动 `download` + `dpkg-deb -x` 安装 20+ 个依赖包 |
| `libz.so.1` 缺失 | 压缩库缺失 | 安装 `zlib1g` |
| `libssl.so.3` 缺失 | SSL 库缺失 | 安装 `libssl3t64` |
| `libsqlite3.so.0` 缺失 | 数据库库缺失 | 安装 `libsqlite3-0` |
| Node.js 18 报错 externalized builtins | Ubuntu patch 与 proot 不兼容 | **放弃系统包，换 Node.js 20 官方独立二进制** |
| curl 报 SSL 证书错误 | CA 证书文件缺失 | 手动 `cat` 合并证书到 `/etc/ssl/certs/` |
| tar 报 `xz: Cannot exec` | xz 解压工具缺失 | 安装 `xz-utils` |

---

## 🔧 核心修法（3 个关键决策）

### 1. 共享库：手动安装而非 apt-get
apt-get install 在 proot 下不可靠 → 用 `apt-get download` + `dpkg-deb -x` 手动解压到根目录

```bash
# 示例：安装 libcurl 及其依赖
mkdir -p /tmp/debs && cd /tmp/debs
apt-get download libcurl4 libcurl3t64 libnghttp2-14 libssh-4 libssh-gcrypt-4
for f in *.deb; do dpkg-deb -x "$f" /; done
```

### 2. Node.js：放弃系统包，换官方独立二进制
Ubuntu 18 的 externalized builtins 机制是无限依赖链，修不完 → 直接用 **Node.js v20.19.0 官方独立二进制**，从 npmmirror 下载

```bash
# 下载 Node.js 20 官方二进制
curl -fsSL "https://npmmirror.com/mirrors/node/v20.19.0/node-v20.19.0-linux-arm64.tar.xz" -o node.tar.xz
tar -xf node.tar.xz -C /usr/local --strip-components=1
```

### 3. CA 证书：手动合并
`update-ca-certificates` 在 proot 下 mktemp 失败 → 手动 `cat` 合并

```bash
# 手动合并 CA 证书
cat /usr/share/ca-certificates/mozilla/*.crt > /etc/ssl/certs/ca-certificates.crt
```

---

## 🎯 修后状态

| 组件 | 版本 | 状态 |
|------|------|------|
| Python 3 | 3.12.3 | ✅ |
| Node.js | v20.19.0 LTS | ✅ |
| npm | 10.8.2 | ✅ |
| curl | 8.5.0 | ✅ |
| 共享库 | libcurl/libz/libssl/libsqlite3 | ✅ 全部链接正常 |

---

## 💡 关键经验

> **Python 是 proot 环境下最好的自救工具** — curl 挂了用 python 下载，node 挂了用 python 装。只要 Python 能跑，就能修复一切。

---

## 📋 完整安装清单

### 共享库依赖（20+ 个 .deb 包）
```bash
# 必需的共享库
libcurl4 libcurl3t64
libnghttp2-14
libssh-4 libssh-gcrypt-4
libzlib1g
libssl3t64
libsqlite3-0
libgcc-s1
libc6
libstdc++6
libgssapi-krb5-2
libldap-2.5-0
libpsl5
librtmp1
libssh-gcrypt-4
libunistring5
```

### 开发工具链
```bash
gcc g++ make cmake
build-essential
linux-headers-generic
```

### Python 环境
```bash
python3 python3-pip python3-venv
python3-dev
```

### Node.js 环境（推荐官方二进制）
```bash
# 不要用 apt-get install nodejs
# 直接下载 Node.js 20 官方独立二进制
```

---

## ⚠️ 避坑指南

1. **不要在 proot 下用 apt-get install 安装关键依赖** — 可能部分安装导致状态不一致
2. **优先使用 apt-get download + dpkg-deb -x** — 手动控制安装过程
3. **Node.js 版本很重要** — Ubuntu 24.04 的 nodejs 包可能有问题，用官方二进制
4. **CA 证书需要手动处理** — update-ca-certificates 在 proot 下不可靠
5. **先装 Python** — Python 是最可靠的工具，可以用来修复其他组件