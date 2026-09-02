#!/bin/sh
# CMS v2 bootstrap — Ubuntu 24.04 (Noble) ARM64 专用
# 幂等：安全重复执行。成功写 .bootstrap.done 标记。

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ═══════════════════════════════════════════════════════════
# DNS 配置
# ═══════════════════════════════════════════════════════════
if [ ! -f /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
    mkdir -p /etc
    cat > /etc/resolv.conf 2>/dev/null << 'DNS' || printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 223.5.5.5\n' > /etc/resolv.conf
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
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
    # 轮次F：放开未签名仓库（proot 下手动 curl 拉索引，无 Release 签名）→ apt 才肯用这批索引
    printf 'Acquire::Check-Valid-Until "false";\nAPT::Get::AllowUnauthenticated "true";\nAcquire::AllowInsecureRepositories "true";\n' > /etc/apt/apt.conf.d/99no-check-gpg
    # 轮次F：切清华 TUNA（HTTP，避免 proot 下 HTTPS 卡死）；ports.ubuntu.com pool 整体 404 已弃用
    printf 'deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main restricted universe multiverse\ndeb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main restricted universe multiverse\ndeb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main restricted universe multiverse\n' > /etc/apt/sources.list
    echo "[cms-bootstrap] apt sources configured (noble, tuna http)"
fi

# ═══════════════════════════════════════════════════════════
# CA 证书合并（proot 下 update-ca-certificates 不可靠）
# ═══════════════════════════════════════════════════════════
if [ -d /usr/share/ca-certificates/mozilla ]; then
    cat /usr/share/ca-certificates/mozilla/*.crt > /etc/ssl/certs/ca-certificates.crt 2>/dev/null || true
fi

# ═══════════════════════════════════════════════════════════
# 轮次F：/etc/hosts 静态映射（proot 下 DNS 53 端口常不可达，先写死镜像域名 IP）
# TUNA（清华）+ aliyun（阿里云）anycast IP；幂等：先删自身标记行再追加，绝不覆盖其它条目。
# 另补 127.0.0.1 localhost —— proot 默认无 localhost 映射 → `ssh localhost` 报 Could not resolve hostname。
# ═══════════════════════════════════════════════════════════
sed -i '/# quro-bootstrap-dns$/d' /etc/hosts 2>/dev/null || true
cat >> /etc/hosts << 'HOSTS'
101.6.15.130 mirrors.tuna.tsinghua.edu.cn # quro-bootstrap-dns
163.181.201.182 mirrors.aliyun.com # quro-bootstrap-dns
HOSTS
grep -q '^127.0.0.1[[:space:]].*localhost' /etc/hosts 2>/dev/null || echo "127.0.0.1 localhost" >> /etc/hosts

# ═══════════════════════════════════════════════════════════
# 强制修复 dpkg 状态 + 中和服务管理器
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] fixing dpkg..."
for lk in /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock; do
    rm -f "$lk" 2>/dev/null
done
# 轮次F：清掉残留的 partial 下载碎片（apt-get update 超时强杀常留半截）
rm -rf /var/lib/apt/lists/partial/* 2>/dev/null || true
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
# 轮次F · apt 索引（绕过 apt-get update 超时）
# proot 下 apt-get update 拉清华 universe 6万+ 包超 30s 终端超时，且 ports.ubuntu.com pool 整体 404。
# 改用 curl 手动拉清华 TUNA(HTTP) 12 组件索引直写 /var/lib/apt/lists/，文件名须严格合规，apt 才能识别。
# 仅当 TUNA 拉齐后才清旧源（ports.ubuntu.com 已 404 必删；aliyun 仅在 TUNA 成功时替换），
# 避免误删基线已装好的 aliyun 索引导致无索引可用。curl 缺失或 TUNA 全失败时回退 apt-get update（硬 25s 超时）。
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] apt index (manual curl, bypass apt-get update timeout)..."
quro_manual_apt_index() {
    local BASE="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists"
    local APTL="/var/lib/apt/lists"
    mkdir -p "$APTL" "$APTL/partial"
    rm -f "$APTL"/partial/* 2>/dev/null || true
    local ok=0 total=0
    if command -v curl >/dev/null 2>&1; then
        for dist in noble noble-updates noble-security; do
            for comp in main universe multiverse restricted; do
                total=$((total+1))
                local f="mirrors.tuna.tsinghua.edu.cn_ubuntu-ports_dists_${dist}_${comp}_binary-arm64_Packages"
                if curl -s --max-time 40 -o "$APTL/$f.gz" "$BASE/$dist/$comp/binary-arm64/Packages.gz" \
                   && gzip -dc "$APTL/$f.gz" > "$APTL/$f" 2>/dev/null && [ -s "$APTL/$f" ]; then
                    rm -f "$APTL/$f.gz"; ok=$((ok+1)); echo "[apt] index ok: $f"
                else
                    echo "[apt] WARN: failed fetch $dist/$comp"; rm -f "$APTL/$f.gz" "$APTL/$f" 2>/dev/null || true
                fi
            done
        done
        echo "[apt] manual index: $ok/$total fetched"
        if [ "$ok" -ge 1 ]; then
            rm -f "$APTL"/ports.ubuntu.com_* 2>/dev/null || true
            rm -f "$APTL"/mirrors.aliyun.com_* 2>/dev/null || true
        fi
    else
        echo "[apt] curl not available, will rely on apt-get update"
    fi
    if [ "$ok" -lt 1 ]; then
        echo "[apt] manual index insufficient, trying apt-get update (hard 25s timeout)..."
        if command -v timeout >/dev/null 2>&1; then
            timeout 25 apt-get update 2>&1 | tail -5 || true
        else
            apt-get update 2>&1 | tail -5 || true
        fi
    fi
}
quro_manual_apt_index

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

# Phase 3.5 (Bug3 修复)：网络诊断命令（ping/nslookup/dig/host/netstat/ifconfig/ip/ss）。
# proot 默认不含，按用户清单补齐，best-effort 非致命。
# 包映射：iputils-ping→ping, dnsutils→nslookup/dig/host, net-tools→netstat/ifconfig, iproute2→ip/ss
robust_install "iputils-ping" "ping" || true
robust_install "dnsutils" "nslookup" || true
robust_install "net-tools" "netstat" || true
robust_install "iproute2" "ip" || true

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

# ═══════════════════════════════════════════════════════════
# Phase 7.x (轮次E · Rust 修复)：rustup 工具链软链 + 环境变量持久化（幂等兜底）
# 兼容两种布局：/var/rustup+/var/cargo（方案标准）与默认 /root/.rustup+/root/.cargo（dev-env/终端 UI 安装）。
# 仅当 rustup 工具链真实存在时清理 /usr/bin 孤儿二进制并软链；否则保留 apt 装的 rustc/cargo，绝不破坏可用链路。
# ═══════════════════════════════════════════════════════════
RUST_HOME=""
CARGO_HOME_DIR=""
if [ -d /var/rustup/toolchains ]; then RUST_HOME=/var/rustup; CARGO_HOME_DIR=/var/cargo; fi
if [ -d /root/.rustup/toolchains ]; then RUST_HOME=/root/.rustup; CARGO_HOME_DIR=/root/.cargo; fi
RUST_TC_DIR=""
if [ -n "$RUST_HOME" ]; then
    for d in "$RUST_HOME"/toolchains/*-unknown-linux-gnu "$RUST_HOME"/toolchains/stable-*; do
        if [ -d "$d/bin" ] && [ -x "$d/bin/rustc" ]; then RUST_TC_DIR="$d/bin"; break; fi
    done
fi
if [ -n "$RUST_TC_DIR" ]; then
    rm -f /usr/bin/rustc /usr/bin/cargo /usr/bin/rustfmt /usr/bin/clippy-driver /usr/bin/cargo-clippy 2>/dev/null || true
    for b in rustc cargo rustfmt clippy-driver cargo-clippy rustdoc; do
        ln -sf "$RUST_TC_DIR/$b" /usr/bin/$b 2>/dev/null || true
    done
    echo "[rust] linked toolchain from $RUST_TC_DIR"
fi
if [ -n "$RUST_HOME" ]; then
    grep -q 'RUSTUP_HOME' /root/.bashrc 2>/dev/null || cat >> /root/.bashrc << RB
export RUSTUP_HOME=$RUST_HOME
export CARGO_HOME=$CARGO_HOME_DIR
export PATH="$CARGO_HOME_DIR/bin:/usr/local/go/bin:$PATH"
RB
fi
echo "[cms-bootstrap] done at $(date)"
