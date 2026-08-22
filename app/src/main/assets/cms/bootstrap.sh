#!/bin/sh
# CMS v2 built-in bootstrap — one-time full dev environment for proot/Alpine aarch64.
# Idempotent: apk add --no-cache skips already-installed packages; safe to re-run.
# Marker .bootstrap.done is written next to this script; the host (Android) detects
# it under <homePath>/cms/_bootstrap/ to skip re-running on subsequent deploys.
set -e

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ═══ Phase 0: 配置 DNS（proot 环境可能缺少 DNS） ═══
echo "[cms-bootstrap] 🔧 configuring DNS..."
if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
    mkdir -p /etc
    cat > /etc/resolv.conf << 'DNS'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
DNS
    echo "[cms-bootstrap] DNS configured (8.8.8.8, 114.114.114.114, 223.5.5.5)"
fi

# ═══ Phase 0.5: 配置镜像源（使用国内镜像 + 官方源） ═══
echo "[cms-bootstrap] 🔧 configuring apk mirrors..."
MIRROR_DIR="/etc/apk/repositories.d"
mkdir -p "$MIRROR_DIR"
# 使用阿里云镜像（国内快速）+ 官方源作为备用
cat > /etc/apk/repositories << 'MIRRORS'
https://mirrors.aliyun.com/alpine/v3.20/main
https://mirrors.aliyun.com/alpine/v3.20/community
https://dl-cdn.alpinelinux.org/alpine/v3.20/main
https://dl-cdn.alpinelinux.org/alpine/v3.20/community
MIRRORS
echo "[cms-bootstrap] mirrors configured: aliyun + official"

# ═══ Phase 1: 更新索引（带重试） ═══
echo "[cms-bootstrap] 📦 updating apk index..."
update_apk() {
    apk update --no-cache 2>&1 && return 0
    echo "[cms-bootstrap] WARN: apk update failed, trying alternative mirrors..."
    # 尝试只用官方源
    echo "https://dl-cdn.alpinelinux.org/alpine/v3.20/main" > /etc/apk/repositories
    echo "https://dl-cdn.alpinelinux.org/alpine/v3.20/community" >> /etc/apk/repositories
    sleep 2
    apk update --no-cache 2>&1 && return 0
    # 最后尝试只用主源
    echo "https://mirrors.aliyun.com/alpine/v3.20/main" > /etc/apk/repositories
    sleep 2
    apk update --no-cache 2>&1 && return 0
    return 1
}
update_apk || {
    echo "[cms-bootstrap] ❌ FAILED: apk update failed on all mirrors"
    echo "[cms-bootstrap] Check network connectivity in proot environment"
    exit 1
}

# ═══ Phase 2: 语言运行时 ═══
echo "[cms-bootstrap] 📦 installing language runtimes..."
install_with_retry() {
    local pkgs="$1"
    local max_retries=3
    local retry=0
    while [ $retry -lt $max_retries ]; do
        apk add --no-cache $pkgs 2>&1 && return 0
        retry=$((retry + 1))
        echo "[cms-bootstrap] WARN: attempt $retry/$max_retries failed, retrying in 3s..."
        sleep 3
    done
    return 1
}
install_with_retry "python3 py3-pip nodejs npm" || {
    echo "[cms-bootstrap] ❌ FAILED: language runtimes install failed after $max_retries retries"
    exit 1
}

# ═══ Phase 3: 编译工具链 ═══
echo "[cms-bootstrap] 🔨 installing build toolchain..."
install_with_retry "gcc g++ make cmake linux-headers" || true

# ═══ Phase 4: 开发工具 ═══
echo "[cms-bootstrap] 🛠️ installing dev tools..."
install_with_retry "git vim nano bash" || true

# ═══ Phase 5: 网络与压缩工具 ═══
echo "[cms-bootstrap] 🌐 installing network & utility tools..."
install_with_retry "curl wget jq zip unzip openssh-client" || true

# ═══ Phase 6: Python venv ═══
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[cms-bootstrap] creating /root/cms-venv..."
    python3 -m venv /root/cms-venv || echo "[cms-bootstrap] WARN: venv creation failed (system python still usable)"
fi

# ═══ 验证 ═══
echo "[cms-bootstrap] ✅ dev environment ready:"
echo "  python3  = $(python3 --version 2>&1)"
echo "  node     = $(node --version 2>&1)"
echo "  npm      = $(npm --version 2>&1)"
echo "  gcc      = $(gcc --version 2>&1 | head -1)"
echo "  cmake    = $(cmake --version 2>&1 | head -1)"
echo "  git      = $(git --version 2>&1)"
echo "  curl     = $(curl --version 2>&1 | head -1)"

touch "$BOOT_DIR/.bootstrap.done"
echo "[cms-bootstrap] marker written: $BOOT_DIR/.bootstrap.done"
