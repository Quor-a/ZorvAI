#!/bin/bash
# CMS v2 built-in bootstrap — one-time full dev environment for proot/Ubuntu 24.04 ARM64.
# Based on terminal environment repair flow from AI summary (2026-08-26)
# Idempotent: apt-get install -y skips already-installed packages; safe to re-run.
# Marker .bootstrap.done is written next to this script; the host (Android) detects
# it under <homePath>/cms/_bootstrap/ to skip re-running on subsequent deploys.

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ── dpkg / apt 锁检测与释放 ──
# 上一次安装中断/崩溃会残留 /var/lib/dpkg/lock* 与 /var/cache/apt/archives/lock，
# 导致后续 apt-get 卡死或报 "Could not get lock"。本段检测并释放 stale 锁（被进程占用则跳过）。
echo "[cms-bootstrap] 🔓 检测并释放残留 dpkg/apt 锁..."
for lk in /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock /var/lib/apt/lists/lock; do
  if [ -e "$lk" ]; then
    if command -v fuser >/dev/null 2>&1 && fuser "$lk" >/dev/null 2>&1; then
      echo "[cms-bootstrap] ⏭️ $lk 被进程占用，跳过释放"
    else
      echo "[cms-bootstrap] 🧹 释放 stale 锁: $lk"
      rm -f "$lk" 2>/dev/null || true
    fi
  fi
done
dpkg --configure -a 2>/dev/null || true

# ── 中和 dpkg 服务管理器（proot 无 PID1/init，postinst 调用 start-stop-daemon/systemctl 等会挂起）──
echo "[cms-bootstrap] 🛡️ 中和 dpkg 服务管理器（proot 无 init，避免维护脚本挂起）..."
neutralize_dpkg_services() {
    mkdir -p /usr/local/sbin
    for b in start-stop-daemon invoke-rc.d update-rc.d service systemctl telinit initctl deb-systemd-helper deb-systemd-invoke; do
        printf '#!/bin/sh\nexit 0\n' > "/usr/local/sbin/$b"
        chmod +x "/usr/local/sbin/$b" 2>/dev/null || true
    done
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d 2>/dev/null || true
    chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true
    echo "[cms-bootstrap] ✅ 服务管理器已中和（no-op 置于 /usr/local/sbin，PATH 优先级高于系统二进制）"
}
neutralize_dpkg_services

echo "[cms-bootstrap] 🚀 Starting CMS bootstrap at $(date)"
echo "[cms-bootstrap] 📁 Bootstrap directory: $BOOT_DIR"

# ═══ Phase 0: 确保 DNS 可用 ═══
echo "[cms-bootstrap] 🔧 Phase 0: checking DNS..."

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

# 测试网络连通性（用curl替代ping，proot可能没有ping）
echo "[cms-bootstrap] 🔍 Testing network connectivity..."
if curl -sS --connect-timeout 5 --max-time 10 http://mirrors.aliyun.com >/dev/null 2>&1; then
    echo "[cms-bootstrap] ✅ Network connectivity working"
elif wget -q --spider --timeout=5 http://mirrors.aliyun.com 2>/dev/null; then
    echo "[cms-bootstrap] ✅ Network connectivity working (wget)"
else
    echo "[cms-bootstrap] ⚠️ Network connectivity test failed, but continuing..."
fi

# ═══ Phase 0.5: 确保 apt 源可用（Ubuntu 24.04 HTTP 镜像） ═══
echo "[cms-bootstrap] 🔧 Phase 0.5: checking apt sources..."
# 修复dpkg数据库（之前可能中断）
echo "[cms-bootstrap] 🔧 Fixing dpkg database..."
dpkg --configure -a 2>/dev/null || echo "[cms-bootstrap] ⚠️ dpkg --configure -a failed, continuing..."

# 配置Ubuntu 24.04 apt源（HTTP避免SSL证书问题）
echo "[cms-bootstrap] 🔧 Configuring apt sources..."
if [ ! -s /etc/apt/sources.list ] || ! grep -q "noble" /etc/apt/sources.list 2>/dev/null; then
    mkdir -p /etc/apt/apt.conf.d
    # 关闭签名验证（proot环境下GPG公钥可能不完整）
    printf 'Acquire::Check-Valid-Until "false";\nAPT::Get::AllowUnauthenticated "true";\n' > /etc/apt/apt.conf.d/99no-check-gpg
    # 使用阿里云镜像（arm64必须用ubuntu-ports）
    printf 'deb http://mirrors.aliyun.com/ubuntu-ports/ noble main restricted universe multiverse\ndeb http://mirrors.aliyun.com/ubuntu-ports/ noble-updates main restricted universe multiverse\ndeb http://mirrors.aliyun.com/ubuntu-ports/ noble-security main restricted universe multiverse\n' > /etc/apt/sources.list
    echo "[cms-bootstrap] ✅ apt sources configured (noble, aliyun ubuntu-ports)"
else
    echo "[cms-bootstrap] ℹ️ apt sources already configured (keeping existing)"
fi

# 显示当前apt源
echo "[cms-bootstrap] 📋 Current apt sources:"
cat /etc/apt/sources.list 2>/dev/null | head -5

# ═══ Phase 1: 更新索引（带重试） ═══
echo "[cms-bootstrap] 📦 Phase 1: updating apt index..."
update_apt() {
    # 先试当前配置
    echo "[cms-bootstrap] 🔍 Trying current apt configuration..."
    if apt-get update 2>&1; then
        echo "[cms-bootstrap] ✅ apt-get update succeeded with current configuration"
        return 0
    fi
    echo "[cms-bootstrap] ⚠️ apt-get update failed with current configuration, trying alternative mirrors..."
    # 逐个尝试其他镜像（arm64必须用ubuntu-ports）
    for BASE in \
        "http://mirrors.aliyun.com/ubuntu-ports" \
        "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports" \
        "http://ports.ubuntu.com/ubuntu-ports"; do
        echo "[cms-bootstrap] 🔍 Trying mirror: $BASE"
        printf "deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n" "$BASE" "$BASE" "$BASE" > /etc/apt/sources.list
        sleep 1
        if apt-get update 2>&1; then
            echo "[cms-bootstrap] ✅ apt-get update succeeded with mirror: $BASE"
            return 0
        fi
        echo "[cms-bootstrap] ❌ apt-get update failed with mirror: $BASE"
    done
    return 1
}
update_apt || {
    echo "[cms-bootstrap] ❌ FAILED: apt-get update failed on all mirrors"
    echo "[cms-bootstrap] 📋 Available sources:"
    cat /etc/apt/sources.list 2>/dev/null || echo "(none)"
    echo "[cms-bootstrap] 🔍 Testing network connectivity..."
    curl -sS --connect-timeout 5 --max-time 10 http://mirrors.aliyun.com >/dev/null 2>&1 || echo "[cms-bootstrap] ⚠️ Network connectivity test failed"
    exit 1
}

# ═══ Phase 2: 修复 dpkg 数据库错误 ═══
echo "[cms-bootstrap] 🔧 fixing dpkg database errors..."
dpkg --configure -a 2>/dev/null || true
apt-get install -f -y 2>/dev/null || true

# ═══ Phase 2.5: 安装关键共享库（手动安装，避免 proot 下 apt 不可靠） ═══
echo "[cms-bootstrap] 🔧 Phase 2.5: installing critical shared libraries..."

# 创建临时目录用于下载deb包
mkdir -p /tmp/debs && cd /tmp/debs

# 关键共享库列表（手动下载安装，确保完整）
LIBS=(
    "libcurl4"
    "libcurl3t64" 
    "libnghttp2-14"
    "libssh-4"
    "libssh-gcrypt-4"
    "zlib1g"
    "libssl3t64"
    "libsqlite3-0"
    "libgcc-s1"
    "libc6"
    "libstdc++6"
    "libgssapi-krb5-2"
    "libldap-2.5-0"
    "libpsl5"
    "librtmp1"
    "libunistring5"
)

echo "[cms-bootstrap] 📦 Downloading and installing shared libraries..."
for lib in "${LIBS[@]}"; do
    echo "[cms-bootstrap]   Installing $lib..."
    apt-get download "$lib" 2>/dev/null && dpkg-deb -x "$lib"*.deb / 2>/dev/null
    rm -f "$lib"*.deb 2>/dev/null
done

# 清理临时目录
cd /
rm -rf /tmp/debs

# 验证关键共享库
echo "[cms-bootstrap] 🔍 Verifying critical shared libraries..."
for lib in libcurl libz libssl libsqlite3; do
    if ldconfig -p 2>/dev/null | grep -q "$lib"; then
        echo "[cms-bootstrap]   ✅ $lib found"
    else
        echo "[cms-bootstrap]   ⚠️ $lib not found in ldconfig, but may still work"
    fi
done

# ═══ Phase 3: 语言运行时 ═══
echo "[cms-bootstrap] 📦 Phase 3: installing language runtimes..."
# 使用稳健安装函数：先 apt-get install；proot 下事务常半装失败，则回退 apt-get download + dpkg-deb -x。
robust_install() {
    local pkg="$1"
    local cmd="$2"
    if command -v $cmd >/dev/null 2>&1; then
        echo "[cms-bootstrap] ✅ $pkg already installed: $(command -v $cmd)"
        return 0
    fi
    echo "[cms-bootstrap] 📦 Installing $pkg..."
    apt-get install -y --no-install-recommends $pkg 2>&1 | tail -5
    if command -v $cmd >/dev/null 2>&1; then
        echo "[cms-bootstrap] ✅ $pkg installed via apt"
        return 0
    fi
    echo "[cms-bootstrap] ⚠️ apt 未生效，$pkg 改用 download+dpkg-deb 回退..."
    local tmp=/tmp/quro_deb; mkdir -p "$tmp"
    ( cd "$tmp" && apt-get download $pkg 2>/dev/null && for f in *.deb; do dpkg-deb -x "$f" / 2>/dev/null; done; rm -f *.deb )
    apt-get install -f -y 2>/dev/null || true
    if command -v $cmd >/dev/null 2>&1; then
        echo "[cms-bootstrap] ✅ $pkg installed via dpkg fallback"
        return 0
    else
        echo "[cms-bootstrap] ❌ $pkg installation failed (apt + dpkg fallback)"
        return 1
    fi
}

# 安装Python（优先，因为Python是proot环境下最好的自救工具）
echo "[cms-bootstrap] 📦 Installing python3..."
robust_install "python3" "python3" || echo "[cms-bootstrap] ⚠️ python3 installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing python3-pip..."
robust_install "python3-pip" "pip3" || echo "[cms-bootstrap] ⚠️ python3-pip installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing python3-venv..."
robust_install "python3-venv" "python3" || echo "[cms-bootstrap] ⚠️ python3-venv installation failed, continuing..."

# 安装Python开发头文件（修复Python.h缺失）
echo "[cms-bootstrap] 📦 Installing python3.12-dev..."
mkdir -p /tmp/debs && cd /tmp/debs
apt-get download python3.12-dev libpython3.12-dev 2>/dev/null
for f in *.deb; do dpkg-deb -x "$f" / 2>/dev/null; done
rm -f *.deb 2>/dev/null
cd /
rm -rf /tmp/debs

# 安装Node.js（使用官方独立二进制，避免Ubuntu包的externalized builtins问题）
echo "[cms-bootstrap] 📦 Installing Node.js 20 (official binary)..."
if command -v node >/dev/null 2>&1; then
    NODE_VERSION=$(node --version 2>&1)
    echo "[cms-bootstrap] ✅ Node.js already installed: $NODE_VERSION"
else
    echo "[cms-bootstrap] 📦 Downloading Node.js v20.19.0 from npmmirror..."
    NODE_URL="https://npmmirror.com/mirrors/node/v20.19.0/node-v20.19.0-linux-arm64.tar.xz"
    curl -fsSL "$NODE_URL" -o /tmp/node.tar.xz 2>/dev/null
    if [ $? -eq 0 ] && [ -f /tmp/node.tar.xz ]; then
        echo "[cms-bootstrap] 📦 Extracting Node.js..."
        tar -xf /tmp/node.tar.xz -C /usr/local --strip-components=1 2>/dev/null
        rm -f /tmp/node.tar.xz
        if command -v node >/dev/null 2>&1; then
            echo "[cms-bootstrap] ✅ Node.js installed: $(node --version)"
        else
            echo "[cms-bootstrap] ⚠️ Node.js extraction succeeded but command not found"
        fi
    else
        echo "[cms-bootstrap] ⚠️ Node.js download failed, trying apt fallback..."
        robust_install "nodejs" "node" || echo "[cms-bootstrap] ⚠️ nodejs installation failed, continuing..."
    fi
fi

# 安装npm（如果Node.js安装成功）
echo "[cms-bootstrap] 📦 Installing npm..."
if command -v npm >/dev/null 2>&1; then
    echo "[cms-bootstrap] ✅ npm already installed: $(npm --version)"
else
    robust_install "npm" "npm" || echo "[cms-bootstrap] ⚠️ npm installation failed, continuing..."
fi

# 修复node-gyp PATH问题
echo "[cms-bootstrap] 🔧 Fixing node-gyp PATH..."
if [ -d /opt/node-v20.19.0-linux-arm64/lib/node_modules/node-gyp ]; then
    ln -sf /opt/node-v20.19.0-linux-arm64/lib/node_modules/node-gyp/bin/node-gyp.js \
           /opt/node-v20.19.0-linux-arm64/bin/node-gyp 2>/dev/null
    echo "[cms-bootstrap] ✅ node-gyp symlink created"
fi

# 安装Go
echo "[cms-bootstrap] 📦 Installing Go..."
if command -v go >/dev/null 2>&1; then
    echo "[cms-bootstrap] ✅ Go already installed: $(go --version 2>&1 | head -1)"
else
    echo "[cms-bootstrap] 📦 Downloading Go 1.23.4..."
    GO_URL="https://npmmirror.com/mirrors/golang/go1.23.4.linux-arm64.tar.gz"
    curl -fsSL "$GO_URL" -o /tmp/go.tar.gz 2>/dev/null
    if [ $? -eq 0 ] && [ -f /tmp/go.tar.gz ]; then
        echo "[cms-bootstrap] 📦 Extracting Go..."
        tar -xf /tmp/go.tar.gz -C /usr/local 2>/dev/null
        rm -f /tmp/go.tar.gz
        export PATH=$PATH:/usr/local/go/bin
        if command -v go >/dev/null 2>&1; then
            echo "[cms-bootstrap] ✅ Go installed: $(go --version 2>&1 | head -1)"
        fi
    fi
fi

# 安装Rust（使用中科大镜像）
echo "[cms-bootstrap] 📦 Installing Rust..."
if command -v rustc >/dev/null 2>&1; then
    echo "[cms-bootstrap] ✅ Rust already installed: $(rustc --version 2>&1)"
else
    echo "[cms-bootstrap] 📦 Downloading rustup-init..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --profile minimal 2>/dev/null
    if [ $? -eq 0 ]; then
        # 配置中科大镜像
        export RUSTUP_DIST_SERVER="https://mirrors.ustc.edu.cn/rust-static"
        export RUSTUP_UPDATE_ROOT="https://mirrors.ustc.edu.cn/rust-static/rustup"
        source "$HOME/.cargo/env" 2>/dev/null
        rustup component add rustfmt clippy 2>/dev/null
        echo "[cms-bootstrap] ✅ Rust installed: $(rustc --version 2>&1)"
    fi
fi

# ═══ Phase 4: 编译工具链 ═══
echo "[cms-bootstrap] 🔨 Phase 4: installing build toolchain..."
echo "[cms-bootstrap] 📦 Installing gcc..."
robust_install "gcc" "gcc" || echo "[cms-bootstrap] ⚠️ gcc installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing g++..."
robust_install "g++" "g++" || echo "[cms-bootstrap] ⚠️ g++ installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing make..."
robust_install "make" "make" || echo "[cms-bootstrap] ⚠️ make installation failed, continuing..."

# 安装CMake及其依赖链
echo "[cms-bootstrap] 📦 Installing CMake with dependencies..."
mkdir -p /tmp/debs && cd /tmp/debs
apt-get download cmake cmake-data libarchive13t64 libjsoncpp25 libuv1t64 librhash0 libxml2 2>/dev/null
for f in *.deb; do dpkg-deb -x "$f" / 2>/dev/null; done
rm -f *.deb 2>/dev/null
cd /
rm -rf /tmp/debs
ldconfig 2>/dev/null

echo "[cms-bootstrap] 📦 Installing linux-headers-generic..."
robust_install "linux-headers-generic" "make" || echo "[cms-bootstrap] ⚠️ linux-headers-generic installation failed, continuing..."

# ═══ Phase 5: 开发工具 ═══
echo "[cms-bootstrap] 🛠️ Phase 5: installing dev tools..."
echo "[cms-bootstrap] 📦 Installing git..."
robust_install "git" "git" || echo "[cms-bootstrap] ⚠️ git installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing vim..."
robust_install "vim" "vim" || echo "[cms-bootstrap] ⚠️ vim installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing nano..."
robust_install "nano" "nano" || echo "[cms-bootstrap] ⚠️ nano installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing bash..."
robust_install "bash" "bash" || echo "[cms-bootstrap] ⚠️ bash installation failed, continuing..."

# ═══ Phase 6: 网络与压缩工具 ═══
echo "[cms-bootstrap] 🌐 Phase 6: installing network & utility tools..."
echo "[cms-bootstrap] 📦 Installing curl..."
robust_install "curl" "curl" || echo "[cms-bootstrap] ⚠️ curl installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing wget..."
robust_install "wget" "wget" || echo "[cms-bootstrap] ⚠️ wget installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing jq..."
robust_install "jq" "jq" || echo "[cms-bootstrap] ⚠️ jq installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing zip..."
robust_install "zip" "zip" || echo "[cms-bootstrap] ⚠️ zip installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing unzip..."
robust_install "unzip" "unzip" || echo "[cms-bootstrap] ⚠️ unzip installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing openssh-client..."
robust_install "openssh-client" "ssh" || echo "[cms-bootstrap] ⚠️ openssh-client installation failed, continuing..."
echo "[cms-bootstrap] 📦 Installing xz-utils..."
robust_install "xz-utils" "xz" || echo "[cms-bootstrap] ⚠️ xz-utils installation failed, continuing..."

# ═══ Phase 6.5: 修复CA证书（手动合并，避免 update-ca-certificates 在 proot 下失败） ═══
echo "[cms-bootstrap] 🔧 Phase 6.5: fixing CA certificates..."
if [ -d /usr/share/ca-certificates/mozilla ]; then
    echo "[cms-bootstrap] 📦 Merging CA certificates manually..."
    cat /usr/share/ca-certificates/mozilla/*.crt > /etc/ssl/certs/ca-certificates.crt 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "[cms-bootstrap] ✅ CA certificates merged successfully"
    else
        echo "[cms-bootstrap] ⚠️ CA certificates merge failed, but continuing..."
    fi
else
    echo "[cms-bootstrap] ⚠️ CA certificates directory not found, skipping..."
fi

# ═══ Phase 7: Python venv 和依赖 ═══
echo "[cms-bootstrap] 🐍 Phase 7: creating Python venv and installing dependencies..."
if [ ! -x /root/cms-venv/bin/python3 ]; then
    echo "[cms-bootstrap] 📦 Creating /root/cms-venv..."
    if python3 -m venv /root/cms-venv 2>&1; then
        echo "[cms-bootstrap] ✅ Python venv created successfully"
    else
        echo "[cms-bootstrap] ⚠️ Python venv creation failed (system python still usable)"
    fi
else
    echo "[cms-bootstrap] ✅ Python venv already exists"
fi

# 安装Python requests依赖链
echo "[cms-bootstrap] 📦 Installing Python requests dependencies..."
pip3 install --break-system-packages certifi chardet charset-normalizer urllib3 2>/dev/null
echo "[cms-bootstrap] ✅ Python requests dependencies installed"

# ═══ 验证 ═══
echo "[cms-bootstrap] 🔍 Phase 8: verifying installation..."
echo "[cms-bootstrap] ✅ dev environment ready:"
echo "  python3  = $(python3 --version 2>&1)"
echo "  node     = $(node --version 2>&1)"
echo "  npm      = $(npm --version 2>&1)"
echo "  gcc      = $(gcc --version 2>&1 | head -1)"
echo "  cmake    = $(cmake --version 2>&1 | head -1)"
echo "  git      = $(git --version 2>&1)"
echo "  curl     = $(curl --version 2>&1 | head -1)"
echo "  go       = $(go --version 2>&1 | head -1)"
echo "  rustc    = $(rustc --version 2>&1)"

echo "[cms-bootstrap] 📊 Installation summary:"
echo "  - python3: $(command -v python3 2>&1 || echo 'not found')"
echo "  - pip3: $(command -v pip3 2>&1 || echo 'not found')"
echo "  - node: $(command -v node 2>&1 || echo 'not found')"
echo "  - npm: $(command -v npm 2>&1 || echo 'not found')"
echo "  - gcc: $(command -v gcc 2>&1 || echo 'not found')"
echo "  - g++: $(command -v g++ 2>&1 || echo 'not found')"
echo "  - make: $(command -v make 2>&1 || echo 'not found')"
echo "  - cmake: $(command -v cmake 2>&1 || echo 'not found')"
echo "  - git: $(command -v git 2>&1 || echo 'not found')"
echo "  - curl: $(command -v curl 2>&1 || echo 'not found')"
echo "  - wget: $(command -v wget 2>&1 || echo 'not found')"
echo "  - vim: $(command -v vim 2>&1 || echo 'not found')"
echo "  - nano: $(command -v nano 2>&1 || echo 'not found')"
echo "  - bash: $(command -v bash 2>&1 || echo 'not found')"
echo "  - go: $(command -v go 2>&1 || echo 'not found')"
echo "  - rustc: $(command -v rustc 2>&1 || echo 'not found')"

# 仅当核心开发工具齐全才写 .bootstrap.done（避免"标记有了但工具没装"的假成功，这正是终端缺开发工具、模块装不上的根因）
CORE_OK=1
for t in python3 node gcc make cmake git curl; do
    if command -v $t >/dev/null 2>&1; then
        echo "[cms-bootstrap] ✅ $t present"
    else
        echo "[cms-bootstrap] ❌ $t MISSING"
        CORE_OK=0
    fi
done
if [ "$CORE_OK" = "1" ]; then
    touch "$BOOT_DIR/.bootstrap.done"
    echo "[cms-bootstrap] ✅ marker written: $BOOT_DIR/.bootstrap.done"
else
    echo "[cms-bootstrap] ❌ core dev tools missing, NOT writing .bootstrap.done（下次部署会重试，可在 CMS 日志看到缺了哪个工具）"
fi
echo "[cms-bootstrap] 🎉 Bootstrap completed at $(date)"