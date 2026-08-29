#!/bin/sh
# CMS v2 bootstrap — Ubuntu 24.04 (Noble) ARM64 专用
# 幂等：安全重复执行。成功写 .bootstrap.done 标记。

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ═══════════════════════════════════════════════════════════
# DNS 配置（修复版）
# ═══════════════════════════════════════════════════════════
if [ ! -f /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
    mkdir -p /etc
    cat > /etc/resolv.conf 2>/dev/null << 'DNS'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 223.5.5.5
nameserver 1.1.1.1
nameserver 9.9.9.9
DNS
    echo "[cms-bootstrap] DNS configured"
fi

# ═══════════════════════════════════════════════════════════
# apt sources（Ubuntu 24.04 Noble）
# ═══════════════════════════════════════════════════════════
if [ ! -s /etc/apt/sources.list ] || ! grep -q "noble" /etc/apt/sources.list 2>/dev/null; then
    mkdir -p /etc/apt/apt.conf.d
    printf 'Acquire::Check-Valid-Until "false";\nAPT::Get::AllowUnauthenticated "true";\n' > /etc/apt/apt.conf.d/99no-check-gpg
    printf 'deb http://mirrors.aliyun.com/ubuntu-ports/ noble main restricted universe multiverse\ndeb http://mirrors.aliyun.com/ubuntu-ports/ noble-updates main restricted universe multiverse\ndeb http://mirrors.aliyun.com/ubuntu-ports/ noble-security main restricted universe multiverse\n' > /etc/apt/sources.list
    echo "[cms-bootstrap] apt sources configured (noble, http)"
fi

# ═══════════════════════════════════════════════════════════
# CA 证书合并（proot 下 update-ca-certificates 不可靠）
# ═══════════════════════════════════════════════════════════
if [ -d /usr/share/ca-certificates/mozilla ]; then
    cat /usr/share/ca-certificates/mozilla/*.crt > /etc/ssl/certs/ca-certificates.crt 2>/dev/null || true
fi

# ═══════════════════════════════════════════════════════════
# 强制修复 dpkg 状态 + 中和服务管理器
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] fixing dpkg..."
for lk in /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock; do
    rm -f "$lk" 2>/dev/null
done
for proc in dpkg apt apt-get; do pkill -9 "$proc" 2>/dev/null || true; done
dpkg --configure -a 2>/dev/null || true
apt-get install -f -y 2>/dev/null || true

# 中和 proot 下会挂起的服务管理器
mkdir -p /usr/local/sbin
for b in start-stop-daemon invoke-rc.d update-rc.d service systemctl telinit initctl deb-systemd-helper deb-systemd-invoke; do
    printf '#!/bin/sh\nexit 0\n' > "/usr/local/sbin/$b" 2>/dev/null
    chmod +x "/usr/local/sbin/$b" 2>/dev/null || true
done
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d 2>/dev/null || true
chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true

# ═══════════════════════════════════════════════════════════
# apt update（带镜像回退）
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] apt update..."
if ! apt-get update 2>&1; then
    for m in aliyun tsinghua ports; do
        case "$m" in
            aliyun)   BASE="http://mirrors.aliyun.com/ubuntu-ports" ;;
            tsinghua) BASE="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports" ;;
            ports)    BASE="http://ports.ubuntu.com/ubuntu-ports" ;;
        esac
        printf "deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n" "$BASE" "$BASE" "$BASE" > /etc/apt/sources.list
        sleep 1
        if apt-get update 2>&1; then break; fi
    done
fi

# ═══════════════════════════════════════════════════════════
# 稳健安装函数：apt 优先，失败回退 apt-get download + dpkg-deb -x
# ═══════════════════════════════════════════════════════════
robust_install() {
    local pkgs="$1"
    local probe="$2"
    if [ -n "$probe" ] && command -v "$probe" >/dev/null 2>&1; then
        return 0
    fi
    echo "[cms-bootstrap] Installing $pkgs..."
    apt-get install -y --no-install-recommends $pkgs 2>&1 | tail -3
    if [ -n "$probe" ] && command -v "$probe" >/dev/null 2>&1; then
        echo "[cms-bootstrap]  installed $pkgs via apt"
        return 0
    fi
    echo "[cms-bootstrap]  apt failed for $pkgs, trying dpkg fallback..."
    local tmp=/tmp/cms_deb; mkdir -p "$tmp"
    ( cd "$tmp" && apt-get download $pkgs 2>/dev/null && for f in *.deb; do [ -e "$f" ] && dpkg-deb -x "$f" / 2>/dev/null; done; rm -f *.deb )
    apt-get install -f -y 2>/dev/null || true
    if [ -n "$probe" ] && command -v "$probe" >/dev/null 2>&1; then
        echo "[cms-bootstrap]  installed $pkgs via dpkg fallback"
        return 0
    elif [ -z "$probe" ]; then
        return 0
    else
        echo "[cms-bootstrap]  FAILED: $pkgs"
        return 1
    fi
}

# ═══════════════════════════════════════════════════════════
# Phase 1: 语言运行时
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] Phase 1: language runtimes..."
robust_install "python3 python3-pip python3-venv" "python3" || true
robust_install "nodejs npm" "node" || true

# Node.js 官方二进制回退（apt nodejs 可能不可用）
if ! command -v node >/dev/null 2>&1; then
    echo "[cms-bootstrap] apt nodejs unavailable, downloading Node.js 20 binary..."
    curl -fsSL "https://npmmirror.com/mirrors/node/v20.19.0/node-v20.19.0-linux-arm64.tar.xz" -o /tmp/node.tar.xz 2>/dev/null
    if [ -f /tmp/node.tar.xz ]; then
        tar -xf /tmp/node.tar.xz -C /usr/local --strip-components=1 2>/dev/null
        rm -f /tmp/node.tar.xz
    fi
fi

# ═══════════════════════════════════════════════════════════
# Phase 2: 构建工具链
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] Phase 2: build toolchain..."
robust_install "gcc g++ make cmake" "gcc" || true
robust_install "git" "git" || true

# ═══════════════════════════════════════════════════════════
# Phase 3: 开发工具
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] Phase 3: dev tools..."
robust_install "vim nano" "vim" || true
robust_install "curl wget" "curl" || true
robust_install "jq zip unzip" "jq" || true
robust_install "openssh-client" "ssh" || true
robust_install "tree file" "tree" || true
robust_install "less" "less" || true
robust_install "bc" "bc" || true

# ═══════════════════════════════════════════════════════════
# Phase 4: Python venv
# ═══════════════════════════════════════════════════════════
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[cms-bootstrap] creating /root/cms-venv..."
    python3 -m venv /root/cms-venv 2>&1 || echo "[cms-bootstrap] WARN: venv creation failed"
fi

# ═══════════════════════════════════════════════════════════
# 验证
# ═══════════════════════════════════════════════════════════
echo ""
echo "═══════════════════════════════════════════════════════"
echo "[cms-bootstrap] verification"
echo "═══════════════════════════════════════════════════════"
CORE_OK=1
for t in python3 node gcc make cmake git curl jq; do
    if command -v $t >/dev/null 2>&1; then
        echo "  ✅ $t = $(command -v $t)"
    else
        echo "  ❌ $t MISSING"
        CORE_OK=0
    fi
done

if [ "$CORE_OK" = "1" ]; then
    touch "$BOOT_DIR/.bootstrap.done"
    echo "[cms-bootstrap] all core tools present"
else
    echo "[cms-bootstrap] some tools missing, NOT writing .bootstrap.done"
fi
echo "[cms-bootstrap] done at $(date)"
