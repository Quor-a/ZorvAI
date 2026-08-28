#!/bin/sh
# ═══════════════════════════════════════════════════════════════════════════
# QuroTerm 命令路由层 - 双兼容架构
# ═══════════════════════════════════════════════════════════════════════════
# 
# 架构：原生命令优先 → 兼容层转发 → 提示不存在
# 
# 用法：由 prepareRuntimeExtras 安装到 /usr/local/bin/
#       确保 PATH 中 /usr/local/bin 优先于 /usr/bin /bin
#
# 工作流程：
#   1. 用户输入命令（如 apt install python3）
#   2. 检查原生终端是否有该命令（command -v）
#   3. 如果有，直接执行原生命令
#   4. 如果没有，检查兼容层是否有对应的转换器
#   5. 如果有转换器，转发到兼容层执行
#   6. 如果都没有，提示命令不存在
# ═══════════════════════════════════════════════════════════════════════════

set -e

# 命令映射表：原生命令 -> 兼容层命令
# 格式：原生命令|兼容层命令|转换函数名
#
# ⚠ 注意：apt-get|apt|dpkg 不在此列表中！
# Ubuntu 24.04 rootfs 自带原生 apt/dpkg，不需要转换到 apk。
# 如果加进来，/usr/local/bin/apt 会遮蔽 /usr/bin/apt，导致原生命令失效。
CMD_MAP="
dpkg-deb|tar|_dpkgdeb_to_tar
pkg|apk|_pkg_to_apk
yum|apk|_yum_to_apk
dnf|apk|_dnf_to_apk
pacman|apk|_pacman_to_apk
zypper|apk|_zypper_to_apk
xbps-install|apk|_xbps_to_apk
nix-env|apk|_nix_to_apk
swupd|apk|_swupd_to_apk
"

# ═══════════════════════════════════════════════════════════
# 转换函数：Debian apt -> Alpine apk
# ═══════════════════════════════════════════════════════════
_apt_to_apk() {
    case "$1" in
        install|i)
            shift
            apk add "$@"
            ;;
        remove|r|purge)
            shift
            apk del "$@"
            ;;
        update)
            apk update
            ;;
        upgrade)
            apk upgrade
            ;;
        search)
            apk search "$2"
            ;;
        show)
            apk info "$2"
            ;;
        list|--installed)
            apk list --installed
            ;;
        *)
            # 尝试直接执行 apk
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：Debian dpkg -> Alpine apk
# ═══════════════════════════════════════════════════════════
_dpkg_to_apk() {
    case "$1" in
        -i|--install)
            shift
            apk add --allow-untrusted "$@"
            ;;
        -r|--remove)
            shift
            apk del "$@"
            ;;
        -l|--list)
            apk list --installed
            ;;
        -s|--status)
            apk info "$2"
            ;;
        --configure|-a)
            # dpkg --configure -a: 重新配置所有待配置的包
            echo "[quro-compat] dpkg --configure -a: Alpine 不需要此操作"
            ;;
        --configure *)
            shift
            echo "[quro-compat] dpkg --configure $@: Alpine 不需要此操作"
            ;;
        *)
            echo "[quro-compat] dpkg $@: 转发到 apk"
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：dpkg-deb -> tar
# ═══════════════════════════════════════════════════════════
_dpkgdeb_to_tar() {
    case "$1" in
        -x|--extract|-xf)
            shift
            # dpkg-deb -x package.deb target_dir
            # 转换为: tar xf package.deb -C target_dir
            local pkg="$1"
            local dest="${2:-.}"
            if [ -f "$pkg" ]; then
                mkdir -p "$dest"
                # .deb 文件实际上是 ar 归档，里面包含 data.tar.xz
                # 需要先解 ar，再解 data.tar.xz
                local tmpdir=$(mktemp -d)
                ar x "$pkg" --output="$tmpdir" 2>/dev/null || {
                    # 如果没有 ar 命令，尝试直接解压
                    cd "$dest" && tar xf "$pkg" 2>/dev/null || echo "无法解压 $pkg"
                    cd - >/dev/null
                    rm -rf "$tmpdir"
                    return
                }
                if [ -f "$tmpdir/data.tar.xz" ]; then
                    tar xf "$tmpdir/data.tar.xz" -C "$dest"
                elif [ -f "$tmpdir/data.tar.gz" ]; then
                    tar xf "$tmpdir/data.tar.gz" -C "$dest"
                elif [ -f "$tmpdir/data.tar.zst" ]; then
                    tar xf "$tmpdir/data.tar.zst" -C "$dest" 2>/dev/null || zstd -d "$tmpdir/data.tar.zst" -o "$tmpdir/data.tar" && tar xf "$tmpdir/data.tar" -C "$dest"
                fi
                rm -rf "$tmpdir"
            fi
            ;;
        -I|--info)
            shift
            # 显示包信息
            if [ -f "$1" ]; then
                local tmpdir=$(mktemp -d)
                ar t "$1" 2>/dev/null
                rm -rf "$tmpdir"
            fi
            ;;
        *)
            echo "[quro-compat] dpkg-deb $@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：Termux pkg -> Alpine apk
# ═══════════════════════════════════════════════════════════
_pkg_to_apk() {
    case "$1" in
        install|i)
            shift
            apk add "$@"
            ;;
        uninstall|remove|r)
            shift
            apk del "$@"
            ;;
        upgrade|up)
            apk upgrade
            ;;
        update)
            apk update
            ;;
        search|s)
            apk search "$2"
            ;;
        list-installed)
            apk list --installed
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：CentOS yum -> Alpine apk
# ═══════════════════════════════════════════════════════════
_yum_to_apk() {
    case "$1" in
        install|i)
            shift
            apk add "$@"
            ;;
        remove|erase|r)
            shift
            apk del "$@"
            ;;
        update)
            apk update
            ;;
        upgrade|update-to)
            apk upgrade
            ;;
        search)
            apk search "$2"
            ;;
        info)
            apk info "$2"
            ;;
        list)
            apk list --installed
            ;;
        clean)
            apk cache clean 2>/dev/null || true
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：CentOS dnf -> Alpine apk
# ═══════════════════════════════════════════════════════════
_dnf_to_apk() {
    case "$1" in
        install|i)
            shift
            apk add "$@"
            ;;
        remove|erase|r)
            shift
            apk del "$@"
            ;;
        upgrade|up)
            apk upgrade
            ;;
        check-update)
            apk update
            ;;
        search)
            apk search "$2"
            ;;
        info)
            apk info "$2"
            ;;
        list)
            apk list --installed
            ;;
        clean)
            apk cache clean 2>/dev/null || true
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：Arch pacman -> Alpine apk
# ═══════════════════════════════════════════════════════════
_pacman_to_apk() {
    case "$1" in
        -S|--sync)
            shift
            if [ "$1" = "-y" ] || [ "$1" = "--refresh" ]; then
                shift
                apk update
                if [ "$1" = "-u" ] || [ "$1" = "--sysupgrade" ]; then
                    shift
                    apk upgrade
                fi
            elif [ "$1" = "-u" ] || [ "$1" = "--sysupgrade" ]; then
                shift
                apk upgrade
            else
                apk add "$@"
            fi
            ;;
        -R|--remove)
            shift
            apk del "$@"
            ;;
        -Q|--query)
            shift
            if [ "$1" = "-e" ] || [ "$1" = "--explicit" ]; then
                apk list --installed
            else
                apk list --installed
            fi
            ;;
        -Syu|--sync --refresh --sysupgrade)
            apk update && apk upgrade
            ;;
        -Ss|--sync --search)
            shift
            apk search "$@"
            ;;
        -Si|--sync --info)
            shift
            apk info "$@"
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：openSUSE zypper -> Alpine apk
# ═══════════════════════════════════════════════════════════
_zypper_to_apk() {
    case "$1" in
        install|in|i)
            shift
            apk add "$@"
            ;;
        remove|rm|r|uninstall)
            shift
            apk del "$@"
            ;;
        update|up|refresh)
            apk update
            ;;
        list-updates|lu)
            apk update
            ;;
        search|se|si)
            shift
            apk search "$@"
            ;;
        info|if)
            shift
            apk info "$@"
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：Void xbps -> Alpine apk
# ═══════════════════════════════════════════════════════════
_xbps_to_apk() {
    case "$1" in
        -S|--sync)
            shift
            apk add "$@"
            ;;
        -R|--remove)
            shift
            apk del "$@"
            ;;
        -Q|--query)
            apk list --installed
            ;;
        -Su|--sync --update)
            apk upgrade
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：NixOS nix-env -> Alpine apk
# ═══════════════════════════════════════════════════════════
_nix_to_apk() {
    case "$1" in
        -i|--install|-iA)
            shift
            # 去掉 nixpkgs. 前缀
            local pkg="${1#nixpkgs.}"
            apk add "$pkg"
            ;;
        -e|--erase)
            shift
            local pkg="${1#nixpkgs.}"
            apk del "$pkg"
            ;;
        -u|--upgrade)
            apk upgrade
            ;;
        -q|--query)
            apk list --installed
            ;;
        -p|--profile)
            shift
            apk list --installed
            ;;
        *)
            echo "[quro-compat] nix-env $@: 转发到 apk"
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 转换函数：swupd -> Alpine apk
# ═══════════════════════════════════════════════════════════
_swupd_to_apk() {
    case "$1" in
        bundle-add)
            shift
            apk add "$@"
            ;;
        bundle-remove)
            shift
            apk del "$@"
            ;;
        bundle-list)
            apk list --installed
            ;;
        update)
            apk update
            ;;
        search)
            apk search "$2"
            ;;
        *)
            apk "$@"
            ;;
    esac
}

# ═══════════════════════════════════════════════════════════
# 主路由逻辑
# ═══════════════════════════════════════════════════════════
route_command() {
    local cmd="$1"
    shift
    
    # 1. 如果原生命令存在，直接执行
    if command -v "$cmd" >/dev/null 2>&1; then
        exec "$cmd" "$@"
    fi
    
    # 2. 检查是否有兼容层转换器
    local map_line
    echo "$CMD_MAP" | while IFS='|' read -r orig_cmd compat_cmd conv_func; do
        # 跳过空行
        [ -z "$orig_cmd" ] && continue
        
        if [ "$cmd" = "$orig_cmd" ]; then
            # 检查兼容层命令是否存在
            if command -v "$compat_cmd" >/dev/null 2>&1; then
                echo "[quro-compat] 原生命令 '$cmd' 不存在，转发到兼容层 '$conv_func'"
                $conv_func "$@"
                exit $?
            else
                echo "[quro-compat] 原生命令 '$cmd' 和兼容层 '$compat_cmd' 都不存在"
                echo "[quro-compat] 尝试通过 apk 安装..."
                apk add --no-cache "$orig_cmd" 2>/dev/null && exec "$orig_cmd" "$@"
                echo "[quro-compat] 安装失败，命令 '$cmd' 不存在"
                exit 127
            fi
        fi
    done
    
    # 3. 如果不在映射表中，提示命令不存在
    echo "sh: $cmd: command not found"
    exit 127
}

# ═══════════════════════════════════════════════════════════
# 安装命令路由器
# ═══════════════════════════════════════════════════════════
install_router() {
    local bin_dir="/usr/local/bin"
    mkdir -p "$bin_dir"
    
    # 为每个需要路由的命令创建包装器
    echo "$CMD_MAP" | while IFS='|' read -r orig_cmd compat_cmd conv_func; do
        [ -z "$orig_cmd" ] && continue
        
        cat > "$bin_dir/$orig_cmd" << ROUTER_EOF
#!/bin/sh
# QuroTerm 命令路由器: $orig_cmd
# 优先执行原生命令，不存在时转发到兼容层

# 加载路由函数
. /usr/local/lib/quro-cmd-router.sh 2>/dev/null || true

# 检查原生命令是否存在
if command -v "$orig_cmd" >/dev/null 2>&1 && [ "\$(readlink -f \$(command -v $orig_cmd) 2>/dev/null)" != "/usr/local/bin/$orig_cmd" ]; then
    exec "$orig_cmd" "\$@"
fi

# 执行路由
route_command "$orig_cmd" "\$@"
ROUTER_EOF
        chmod +x "$bin_dir/$orig_cmd"
    done
    
    echo "[quro-cmd-router] 命令路由器已安装到 $bin_dir"
}

# ═══════════════════════════════════════════════════════════
# 执行
# ═══════════════════════════════════════════════════════════
if [ "$1" = "--install" ]; then
    install_router
elif [ "$1" = "--route" ]; then
    shift
    route_command "$@"
else
    # 直接执行路由
    route_command "$@"
fi
