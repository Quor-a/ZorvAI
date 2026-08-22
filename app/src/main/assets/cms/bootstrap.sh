#!/bin/sh
# CMS v2 built-in bootstrap — one-time full dev environment for proot/Alpine aarch64.
# Idempotent: apk add --no-cache skips already-installed packages; safe to re-run.
# Marker .bootstrap.done is written next to this script; the host (Android) detects
# it under <homePath>/cms/_bootstrap/ to skip re-running on subsequent deploys.
set -e

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ═══ Phase 0: 确保 DNS 可用 ═══
echo "[cms-bootstrap] 🔧 checking DNS..."

# 先检查/etc目录是否存在
if [ ! -d /etc ]; then
    echo "[cms-bootstrap] WARN: /etc directory does not exist, creating..."
    mkdir -p /etc || { echo "[cms-bootstrap] ERROR: failed to create /etc directory"; exit 1; }
fi

# 检查resolv.conf是否存在且包含nameserver
if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
    echo "[cms-bootstrap] DNS not configured, writing resolv.conf..."
    # 尝试多种方式写入DNS配置
    if ! cat > /etc/resolv.conf << 'DNS'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
nameserver 1.1.1.1
nameserver 9.9.9.9
DNS
    then
        # 如果cat失败，尝试echo方式
        echo "[cms-bootstrap] WARN: cat failed, trying echo..."
        echo "nameserver 8.8.8.8" > /etc/resolv.conf
        echo "nameserver 8.8.4.4" >> /etc/resolv.conf
        echo "nameserver 114.114.114.114" >> /etc/resolv.conf
        echo "nameserver 223.5.5.5" >> /etc/resolv.conf
        echo "nameserver 1.1.1.1" >> /etc/resolv.conf
        echo "nameserver 9.9.9.9" >> /etc/resolv.conf
    fi
    
    # 验证写入是否成功
    if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
        echo "[cms-bootstrap] WARN: failed to write DNS configuration, continuing anyway..."
        # 不退出，继续执行后续步骤
    else
        echo "[cms-bootstrap] DNS configured (fallback: 8.8.8.8, 114.114.114.114)"
    fi
else
    echo "[cms-bootstrap] DNS already configured"
fi

# ═══ Phase 0.5: 确保镜像源可用（不覆盖已有配置，除非为空） ═══
echo "[cms-bootstrap] 🔧 checking apk mirrors..."
if [ ! -s /etc/apk/repositories ] || ! grep -q "alpine" /etc/apk/repositories 2>/dev/null; then
    # 配置文件不存在或为空，写入默认镜像（清华 + 阿里云 + 官方）
    mkdir -p /etc/apk
    cat > /etc/apk/repositories << 'MIRRORS'
https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/main
https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/community
https://mirrors.aliyun.com/alpine/v3.20/main
https://mirrors.aliyun.com/alpine/v3.20/community
https://dl-cdn.alpinelinux.org/alpine/v3.20/main
https://dl-cdn.alpinelinux.org/alpine/v3.20/community
MIRRORS
    echo "[cms-bootstrap] mirrors configured: tsinghua + aliyun + official"
else
    echo "[cms-bootstrap] mirrors already configured (keeping existing)"
fi

# ═══ Phase 1: 更新索引（带重试） ═══
echo "[cms-bootstrap] 📦 updating apk index..."
update_apk() {
    # 先试当前配置
    if apk update --no-cache 2>&1; then return 0; fi
    echo "[cms-bootstrap] WARN: apk update failed, trying fallback mirrors..."
    # 逐个尝试其他镜像
    for mirror in \
        "https://mirrors.tuna.tsinghua.edu.cn/alpine" \
        "https://mirrors.aliyun.com/alpine" \
        "https://dl-cdn.alpinelinux.org/alpine"; do
        echo "https://${mirror#https://}/v3.20/main" > /etc/apk/repositories
        echo "https://${mirror#https://}/v3.20/community" >> /etc/apk/repositories
        sleep 1
        if apk update --no-cache 2>&1; then return 0; fi
    done
    return 1
}
update_apk || {
    echo "[cms-bootstrap] ❌ FAILED: apk update failed on all mirrors"
    echo "[cms-bootstrap] Available repositories:"
    cat /etc/apk/repositories 2>/dev/null || echo "(none)"
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
