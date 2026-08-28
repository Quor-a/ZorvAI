#!/bin/sh
# CMS v2 bootstrap — universal: auto-detects ALL available package managers
# (apk, apt-get, pkg, dnf, yum, pacman, zypper, xbps, nix, swupd)
# and installs the full terminal command set through every one found.
# Idempotent: safe to re-run. Marker .bootstrap.done written on success.

BOOT_DIR=$(cd "$(dirname "$0")" && pwd)

# ═══════════════════════════════════════════════════════════
# DNS 配置（所有路径共享）
# ═══════════════════════════════════════════════════════════
configure_dns() {
    if [ ! -f /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
        mkdir -p /etc
        cat > /etc/resolv.conf 2>/dev/null << 'DNS' || {
            echo "nameserver 8.8.8.8" > /etc/resolv.conf
            echo "nameserver 8.8.4.4" >> /etc/resolv.conf
            echo "nameserver 223.5.5.5" >> /etc/resolv.conf
        }
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 114.114.114.114
nameserver 223.5.5.5
nameserver 1.1.1.1
nameserver 9.9.9.9
DNS
        echo "[cms-bootstrap] ✅ DNS configured"
    fi
}

configure_dns

# ═══════════════════════════════════════════════════════════
# 检测所有可用的包管理器
# ═══════════════════════════════════════════════════════════
HAS_APK=0; HAS_APT=0; HAS_PKG=0; HAS_DNF=0; HAS_YUM=0; HAS_PACMAN=0; HAS_ZYPPER=0; HAS_XBPS=0; HAS_NIX=0; HAS_SWUPD=0
command -v apk     >/dev/null 2>&1 && HAS_APK=1
command -v apt-get >/dev/null 2>&1 && HAS_APT=1
command -v pkg     >/dev/null 2>&1 && HAS_PKG=1
command -v dnf     >/dev/null 2>&1 && HAS_DNF=1
command -v yum     >/dev/null 2>&1 && HAS_YUM=1
command -v pacman  >/dev/null 2>&1 && HAS_PACMAN=1
command -v zypper  >/dev/null 2>&1 && HAS_ZYPPER=1
command -v xbps-install >/dev/null 2>&1 && HAS_XBPS=1
command -v nix-env >/dev/null 2>&1 && HAS_NIX=1
command -v swupd   >/dev/null 2>&1 && HAS_SWUPD=1

PM_COUNT=$((HAS_APK+HAS_APT+HAS_PKG+HAS_DNF+HAS_YUM+HAS_PACMAN+HAS_ZYPPER+HAS_XBPS+HAS_NIX+HAS_SWUPD))
echo "[cms-bootstrap] 🔍 Detected $PM_COUNT package managers:"
[ $HAS_APK    -eq 1 ] && echo "  ✅ apk (Alpine)"
[ $HAS_APT    -eq 1 ] && echo "  ✅ apt-get (Debian/Ubuntu)"
[ $HAS_PKG    -eq 1 ] && echo "  ✅ pkg (Termux)"
[ $HAS_DNF    -eq 1 ] && echo "  ✅ dnf (Fedora/RHEL)"
[ $HAS_YUM    -eq 1 ] && echo "  ✅ yum (CentOS/RHEL)"
[ $HAS_PACMAN -eq 1 ] && echo "  ✅ pacman (Arch/Manjaro)"
[ $HAS_ZYPPER -eq 1 ] && echo "  ✅ zypper (openSUSE)"
[ $HAS_XBPS   -eq 1 ] && echo "  ✅ xbps (Void Linux)"
[ $HAS_NIX    -eq 1 ] && echo "  ✅ nix (NixOS)"
[ $HAS_SWUPD  -eq 1 ] && echo "  ✅ swupd (Clear Linux)"

if [ $PM_COUNT -eq 0 ]; then
    echo "[cms-bootstrap] ❌ No package manager found, cannot install commands"
    echo "[cms-bootstrap] ⚠️ Available shell commands are limited to built-in/PATH"
    touch "$BOOT_DIR/.bootstrap.done"
    exit 1
fi

# ═══════════════════════════════════════════════════════════
# 通用安装函数：尝试所有可用包管理器
# ═══════════════════════════════════════════════════════════
try_install() {
    local cmd_name="$1"
    shift
    # 如果命令已存在，跳过
    command -v "$cmd_name" >/dev/null 2>&1 && return 0

    # 依次尝试每个包管理器
    for pkg_name in "$@"; do
        if [ $HAS_APK -eq 1 ]; then
            apk add --no-cache "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_APT -eq 1 ]; then
            apt-get install -y --no-install-recommends "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_PKG -eq 1 ]; then
            pkg install -y "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_DNF -eq 1 ]; then
            dnf install -y "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_YUM -eq 1 ]; then
            yum install -y "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_PACMAN -eq 1 ]; then
            pacman -S --noconfirm "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_ZYPPER -eq 1 ]; then
            zypper install -y "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_XBPS -eq 1 ]; then
            xbps-install -y "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_NIX -eq 1 ]; then
            nix-env -iA nixpkgs."$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
        if [ $HAS_SWUPD -eq 1 ]; then
            swupd bundle-add "$pkg_name" >/dev/null 2>&1 && command -v "$cmd_name" >/dev/null 2>&1 && return 0
        fi
    done
    return 1
}

# 也尝试同时安装多个包名（适配不同包管理器的包命名差异）
batch_install() {
    for cmd in "$@"; do
        IFS='=' read -r cmd_name pkgs <<< "$cmd"
        try_install "$cmd_name" $pkgs >/dev/null 2>&1
    done
}

# ═══════════════════════════════════════════════════════════
# 锁检测与修复（apt/dpkg 特有）
# ═══════════════════════════════════════════════════════════
if [ $HAS_APT -eq 1 ]; then
    echo "[cms-bootstrap] 🔓 Fixing apt/dpkg locks..."
    for lk in /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock; do
        [ -e "$lk" ] && rm -f "$lk" 2>/dev/null || true
    done
    dpkg --configure -a 2>/dev/null || true

    # 中和服务管理器（proot 无 init）
    mkdir -p /usr/local/sbin
    for b in start-stop-daemon invoke-rc.d update-rc.d service systemctl; do
        printf '#!/bin/sh\nexit 0\n' > "/usr/local/sbin/$b" 2>/dev/null
        chmod +x "/usr/local/sbin/$b" 2>/dev/null || true
    done
fi

# ═══════════════════════════════════════════════════════════
# 锁检测与修复（apk 特有）
# ═══════════════════════════════════════════════════════════
[ $HAS_APK -eq 1 ] && rm -f /var/cache/apk/lock 2>/dev/null

# ═══════════════════════════════════════════════════════════
# 更新所有包管理器索引
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] 📦 Updating package indexes..."
[ $HAS_APK    -eq 1 ] && apk update 2>&1 | tail -2
[ $HAS_APT    -eq 1 ] && apt-get update 2>&1 | tail -2
[ $HAS_PKG    -eq 1 ] && pkg update -y 2>&1 | tail -2
[ $HAS_DNF    -eq 1 ] && dnf makecache 2>&1 | tail -2
[ $HAS_YUM    -eq 1 ] && yum makecache 2>&1 | tail -2
[ $HAS_PACMAN -eq 1 ] && pacman -Sy 2>&1 | tail -2
[ $HAS_ZYPPER -eq 1 ] && zypper refresh 2>&1 | tail -2
[ $HAS_XBPS   -eq 1 ] && xbps-install -Su 2>&1 | tail -2

# ═══════════════════════════════════════════════════════════
# 安装完整终端命令集
# ═══════════════════════════════════════════════════════════

# ── 核心 Shell & 基础命令 ──
echo "[cms-bootstrap] 🐚 Installing core shell & basic commands..."
batch_install \
    "bash=bash bash-static" \
    "sh=busybox dash" \
    "ls=coreutils util-linux busybox" \
    "cp=coreutils busybox" \
    "mv=coreutils busybox" \
    "rm=coreutils busybox" \
    "mkdir=coreutils busybox" \
    "rmdir=coreutils busybox" \
    "touch=coreutils busybox" \
    "chmod=coreutils busybox" \
    "chown=coreutils busybox" \
    "chgrp=coreutils busybox" \
    "ln=coreutils busybox" \
    "readlink=coreutils busybox" \
    "stat=coreutils busybox" \
    "id=coreutils busybox" \
    "whoami=coreutils busybox" \
    "who=w-ng procps" \
    "w=w-ng procps" \
    "uname=coreutils busybox" \
    "hostname=coreutils net-tools busybox" \
    "date=coreutils busybox" \
    "sleep=coreutils busybox" \
    "kill=procps psmisc busybox" \
    "nice=coreutils busybox" \
    "renice=sysutils coreutils" \
    "test=coreutils busybox" \
    "true=coreutils busybox" \
    "false=coreutils busybox" \
    "yes=coreutils busybox" \
    "seq=coreutils busybox" \
    "dirname=coreutils busybox" \
    "basename=coreutils busybox" \
    "base64=coreutils busybox" \
    "env=coreutils busybox" \
    "printenv=coreutils busybox" \
    "expr=coreutils busybox" \
    "tee=coreutils busybox" \
    "mktemp=coreutils busybox" \
    "realpath=coreutils busybox"

# ── 文件查找与操作 ──
echo "[cms-bootstrap] 📁 Installing file tools..."
batch_install \
    "find=findutils busybox" \
    "xargs=findutils busybox" \
    "locate=plocate mlocate" \
    "updatedb=plocate mlocate" \
    "tree=tree" \
    "file=file" \
    "less=less" \
    "more=util-linux" \
    "head=coreutils busybox" \
    "tail=coreutils busybox" \
    "wc=coreutils busybox" \
    "sort=coreutils busybox" \
    "uniq=coreutils busybox" \
    "cut=coreutils busybox" \
    "tr=coreutils busybox" \
    "dd=coreutils busybox" \
    "od=coreutils busybox" \
    "hexdump=bsdmainutils busybox" \
    "xxd=xxd vim" \
    "split=coreutils busybox" \
    "csplit=coreutils" \
    "cat=coreutils busybox" \
    "tac=coreutils busybox" \
    "rev=util-linux" \
    "paste=coreutils busybox" \
    "join=coreutils busybox" \
    "comm=coreutils busybox" \
    "diff=diffutils busybox" \
    "colordiff=colordiff" \
    "patch=patch" \
    "cmp=diffutils busybox" \
    "fold=coreutils busybox" \
    "expand=coreutils busybox" \
    "unexpand=coreutils busybox" \
    "fmt=textutils" \
    "column=bsdextrautils util-linux" \
    "pr=coreutils" \
    "paste=coreutils" \
    "sdisk=util-linux fdisk" \
    "lsblk=util-linux" \
    "losetup=util-linux"

# ── 压缩与归档 ──
echo "[cms-bootstrap] 📦 Installing compression tools..."
batch_install \
    "tar=libarchive bsdtar busybox" \
    "gzip=gzip busybox" \
    "gunzip=gzip busybox" \
    "zcat=gzip busybox" \
    "bzip2=bzip2 busybox" \
    "bunzip2=bzip2" \
    "xz=xz busybox" \
    "unxz=xz" \
    "lz4=lz4" \
    "zstd=zstd" \
    "lzop=lzop" \
    "pigz=pigz" \
    "unpigz=pigz" \
    "7z=p7zip p7zip-full" \
    "unzip=unzip busybox" \
    "zip=zip" \
    "unrar=unrar unrars" \
    "rar=rar" \
    "cpio=cpio busybox"

# ── 文本编辑器 ──
echo "[cms-bootstrap] 📝 Installing editors..."
batch_install \
    "nano=nano" \
    "vim=vim vim-runtime neovim" \
    "nvim=neovim vim" \
    "vi=busybox vim"

# ── 文本处理 ──
echo "[cms-bootstrap] 📝 Installing text processing tools..."
batch_install \
    "sed=busybox sed" \
    "awk=gawk busybox" \
    "grep=busybox grep pcre2" \
    "egrep=busybox grep" \
    "fgrep=busybox grep" \
    "rg=ripgrep" \
    "ag=silver-searcher" \
    "jq=jq" \
    "yq=yq" \
    "xmlstarlet=xmlstarlet" \
    "xmllint=libxml2" \
    "dos2unix=dos2unix" \
    "unix2dos=dos2unix" \
    "recode=recode" \
    "iconv=glibc gnu-libiconv" \
    "gettext= gettext-runtime" \
    "envsubst=gettext" \
    "column=bsdextrautils util-linux"

# ── 进程管理 ──
echo "[cms-bootstrap] ⚙️ Installing process tools..."
batch_install \
    "ps=procps busybox" \
    "top=procps busybox" \
    "htop=htop" \
    "atop=atop" \
    "glances=glances" \
    "killall=psmisc busybox" \
    "pkill=procps busybox" \
    "pgrep=procps busybox" \
    "pidof=procps psmisc busybox" \
    "pstree=psmisc" \
    "nohup=coreutils busybox" \
    "timeout=coreutils busybox" \
    "time=busybox" \
    "watch=procps" \
    "strace=strace" \
    "ltrace=ltrace" \
    "lsof=lsof" \
    "fuser=psmisc psmisc" \
    "tmux=tmux" \
    "screen=screen"

# ── 用户与权限 ──
echo "[cms-bootstrap] 👤 Installing user tools..."
batch_install \
    "sudo=sudo" \
    "su=util-linux busybox" \
    "passwd=util-linux busybox" \
    "useradd=shadow util-linux" \
    "userdel=shadow util-linux" \
    "groupadd=shadow util-linux" \
    "groups=coreutils busybox" \
    "last=util-linux" \
    "lastlog=util-linux" \
    "faillog=util-linux" \
    "mesg=util-linux busybox" \
    "write=util-linux"

# ── 网络工具 ──
echo "[cms-bootstrap] 🌐 Installing network tools..."
batch_install \
    "curl=curl" \
    "wget=wget" \
    "ssh=openssh openssh-client" \
    "scp=openssh openssh-client" \
    "sftp=openssh openssh-client" \
    "ssh-keygen=openssh openssh-client" \
    "ssh-agent=openssh openssh-client" \
    "rsync=rsync" \
    "nc=netcat ncat busybox" \
    "ncat=nmap-ncat" \
    "nc.traditional=netcat-openbsd" \
    "socat=socat" \
    "telnet=busybox inetutils" \
    "ping=iputils busybox" \
    "traceroute=traceroute busybox" \
    "tracepath=iproute2" \
    "mtr=mtr" \
    "dig=dnsutils bind-tools" \
    "nslookup=dnsutils busybox" \
    "host=dnsutils bind-tools" \
    "ifconfig=net-tools busybox" \
    "ip=iproute2 busybox" \
    "ss=iproute2 busybox" \
    "netstat=net-tools busybox" \
    "route=net-tools busybox" \
    "arp=net-tools busybox" \
    "arping=iputils" \
    "nmap=nmap" \
    "whois=whois" \
    "nwhois=whois" \
    "ethtool=ethtool" \
    "iw=wireless-tools iw" \
    "iwconfig=wireless-tools" \
    "ip neigh=iproute2" \
    "openvpn=openvpn" \
    "wg=wireguard-tools" \
    "cat=/proc/net/tcp" \
    "ab=apache2-utils" \
    "hey=hey" \
    "httping=httping" \
    "aria2c=aria2" \
    "axel=axel" \
    "proxychains=proxychains-ng"

# ── DNS 工具 ──
echo "[cms-bootstrap] 🌐 Installing DNS tools..."
batch_install \
    "dig=dnsutils bind9-utils bind-tools" \
    "nslookup=net-tools dnsutils busybox" \
    "host=dnsutils bind9-utils" \
    "drill=ldns" \
    "kdig=kdig"

# ── 磁盘与文件系统 ──
echo "[cms-bootstrap] 💾 Installing disk tools..."
batch_install \
    "df=coreutils busybox" \
    "du=coreutils busybox" \
    "fdisk=util-linux busybox" \
    "parted=parted" \
    "lsblk=util-linux" \
    "blkid=util-linux" \
    "mount=util-linux busybox" \
    "umount=util-linux busybox" \
    "fsck=e2fsprogs" \
    "mkfs=e2fsprogs" \
    "mkswap=util-linux" \
    "swapoff=util-linux" \
    "swapon=util-linux" \
    "dd=coreutils busybox" \
    "sync=coreutils busybox" \
    "fallocate=util-linux" \
    "wipefs=util-linux" \
    "lsfd=util-linux" \
    "findmnt=util-linux" \
    "fstrim=util-linux" \
    "hdparm=hdparm" \
    "smartctl=smartmontools" \
    "badblocks=e2fsprogs" \
    "e2fsck=e2fsprogs" \
    "resize2fs=e2fsprogs" \
    "tune2fs=e2fsprogs" \
    "dump=e2fsprogs" \
    "mke2fs=e2fsprogs"

# ── 系统信息 ──
echo "[cms-bootstrap] 🖥️ Installing system info tools..."
batch_install \
    "lscpu=util-linux busybox" \
    "nproc=coreutils busybox" \
    "free=procps busybox" \
    "vmstat=procps busybox" \
    "iostat=sysstat" \
    "mpstat=sysstat" \
    "sar=sysstat" \
    "uptime=coreutils busybox" \
    "dmesg=util-linux busybox" \
    "lsusb=usbutils" \
    "lspci=pciutils" \
    "lspcmos=util-linux" \
    "lshw=lshw" \
    "lshw=hwloc" \
    "dmidecode=dmidecode" \
    "inxi=inxi" \
    "neofetch=neofetch" \
    "fastfetch=fastfetch" \
    "screenfetch=screenfetch" \
    "hwinfo=hwinfo" \
    "sensors=lm-sensors" \
    " turbostat=linux-cpupower" \
    "cpufreq-info=linux-cpupower"

# ── 归档/包提取 ──
echo "[cms-bootstrap] 📦 Installing archive tools..."
batch_install \
    "ar=binutils busybox" \
    "strings=binutils busybox" \
    "nm=binutils busybox" \
    "objdump=binutils busybox" \
    "readelf=binutils busybox" \
    "strip=binutils busybox" \
    "addr2line=binutils" \
    "size=binutils" \
    "elfedit=binutils" \
    "as=binutils gcc-as" \
    "ld=binutils gcc-ld" \
    "ldd=libc busybox" \
    "ldconfig=glibc libc6"

# ── 开发工具 ──
echo "[cms-bootstrap] 🛠️ Installing dev tools..."
batch_install \
    "make=make busybox" \
    "cmake=cmake" \
    "gcc=gcc" \
    "g++=g++ gcc-c++" \
    "cc=gcc" \
    "clang=clang" \
    "clang++=clang" \
    "rustc=rust" \
    "cargo=rust" \
    "go=golang" \
    "git=git" \
    "svn=subversion" \
    "hg=mercurial" \
    "bzr=bzr" \
    "gdb=gdb" \
    "lldb=lldb llvm" \
    "valgrind=valgrind" \
    "pkg-config=pkg-config pkgconf" \
    "autoconf=autoconf" \
    "automake=automake" \
    "libtool=libtool" \
    "ctags=ctags universal-ctags" \
    "cscope=cscope" \
    "etags=emacs-nox emacs" \
    "patchelf=patchelf" \
    "objcopy=binutils" \
    "strip=binutils" \
    "ar=binutils" \
    "nm=binutils"

# ── Python ──
echo "[cms-bootstrap] 🐍 Installing Python..."
batch_install \
    "python3=python3 python python312" \
    "python=python3 python" \
    "pip3=py3-pip python3-pip" \
    "pip=py3-pip python3-pip" \
    "ipython=ipython py3-ipython"

# ── Node.js ──
echo "[cms-bootstrap] 📦 Installing Node.js..."
batch_install \
    "node=nodejs node" \
    "npm=npm" \
    "npx=npm" \
    "yarn=yarn" \
    "pnpm=pnpm" \
    "bun=bun"

# ── Java ──
echo "[cms-bootstrap] ☕ Installing Java..."
batch_install \
    "java=openjdk17-jdk openjdk-17-jdk java-17-openjdk" \
    "javac=openjdk17-jdk openjdk-17-jdk java-17-openjdk" \
    "javap=binutils openjdk" \
    "jstack=openjdk"

# ── Ruby ──
echo "[cms-bootstrap] 💎 Installing Ruby..."
batch_install \
    "ruby=ruby" \
    "gem=ruby" \
    "bundler=ruby-bundler"

# ── Perl ──
echo "[cms-bootstrap] 🐪 Installing Perl..."
batch_install \
    "perl=perl" \
    "cpan=perl" \
    "perldoc=perl-podlators"

# ── PHP ──
echo "[cms-bootstrap] 🐘 Installing PHP..."
batch_install \
    "php=php83 php82 php" \
    "composer=composer php-composer"

# ── Lua ──
echo "[cms-bootstrap] 🌙 Installing Lua..."
batch_install \
    "lua=lua54 lua53 lua" \
    "luarocks=luarocks"

# ── 二进制分析 ──
echo "[cms-bootstrap] 🔬 Installing binary analysis tools..."
batch_install \
    "objdump=binutils" \
    "readelf=binutils" \
    "strings=binutils" \
    "nm=binutils" \
    "size=binutils" \
    "addr2line=binutils" \
    "c++filt=binutils" \
    "elfedit=binutils" \
    "file=file" \
    "ldd=libc6 glibc" \
    "ldconfig=libc6 glibc"

# ── 媒体工具 ──
echo "[cms-bootstrap] 🎵 Installing media tools..."
batch_install \
    "ffmpeg=ffmpeg" \
    "ffprobe=ffmpeg" \
    "sox=sox" \
    "imagemagick=imagemagick" \
    "convert=imagemagick" \
    "identify=imagemagick" \
    "exiftool=perl-image-exiftool"

# ── 数据库工具 ──
echo "[cms-bootstrap] 🗄️ Installing database tools..."
batch_install \
    "sqlite3=sqlite" \
    "mysql=mariadb-client mysql-client" \
    "psql=postgresql-client" \
    "redis-cli=redis" \
    "mongosh=mongodb-tools" \
    "neo4j=neo4j"

# ── 容器/部署工具 ──
echo "[cms-bootstrap] 🐳 Installing container tools..."
batch_install \
    "docker=docker-cli docker" \
    "kubectl=kubectl" \
    "helm=helm" \
    "terraform=terraform" \
    "ansible=ansible" \
    "puppet=bolt puppet" \
    "vagrant=vagrant"

# ── 杂项工具 ──
echo "[cms-bootstrap] 🧰 Installing miscellaneous tools..."
batch_install \
    "bc=bc busybox" \
    "dc=bc busybox" \
    "factor=coreutils" \
    "cal=util-linux busybox" \
    "ncal=util-linux" \
    "clear=ncurses" \
    "reset=ncurses" \
    "screenfetch=screenfetch" \
    "fortune=fortune-mod" \
    "cowsay=cowsay" \
    "figlet=figlet" \
    "toilet=toilet" \
    "lolcat=python-pip" \
    "sl=sl" \
    "cmatrix=cmatrix" \
    "bb=bb" \
    "pv=pv" \
    "progress=progress" \
    "multitail=multitail" \
    "colordiff=colordiff" \
    "tree=tree" \
    "jq=jq" \
    "yq=yq" \
    "xmlstarlet=xmlstarlet" \
    "pup=pup" \
    "htmlq=htmlq" \
    "hjson=hjson" \
    "gron=gron" \
    "bat=bat" \
    "exa=exa" \
    "fd=fd-find fdfind" \
    "fzf=fzf" \
    "ripgrep=ripgrep" \
    "sd=sd" \
    "hexyl=hexyl" \
    "delta=delta" \
    "diff-so-fancy=diff-so-fancy" \
    "tldr=tldr" \
    "howdoi=howdoi" \
    "entr=entr" \
    "watch=watch procps" \
    "ts=ts moreutils" \
    "parallel=parallel" \
    "csvkit=csvkit" \
    "q=textql" \
    "html2text=html2text" \
    "w3m=w3m" \
    "lynx=lynx" \
    "links=links" \
    "surfraw=surfraw"

# ═══════════════════════════════════════════════════════════
# Node.js 官方二进制（包管理器版本可能不可用时的回退）
# ═══════════════════════════════════════════════════════════
if ! command -v node >/dev/null 2>&1; then
    echo "[cms-bootstrap] 📦 Downloading Node.js official binary..."
    ARCH=$(uname -m)
    case "$ARCH" in
        aarch64) NODE_ARCH="linux-arm64" ;;
        armv7l|armhf) NODE_ARCH="linux-armv7l" ;;
        x86_64) NODE_ARCH="linux-x64" ;;
        i*86) NODE_ARCH="linux-x86" ;;
        *) NODE_ARCH="linux-arm64" ;;
    esac
    curl -fsSL "https://npmmirror.com/mirrors/node/v20.19.0/node-v20.19.0-${NODE_ARCH}.tar.xz" -o /tmp/node.tar.xz 2>/dev/null && \
    tar -xf /tmp/node.tar.xz -C /usr/local --strip-components=1 2>/dev/null && \
    rm -f /tmp/node.tar.xz && \
    echo "[cms-bootstrap] ✅ Node.js installed: $(node --version 2>&1)" || \
    echo "[cms-bootstrap] ⚠️ Node.js installation failed"
fi

# ═══════════════════════════════════════════════════════════
# Go 官方二进制（回退）
# ═══════════════════════════════════════════════════════════
if ! command -v go >/dev/null 2>&1; then
    echo "[cms-bootstrap] 📦 Downloading Go official binary..."
    ARCH=$(uname -m)
    case "$ARCH" in
        aarch64) GO_ARCH="arm64" ;;
        armv7l|armhf) GO_ARCH="armv6l" ;;
        x86_64) GO_ARCH="amd64" ;;
        i*86) GO_ARCH="386" ;;
        *) GO_ARCH="arm64" ;;
    esac
    curl -fsSL "https://npmmirror.com/mirrors/golang/go1.23.4.linux-${GO_ARCH}.tar.gz" -o /tmp/go.tar.gz 2>/dev/null && \
    tar -xf /tmp/go.tar.gz -C /usr/local 2>/dev/null && \
    rm -f /tmp/go.tar.gz && \
    echo "[cms-bootstrap] ✅ Go installed: $(/usr/local/go/bin/go version 2>&1)" || \
    echo "[cms-bootstrap] ⚠️ Go installation failed"
fi

# ═══════════════════════════════════════════════════════════
# Rust（回退）
# ═══════════════════════════════════════════════════════════
if ! command -v rustc >/dev/null 2>&1; then
    echo "[cms-bootstrap] 📦 Installing Rust via rustup..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --profile minimal 2>/dev/null
    [ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env" 2>/dev/null
    echo "[cms-bootstrap] ✅ Rust installed: $(rustc --version 2>&1)" || \
    echo "[cms-bootstrap] ⚠️ Rust installation failed"
fi

# ═══════════════════════════════════════════════════════════
# Shell Profile
# ═══════════════════════════════════════════════════════════
echo "[cms-bootstrap] 🔧 Setting up shell profile..."
PROFILE="$HOME/.profile"
[ -f /root/.profile ] && PROFILE="/root/.profile"
touch "$PROFILE" 2>/dev/null
grep -q '/usr/local/go/bin' "$PROFILE" 2>/dev/null || echo 'export PATH=$PATH:/usr/local/go/bin:/usr/local/sbin:/usr/local/bin:$HOME/.cargo/bin' >> "$PROFILE" 2>/dev/null

# ═══════════════════════════════════════════════════════════
# 验证
# ═══════════════════════════════════════════════════════════
echo ""
echo "═══════════════════════════════════════════════════════"
echo "[cms-bootstrap] 🔍 Installation verification"
echo "═══════════════════════════════════════════════════════"

FOUND=0; MISSING=0
check_cmd() {
    if command -v "$1" >/dev/null 2>&1; then
        FOUND=$((FOUND+1))
    else
        MISSING=$((MISSING+1))
        echo "  ❌ $1"
    fi
}

echo "[cms-bootstrap] 📊 Core commands:"
for c in bash sh ls cp mv rm mkdir touch chmod chown ln find xargs sort uniq cut tr head tail cat less more grep sed awk diff patch; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 File & archive:"
for c in tar gzip bzip2 xz unzip zip 7z file tree stat; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Editors:"
for c in nano vim vi; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Text processing:"
for c in jq xxd hexdump; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Process & system:"
for c in ps top htop kill killall lsof strace free uptime uname df du; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Network:"
for c in curl wget ssh scp rsync nc nmap dig nslookup ifconfig ip ss netstat traceroute ping; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Dev tools:"
for c in make cmake gcc g++ clang git python3 node npm go rustc gdb; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Media:"
for c in ffmpeg ffprobe; do check_cmd "$c"; done

echo "[cms-bootstrap] 📊 Extra tools:"
for c in tmux screen jq yq rsync lsof htop; do check_cmd "$c"; done

echo ""
echo "[cms-bootstrap] ════════════════════════════════════════════"
echo "[cms-bootstrap] ✅ Found: $FOUND  |  ❌ Missing: $MISSING"
echo "[cms-bootstrap] 📦 Package managers used: $PM_COUNT"
echo "[cms-bootstrap] ════════════════════════════════════════════"

touch "$BOOT_DIR/.bootstrap.done"
echo "[cms-bootstrap] 🎉 Bootstrap completed at $(date)"
