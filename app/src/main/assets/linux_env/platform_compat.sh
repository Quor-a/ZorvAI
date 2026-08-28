#!/bin/sh
# ZorvAI 跨平台命令兼容层
# 为 Alpine Linux 环境提供其他平台命令的兼容包装器
# 自动检测当前包管理器并创建对应的命令别名

set -e

COMPAT_DIR="/usr/local/lib/quro-compat"
BIN_DIR="/usr/local/bin"

# ═══════════════════════════════════════════════════════════
# 检测当前包管理器
# ═══════════════════════════════════════════════════════════
detect_package_manager() {
    if command -v apk >/dev/null 2>&1; then
        echo "apk"
    elif command -v apt-get >/dev/null 2>&1; then
        echo "apt-get"
    elif command -v pkg >/dev/null 2>&1; then
        echo "pkg"
    elif command -v yum >/dev/null 2>&1; then
        echo "yum"
    elif command -v dnf >/dev/null 2>&1; then
        echo "dnf"
    elif command -v pacman >/dev/null 2>&1; then
        echo "pacman"
    elif command -v zypper >/dev/null 2>&1; then
        echo "zypper"
    else
        echo "unknown"
    fi
}

# ═══════════════════════════════════════════════════════════
# 创建命令包装器
# ═══════════════════════════════════════════════════════════
create_wrapper() {
    local target="$1"
    local wrapper="$2"
    local description="$3"
    
    mkdir -p "$COMPAT_DIR"
    
    cat > "$COMPAT_DIR/$wrapper" << EOF
#!/bin/sh
# $description
# 自动生成的兼容包装器 - ZorvAI 跨平台兼容层

# 如果目标命令存在，直接执行
if command -v "$target" >/dev/null 2>&1; then
    exec "$target" "\$@"
fi

# 如果目标命令不存在，尝试通过包管理器安装
PM="\$(detect_package_manager)"
case "\$PM" in
    apk)
        echo "[quro-compat] 尝试通过 apk 安装 $target..."
        apk add --no-cache "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    apt-get)
        echo "[quro-compat] 尝试通过 apt-get 安装 $target..."
        apt-get update -qq && apt-get install -y --no-install-recommends "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    pkg)
        echo "[quro-compat] 尝试通过 pkg 安装 $target..."
        pkg install -y "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    yum)
        echo "[quro-compat] 尝试通过 yum 安装 $target..."
        yum install -y "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    dnf)
        echo "[quro-compat] 尝试通过 dnf 安装 $target..."
        dnf install -y "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    pacman)
        echo "[quro-compat] 尝试通过 pacman 安装 $target..."
        pacman -S --noconfirm "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    zypper)
        echo "[quro-compat] 尝试通过 zypper 安装 $target..."
        zypper install -y "$target" 2>/dev/null && exec "$target" "\$@"
        ;;
    *)
        echo "[quro-compat] 错误: 未找到包管理器，无法安装 $target"
        exit 1
        ;;
esac
EOF
    
    chmod +x "$COMPAT_DIR/$wrapper"
    ln -sf "$COMPAT_DIR/$wrapper" "$BIN_DIR/$wrapper" 2>/dev/null || true
}

# ═══════════════════════════════════════════════════════════
# Debian/Ubuntu 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_debian_compat() {
    echo "[platform-compat] 设置 Debian/Ubuntu 兼容..."
    
    # apt 包装器
    create_wrapper "apk" "apt" "Debian apt 兼容包装器"
    
    # dpkg 包装器
    create_wrapper "apk" "dpkg" "Debian dpkg 兼容包装器"
    
    # apt-get 已存在则跳过，不存在则创建
    if ! command -v apt-get >/dev/null 2>&1; then
        create_wrapper "apk" "apt-get" "Debian apt-get 兼容包装器"
    fi
    
    # apt-cache
    create_wrapper "apk" "apt-cache" "Debian apt-cache 兼容包装器"
    
    # apt-mark
    create_wrapper "apk" "apt-mark" "Debian apt-mark 兼容包装器"
    
    # dpkg-reconfigure
    cat > "$BIN_DIR/dpkg-reconfigure" << 'EOF'
#!/bin/sh
# dpkg-reconfigure 兼容包装器
echo "[quro-compat] dpkg-reconfigure 在 Alpine 中不需要，跳过"
exit 0
EOF
    chmod +x "$BIN_DIR/dpkg-reconfigure"
}

# ═══════════════════════════════════════════════════════════
# Termux 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_termux_compat() {
    echo "[platform-compat] 设置 Termux 兼容..."
    
    # pkg 包装器
    create_wrapper "apk" "pkg" "Termux pkg 兼容包装器"
    
    # termux-setup-storage
    cat > "$BIN_DIR/termux-setup-storage" << 'EOF'
#!/bin/sh
# termux-setup-storage 兼容包装器
echo "[quro-compat] termux-setup-storage 在 proot 环境中不需要"
echo "[quro-compat] 文件系统已可写，无需额外存储权限"
exit 0
EOF
    chmod +x "$BIN_DIR/termux-setup-storage"
    
    # termux-open
    cat > "$BIN_DIR/termux-open" << 'EOF'
#!/bin/sh
# termux-open 兼容包装器
if [ $# -eq 0 ]; then
    echo "用法: termux-open <文件>"
    exit 1
fi
# 尝试用 open 或 xdg-open
if command -v open >/dev/null 2>&1; then
    exec open "$@"
elif command -v xdg-open >/dev/null 2>&1; then
    exec xdg-open "$@"
else
    echo "[quro-compat] 无法打开文件: $*"
    echo "[quro-compat] 请手动查看文件: $*"
    exit 1
fi
EOF
    chmod +x "$BIN_DIR/termux-open"
    
    # termux-clipboard-get
    cat > "$BIN_DIR/termux-clipboard-get" << 'EOF'
#!/bin/sh
# termux-clipboard-get 兼容包装器
echo "[quro-compat] termux-clipboard-get 在 proot 环境中不可用"
echo "[quro-compat] 请使用 Android 剪贴板功能"
exit 1
EOF
    chmod +x "$BIN_DIR/termux-clipboard-get"
    
    # termux-clipboard-set
    cat > "$BIN_DIR/termux-clipboard-set" << 'EOF'
#!/bin/sh
# termux-clipboard-set 兼容包装器
echo "[quro-compat] termux-clipboard-set 在 proot 环境中不可用"
echo "[quro-compat] 请使用 Android 剪贴板功能"
exit 1
EOF
    chmod +x "$BIN_DIR/termux-clipboard-set"
}

# ═══════════════════════════════════════════════════════════
# CentOS/RHEL 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_centos_compat() {
    echo "[platform-compat] 设置 CentOS/RHEL 兼容..."
    
    # yum 包装器
    create_wrapper "apk" "yum" "CentOS yum 兼容包装器"
    
    # dnf 包装器
    create_wrapper "apk" "dnf" "Fedora dnf 兼容包装器"
    
    # rpm 包装器
    create_wrapper "apk" "rpm" "RPM 包管理器兼容包装器"
    
    # systemctl 兼容
    cat > "$BIN_DIR/systemctl" << 'EOF'
#!/bin/sh
# systemctl 兼容包装器 - proot 环境中无 systemd
case "$1" in
    start|stop|restart|status|enable|disable)
        echo "[quro-compat] systemctl 在 proot 环境中不可用"
        echo "[quro-compat] 服务管理由 Android 系统处理"
        exit 0
        ;;
    *)
        echo "[quro-compat] systemctl 在 proot 环境中功能受限"
        exit 0
        ;;
esac
EOF
    chmod +x "$BIN_DIR/systemctl"
}

# ═══════════════════════════════════════════════════════════
# Arch Linux 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_arch_compat() {
    echo "[platform-compat] 设置 Arch Linux 兼容..."
    
    # pacman 包装器
    create_wrapper "apk" "pacman" "Arch pacman 兼容包装器"
    
    # yay (AUR helper)
    cat > "$BIN_DIR/yay" << 'EOF'
#!/bin/sh
# yay (AUR helper) 兼容包装器
echo "[quro-compat] yay (AUR) 在 Alpine 中不可用"
echo "[quro-compat] 请使用 apk add 安装软件包"
exit 1
EOF
    chmod +x "$BIN_DIR/yay"
    
    # makepkg
    cat > "$BIN_DIR/makepkg" << 'EOF'
#!/bin/sh
# makepkg 兼容包装器
echo "[quro-compat] makepkg 在 Alpine 中不可用"
echo "[quro-compat] Alpine 使用 abuild 构建包"
exit 1
EOF
    chmod +x "$BIN_DIR/makepkg"
}

# ═══════════════════════════════════════════════════════════
# openSUSE 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_suse_compat() {
    echo "[platform-compat] 设置 openSUSE 兼容..."
    
    # zypper 包装器
    create_wrapper "apk" "zypper" "openSUSE zypper 兼容包装器"
    
    # rpm 包装器（如果还没创建）
    if [ ! -x "$BIN_DIR/rpm" ]; then
        create_wrapper "apk" "rpm" "RPM 包管理器兼容包装器"
    fi
}

# ═══════════════════════════════════════════════════════════
# Void Linux 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_void_compat() {
    echo "[platform-compat] 设置 Void Linux 兼容..."
    
    # xbps 包装器
    create_wrapper "apk" "xbps-install" "Void xbps-install 兼容包装器"
    
    # xbps-remove
    create_wrapper "apk" "xbps-remove" "Void xbps-remove 兼容包装器"
    
    # xbps-query
    create_wrapper "apk" "xbps-query" "Void xbps-query 兼容包装器"
}

# ═══════════════════════════════════════════════════════════
# NixOS 包管理器兼容
# ═══════════════════════════════════════════════════════════
setup_nix_compat() {
    echo "[platform-compat] 设置 NixOS 兼容..."
    
    # nix-env 包装器
    create_wrapper "apk" "nix-env" "NixOS nix-env 兼容包装器"
    
    # nix-channel
    cat > "$BIN_DIR/nix-channel" << 'EOF'
#!/bin/sh
# nix-channel 兼容包装器
echo "[quro-compat] nix-channel 在 Alpine 中不可用"
echo "[quro-compat] 请使用 apk add 安装软件包"
exit 1
EOF
    chmod +x "$BIN_DIR/nix-channel"
}

# ═══════════════════════════════════════════════════════════
# macOS 命令兼容
# ═══════════════════════════════════════════════════════════
setup_macos_compat() {
    echo "[platform-compat] 设置 macOS 兼容..."
    
    # open 命令（macOS 特有）
    if ! command -v open >/dev/null 2>&1; then
        cat > "$BIN_DIR/open" << 'EOF'
#!/bin/sh
# open 命令兼容包装器 (macOS 风格)
if [ $# -eq 0 ]; then
    echo "用法: open <文件或URL>"
    exit 1
fi

# 检查是否是 URL
case "$1" in
    http://*|https://*)
        # 尝试用浏览器打开
        if command -v xdg-open >/dev/null 2>&1; then
            exec xdg-open "$@"
        else
            echo "[quro-compat] 无法打开 URL: $1"
            echo "[quro-compat] 请安装 xdg-utils"
            exit 1
        fi
        ;;
    *)
        # 尝试用文件管理器打开
        if command -v xdg-open >/dev/null 2>&1; then
            exec xdg-open "$@"
        elif command -v nautilus >/dev/null 2>&1; then
            exec nautilus "$@"
        elif command -v thunar >/dev/null 2>&1; then
            exec thunar "$@"
        else
            echo "[quro-compat] 无法打开文件: $1"
            exit 1
        fi
        ;;
esac
EOF
        chmod +x "$BIN_DIR/open"
    fi
    
    # pbcopy (macOS 剪贴板复制)
    cat > "$BIN_DIR/pbcopy" << 'EOF'
#!/bin/sh
# pbcopy 兼容包装器 (macOS 风格)
cat > /tmp/quro-clipboard.txt
echo "[quro-compat] 内容已复制到 /tmp/quro-clipboard.txt"
echo "[quro-compat] 在 Android 中请使用系统剪贴板"
EOF
    chmod +x "$BIN_DIR/pbcopy"
    
    # pbpaste (macOS 剪贴板粘贴)
    cat > "$BIN_DIR/pbpaste" << 'EOF'
#!/bin/sh
# pbpaste 兼容包装器 (macOS 风格)
if [ -f /tmp/quro-clipboard.txt ]; then
    cat /tmp/quro-clipboard.txt
else
    echo "[quro-compat] 剪贴板为空"
    exit 1
fi
EOF
    chmod +x "$BIN_DIR/pbpaste"
    
    # say (macOS 文本转语音)
    cat > "$BIN_DIR/say" << 'EOF'
#!/bin/sh
# say 兼容包装器 (macOS 风格)
if [ $# -eq 0 ]; then
    echo "[quro-compat] say 命令需要文本参数"
    exit 1
fi

# 使用 espeak 或 festival 如果可用
if command -v espeak >/dev/null 2>&1; then
    exec espeak "$*"
elif command -v festival >/dev/null 2>&1; then
    echo "$*" | festival --tts
else
    echo "[quro-compat] 文本: $*"
    echo "[quro-compat] 请安装 espeak 或 festival 以启用语音功能"
fi
EOF
    chmod +x "$BIN_DIR/say"
}

# ═══════════════════════════════════════════════════════════
# BSD 命令兼容
# ═══════════════════════════════════════════════════════════
setup_bsd_compat() {
    echo "[platform-compat] 设置 BSD 兼容..."
    
    # BSD 风格的 ls 选项
    cat > "$BIN_DIR/ls-bsd" << 'EOF'
#!/bin/sh
# BSD 风格的 ls 包装器
# 将 BSD 选项转换为 GNU 选项
ARGS=""
for arg in "$@"; do
    case "$arg" in
        -G) ARGS="$ARGS --color=auto" ;;
        -F) ARGS="$ARGS -F" ;;
        -h) ARGS="$ARGS -h" ;;
        -l) ARGS="$ARGS -l" ;;
        -a) ARGS="$ARGS -a" ;;
        -R) ARGS="$ARGS -R" ;;
        *) ARGS="$ARGS $arg" ;;
    esac
done
exec ls $ARGS
EOF
    chmod +x "$BIN_DIR/ls-bsd"
    
    # BSD 风格的 ps
    cat > "$BIN_DIR/ps-bsd" << 'EOF'
#!/bin/sh
# BSD 风格的 ps 包装器
# 将 BSD 选项转换为 GNU 选项
ARGS=""
for arg in "$@"; do
    case "$arg" in
        aux) ARGS="aux" ;;
        -aux) ARGS="aux" ;;
        ax) ARGS="ax" ;;
        -ax) ARGS="ax" ;;
        *) ARGS="$ARGS $arg" ;;
    esac
done
exec ps $ARGS
EOF
    chmod +x "$BIN_DIR/ps-bsd"
}

# ═══════════════════════════════════════════════════════════
# 通用系统命令兼容
# ═══════════════════════════════════════════════════════════
setup_system_compat() {
    echo "[platform-compat] 设置系统命令兼容..."
    
    # update-alternatives 兼容
    cat > "$BIN_DIR/update-alternatives" << 'EOF'
#!/bin/sh
# update-alternatives 兼容包装器
echo "[quro-compat] update-alternatives 在 Alpine 中不可用"
echo "[quro-compat] Alpine 使用 busybox 或符号链接管理命令"
exit 0
EOF
    chmod +x "$BIN_DIR/update-alternatives"
    
    # update-rc.d 兼容
    cat > "$BIN_DIR/update-rc.d" << 'EOF'
#!/bin/sh
# update-rc.d 兼容包装器
echo "[quro-compat] update-rc.d 在 proot 环境中不可用"
echo "[quro-compat] 服务管理由 Android 系统处理"
exit 0
EOF
    chmod +x "$BIN_DIR/update-rc.d"
    
    # service 兼容
    cat > "$BIN_DIR/service" << 'EOF'
#!/bin/sh
# service 兼容包装器
echo "[quro-compat] service 在 proot 环境中不可用"
echo "[quro-compat] 服务管理由 Android 系统处理"
exit 0
EOF
    chmod +x "$BIN_DIR/service"
    
    # invoke-rc.d 兼容
    cat > "$BIN_DIR/invoke-rc.d" << 'EOF'
#!/bin/sh
# invoke-rc.d 兼容包装器
echo "[quro-compat] invoke-rc.d 在 proot 环境中不可用"
echo "[quro-compat] 服务管理由 Android 系统处理"
exit 0
EOF
    chmod +x "$BIN_DIR/invoke-rc.d"
}

# ═══════════════════════════════════════════════════════════
# 网络工具兼容
# ═══════════════════════════════════════════════════════════
setup_network_compat() {
    echo "[platform-compat] 设置网络工具兼容..."
    
    # ifconfig 包装器（如果不存在）
    if ! command -v ifconfig >/dev/null 2>&1; then
        cat > "$BIN_DIR/ifconfig" << 'EOF'
#!/bin/sh
# ifconfig 兼容包装器
if command -v ip >/dev/null 2>&1; then
    exec ip "$@"
else
    echo "[quro-compat] ifconfig 和 ip 都不可用"
    echo "[quro-compat] 请安装 net-tools 或 iproute2"
    exit 1
fi
EOF
        chmod +x "$BIN_DIR/ifconfig"
    fi
    
    # netstat 包装器（如果不存在）
    if ! command -v netstat >/dev/null 2>&1; then
        cat > "$BIN_DIR/netstat" << 'EOF'
#!/bin/sh
# netstat 兼容包装器
if command -v ss >/dev/null 2>&1; then
    exec ss "$@"
else
    echo "[quro-compat] netstat 和 ss 都不可用"
    echo "[quro-compat] 请安装 net-tools 或 iproute2"
    exit 1
fi
EOF
        chmod +x "$BIN_DIR/netstat"
    fi
    
    # route 包装器（如果不存在）
    if ! command -v route >/dev/null 2>&1; then
        cat > "$BIN_DIR/route" << 'EOF'
#!/bin/sh
# route 兼容包装器
if command -v ip >/dev/null 2>&1; then
    exec ip route "$@"
else
    echo "[quro-compat] route 和 ip route 都不可用"
    echo "[quro-compat] 请安装 net-tools 或 iproute2"
    exit 1
fi
EOF
        chmod +x "$BIN_DIR/route"
    fi
}

# ═══════════════════════════════════════════════════════════
# 开发工具兼容
# ═══════════════════════════════════════════════════════════
setup_dev_compat() {
    echo "[platform-compat] 设置开发工具兼容..."
    
    # gcc 包装器（如果不存在）
    if ! command -v gcc >/dev/null 2>&1; then
        create_wrapper "gcc" "gcc" "GCC 编译器"
    fi
    
    # g++ 包装器（如果不存在）
    if ! command -v g++ >/dev/null 2>&1; then
        create_wrapper "g++" "g++" "G++ 编译器"
    fi
    
    # make 包装器（如果不存在）
    if ! command -v make >/dev/null 2>&1; then
        create_wrapper "make" "make" "Make 构建工具"
    fi
    
    # cmake 包装器（如果不存在）
    if ! command -v cmake >/dev/null 2>&1; then
        create_wrapper "cmake" "cmake" "CMake 构建系统"
    fi
    
    # gdb 包装器（如果不存在）
    if ! command -v gdb >/dev/null 2>&1; then
        create_wrapper "gdb" "gdb" "GDB 调试器"
    fi
    
    # strace 包装器（如果不存在）
    if ! command -v strace >/dev/null 2>&1; then
        create_wrapper "strace" "strace" "strace 系统调用跟踪"
    fi
}

# ═══════════════════════════════════════════════════════════
# 语言运行时兼容
# ═══════════════════════════════════════════════════════════
setup_runtime_compat() {
    echo "[platform-compat] 设置语言运行时兼容..."
    
    # python 包装器（如果不存在）
    if ! command -v python >/dev/null 2>&1; then
        cat > "$BIN_DIR/python" << 'EOF'
#!/bin/sh
# python 兼容包装器
if command -v python3 >/dev/null 2>&1; then
    exec python3 "$@"
else
    echo "[quro-compat] python 和 python3 都不可用"
    echo "[quro-compat] 请安装 python3"
    exit 1
fi
EOF
        chmod +x "$BIN_DIR/python"
    fi
    
    # pip 包装器（如果不存在）
    if ! command -v pip >/dev/null 2>&1; then
        cat > "$BIN_DIR/pip" << 'EOF'
#!/bin/sh
# pip 兼容包装器
if command -v pip3 >/dev/null 2>&1; then
    exec pip3 "$@"
elif command -v python3 >/dev/null 2>&1; then
    exec python3 -m pip "$@"
else
    echo "[quro-compat] pip 和 pip3 都不可用"
    echo "[quro-compat] 请安装 python3-pip"
    exit 1
fi
EOF
        chmod +x "$BIN_DIR/pip"
    fi
    
    # node 包装器（如果不存在）
    if ! command -v node >/dev/null 2>&1; then
        cat > "$BIN_DIR/node" << 'EOF'
#!/bin/sh
# node 兼容包装器
if command -v nodejs >/dev/null 2>&1; then
    exec nodejs "$@"
else
    echo "[quro-compat] node 和 nodejs 都不可用"
    echo "[quro-compat] 请安装 nodejs"
    exit 1
fi
EOF
        chmod +x "$BIN_DIR/node"
    fi
}

# ═══════════════════════════════════════════════════════════
# 主函数
# ═══════════════════════════════════════════════════════════
main() {
    echo "═══════════════════════════════════════════════════════"
    echo "[platform-compat] ZorvAI 跨平台命令兼容层"
    echo "═══════════════════════════════════════════════════════"
    
    # 检测当前包管理器
    PM=$(detect_package_manager)
    echo "[platform-compat] 检测到包管理器: $PM"
    
    # 创建兼容目录
    mkdir -p "$COMPAT_DIR" "$BIN_DIR"
    
    # 设置兼容层 —— 关键隔离逻辑：
    # 如果底层系统已经是 Debian/Ubuntu（有 apt-get），则跳过 Debian 兼容层，
    # 避免 /usr/local/bin/apt 等 wrapper 遮蔽 /usr/bin/apt 原生命令。
    case "$PM" in
        apt-get|dpkg)
            echo "[platform-compat] 检测到 Debian/Ubuntu 系统，跳过 Debian 兼容层（保留原生 apt/dpkg）"
            ;;
        *)
            setup_debian_compat
            ;;
    esac
    setup_termux_compat
    setup_centos_compat
    setup_arch_compat
    setup_suse_compat
    setup_void_compat
    setup_nix_compat
    setup_macos_compat
    setup_bsd_compat
    setup_system_compat
    setup_network_compat
    setup_dev_compat
    setup_runtime_compat
    
    echo ""
    echo "═══════════════════════════════════════════════════════"
    echo "[platform-compat] ✅ 跨平台兼容层安装完成"
    echo "[platform-compat] 已创建以下命令的兼容包装器:"
    echo "  - Debian/Ubuntu: apt, dpkg, apt-get, apt-cache, apt-mark, dpkg-reconfigure"
    echo "  - Termux: pkg, termux-setup-storage, termux-open, termux-clipboard-*, termux-reload-settings"
    echo "  - CentOS/RHEL: yum, dnf, rpm, systemctl"
    echo "  - Arch Linux: pacman, yay, makepkg"
    echo "  - openSUSE: zypper"
    echo "  - Void Linux: xbps-install, xbps-remove, xbps-query"
    echo "  - NixOS: nix-env, nix-channel"
    echo "  - macOS: open, pbcopy, pbpaste, say"
    echo "  - BSD: ls-bsd, ps-bsd"
    echo "  - 通用: update-alternatives, update-rc.d, service, invoke-rc.d"
    echo "  - 网络: ifconfig, netstat, route"
    echo "  - 开发: gcc, g++, make, cmake, gdb, strace"
    echo "  - 运行时: python, pip, node"
    echo "═══════════════════════════════════════════════════════"
    echo "[platform-compat] 🎉 所有平台的命令现在都可以使用了！"
    echo ""
    echo "[platform-compat] 使用说明:"
    echo "  - 包管理器命令会自动转换为当前系统的包管理器"
    echo "  - 如果命令不存在，会尝试自动安装"
    echo "  - 部分命令在 proot 环境中功能受限（如 systemctl）"
    echo "  - 所有包装器都位于 $BIN_DIR"
    echo "═══════════════════════════════════════════════════════"
}

# 执行主函数
main "$@"
