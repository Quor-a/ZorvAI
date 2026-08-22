#!/bin/sh
# CMS v2 built-in bootstrap — one-time full dev environment for proot/Alpine aarch64.
# Idempotent: apk add --no-cache skips already-installed packages; safe to re-run.
# Marker .bootstrap.done is written next to this script; the host (Android) detects
# it under <homePath>/cms/_bootstrap/ to skip re-running on subsequent deploys.
set -e

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ═══ Phase 1: 更新索引 ═══
echo "[cms-bootstrap] 🔧 updating apk index..."
apk update || {
    echo "[cms-bootstrap] WARN: apk update failed, retrying..."
    sleep 2
    apk update || {
        echo "[cms-bootstrap] FAILED: cannot reach apk mirror"
        exit 1
    }
}

# ═══ Phase 2: 语言运行时 ═══
echo "[cms-bootstrap] 📦 installing language runtimes..."
apk add --no-cache python3 py3-pip nodejs npm || {
    echo "[cms-bootstrap] First attempt failed, retrying in 2 seconds..."
    sleep 2
    apk add --no-cache python3 py3-pip nodejs npm || {
        echo "[cms-bootstrap] FAILED: language runtimes install failed"
        exit 1
    }
}

# ═══ Phase 3: 编译工具链 ═══
echo "[cms-bootstrap] 🔨 installing build toolchain..."
apk add --no-cache gcc g++ make cmake linux-headers || true

# ═══ Phase 4: 开发工具 ═══
echo "[cms-bootstrap] 🛠️ installing dev tools..."
apk add --no-cache git vim nano bash || true

# ═══ Phase 5: 网络与压缩工具 ═══
echo "[cms-bootstrap] 🌐 installing network & utility tools..."
apk add --no-cache curl wget jq zip unzip openssh-client || true

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
