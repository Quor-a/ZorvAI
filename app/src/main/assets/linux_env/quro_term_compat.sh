#!/bin/sh
# QuroTerm 双兼容层 - 不修改原二进制，提供跨平台命令兼容
# 用法: quro_term_compat.sh [command]

set -e

SANDBOX_DIR="${SANDBOX_DIR:-/data/user/0/com.ai.assistance.quro/files/linux-sandbox}"
ROOTFS_DIR="${SANDBOX_DIR}/rootfs"

# 1. 确保 libtalloc.so.2 存在
ensure_libtalloc() {
    local talloc="${SANDBOX_DIR}/libtalloc.so.2"
    if [ ! -f "$talloc" ]; then
        # 尝试从 nativeLibraryDir 复制
        local native_dir="/data/app/$(ls /data/app/ | grep com.ai.assistance.quro | head -1)/lib/arm64"
        if [ -f "${native_dir}/libtalloc.so" ]; then
            cp "${native_dir}/libtalloc.so" "$talloc"
        fi
    fi
}

# 2. 安装跨平台命令兼容层
install_compat_layer() {
    local bin_dir="${ROOTFS_DIR}/usr/local/bin"
    mkdir -p "$bin_dir"
    
    # Debian/Ubuntu 命令兼容
    for cmd in apt apt-get dpkg dpkg-reconfigure; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Debian/Ubuntu command compat -> Alpine apk
case "$1" in
    install|remove|purge)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    update)
        apk update 2>/dev/null || true
        ;;
    search)
        apk search "$2" 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # Termux 命令兼容
    for cmd in pkg termux-setup-storage termux-open; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Termux command compat -> Alpine apk
case "$1" in
    install|upgrade)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    update)
        apk update 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # CentOS/RHEL 命令兼容
    for cmd in yum dnf rpm; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# CentOS/RHEL command compat -> Alpine apk
case "$1" in
    install|remove)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    update|upgrade)
        apk update 2>/dev/null || true
        ;;
    -qa)
        apk list --installed 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # Arch 命令兼容
    for cmd in pacman yay makepkg; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Arch command compat -> Alpine apk
case "$1" in
    -S|--sync)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    -Syu|--sync --refresh --sysupgrade)
        apk update && apk upgrade 2>/dev/null || true
        ;;
    -R|--remove)
        shift
        apk del "$@" 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # openSUSE 命令兼容
    cat > "${bin_dir}/zypper" << 'COMPAT_EOF'
#!/bin/sh
# openSUSE command compat -> Alpine apk
case "$1" in
    install|in)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    remove|rm)
        shift
        apk del "$@" 2>/dev/null || true
        ;;
    update|up)
        apk update 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
    chmod +x "${bin_dir}/zypper"
    
    # Void Linux 命令兼容
    for cmd in xbps-install xbps-remove xbps-query; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Void Linux command compat -> Alpine apk
case "$1" in
    -S|--sync)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    -R|--remove)
        shift
        apk del "$@" 2>/dev/null || true
        ;;
    -Q|--query)
        apk list --installed 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # NixOS 命令兼容
    for cmd in nix-env nix-channel; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# NixOS command compat -> Alpine apk
case "$1" in
    -i|--install)
        shift
        apk add "$@" 2>/dev/null || echo "apk: $@"
        ;;
    -e|--erase)
        shift
        apk del "$@" 2>/dev/null || true
        ;;
    -u|--upgrade)
        shift
        apk upgrade "$@" 2>/dev/null || true
        ;;
    *)
        echo "compat: $0 $@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # macOS 命令兼容
    for cmd in open pbcopy pbpaste say; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# macOS command compat
case "$(basename $0)" in
    open)
        if [ -d "$1" ]; then
            ls "$1"
        elif [ -f "$1" ]; then
            cat "$1"
        fi
        ;;
    pbcopy)
        cat > /tmp/.quro_clipboard
        ;;
    pbpaste)
        cat /tmp/.quro_clipboard 2>/dev/null || true
        ;;
    say)
        echo "$@"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # 通用系统命令兼容
    for cmd in systemctl service update-rc.d invoke-rc.d update-alternatives; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Generic system command compat
echo "compat: $0 $@ (no-op on Alpine)"
exit 0
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # 网络命令兼容
    for cmd in ifconfig netstat route; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Network command compat -> busybox/applets
case "$1" in
    "")
        busybox $0 2>/dev/null || ip addr 2>/dev/null || echo "Network info not available"
        ;;
    *)
        busybox $0 "$@" 2>/dev/null || ip "$@" 2>/dev/null || echo "Network command not available"
        ;;
esac
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # 开发工具兼容
    for cmd in gcc g++ make cmake gdb strace ltrace; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Dev tool compat
if command -v $0 >/dev/null 2>&1; then
    exec $0 "$@"
else
    echo "Installing $0..."
    apk add $0 2>/dev/null || echo "Package $0 not found in Alpine repos"
fi
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
    
    # 运行时兼容
    for cmd in python python3 pip pip3 node npm; do
        cat > "${bin_dir}/${cmd}" << 'COMPAT_EOF'
#!/bin/sh
# Runtime compat
if command -v $0 >/dev/null 2>&1; then
    exec $0 "$@"
else
    echo "Installing $0..."
    apk add $0 2>/dev/null || echo "Package $0 not found in Alpine repos"
fi
COMPAT_EOF
        chmod +x "${bin_dir}/${cmd}"
    done
}

# 3. 主函数
main() {
    ensure_libtalloc
    install_compat_layer
    
    # 如果有命令参数，执行
    if [ $# -gt 0 ]; then
        exec "$@"
    fi
}

main "$@"
