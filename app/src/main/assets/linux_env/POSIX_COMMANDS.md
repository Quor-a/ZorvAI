# ZorvAI 跨平台通用命令清单

## POSIX 标准命令（IEEE Std 1003.1）

以下命令在所有 Unix-like 系统上都存在且行为一致：
- Android Termux
- Ubuntu/Debian
- CentOS/RHEL
- Arch Linux
- macOS
- Alpine Linux
- FreeBSD

---

## 核心 Shell

| 命令 | 说明 | POSIX |
|------|------|-------|
| sh | POSIX shell | ✅ |
| bash | Bourne Again Shell | 大多数预装 |
| dash | 轻量 POSIX shell | Debian/Ubuntu 默认 |
| zsh | Z Shell | 可选 |

## 文件操作

| 命令 | 说明 | POSIX |
|------|------|-------|
| ls | 列出目录内容 | ✅ |
| cp | 复制文件 | ✅ |
| mv | 移动/重命名文件 | ✅ |
| rm | 删除文件 | ✅ |
| mkdir | 创建目录 | ✅ |
| rmdir | 删除空目录 | ✅ |
| touch | 创建空文件/更新时间戳 | ✅ |
| ln | 创建符号链接 | ✅ |
| chmod | 修改文件权限 | ✅ |
| chown | 修改文件所有者 | ✅ |
| chgrp | 修改文件所属组 | ✅ |
| find | 查找文件 | ✅ |
| xargs | 构建参数列表执行命令 | ✅ |
| file | 识别文件类型 | 大多数预装 |
| stat | 显示文件状态 | 大多数预装 |
| du | 显示磁盘使用 | ✅ |
| df | 显示磁盘空间 | ✅ |
| tree | 显示目录树结构 | 可选 |
| less | 分页查看文件 | ✅ |
| more | 分页查看文件（旧） | ✅ |
| cat | 显示文件内容 | ✅ |
| head | 显示文件开头 | ✅ |
| tail | 显示文件结尾 | ✅ |
| readlink | 读取符号链接 | 大多数预装 |
| realpath | 显示绝对路径 | 大多数预装 |
| basename | 去除路径前缀 | ✅ |
| dirname | 去除文件名 | ✅ |

## 文本处理

| 命令 | 说明 | POSIX |
|------|------|-------|
| grep | 文本搜索 | ✅ |
| sed | 流编辑器 | ✅ |
| awk | 文本处理语言 | ✅ |
| cut | 按列截取文本 | ✅ |
| sort | 排序 | ✅ |
| uniq | 去重 | ✅ |
| tr | 字符转换 | ✅ |
| wc | 统计字数/行数 | ✅ |
| diff | 比较文件差异 | ✅ |
| paste | 按行合并文件 | ✅ |
| join | 按共同字段合并 | ✅ |
| tee | 同时输出到屏幕和文件 | ✅ |
| nl | 显示行号 | 大多数预装 |
| expand | 将制表符转为空格 | 大多数预装 |
| unexpand | 将空格转为制表符 | 大大多数预装 |
| dos2unix | Windows→Unix 换行符转换 | 可选 |
| unix2dos | Unix→Windows 换行符转换 | 可选 |
| jq | JSON 处理器 | 可选 |
| yq | YAML 处理器 | 可选 |
| xmlstarlet | XML 处理器 | 可选 |

## 进程管理

| 命令 | 说明 | POSIX |
|------|------|-------|
| ps | 显示进程 | ✅ |
| kill | 终止进程 | ✅ |
| nice | 以低优先级运行 | ✅ |
| nohup | 忽略挂起信号运行 | ✅ |
| sleep | 暂停执行 | ✅ |
| timeout | 限制命令执行时间 | 大多数预装 |
| htop | 交互式进程查看器 | 可选 |
| top | 简单进程查看器 | ✅ |
| pgrep | 按名称查找进程 | 大多数预装 |
| pkill | 按名称终止进程 | 大多数预装 |

## 系统信息

| 命令 | 说明 | POSIX |
|------|------|-------|
| uname | 显示系统信息 | ✅ |
| date | 显示/设置日期 | ✅ |
| hostname | 显示主机名 | ✅ |
| id | 显示用户/组 ID | ✅ |
| whoami | 显示当前用户 | ✅ |
| w | 显示登录用户 | 大多数预装 |
| uptime | 显示运行时间 | ✅ |
| free | 显示内存使用 | 大多数预装 |
| lscpu | 显示 CPU 信息 | 大多数预装 |
| lsblk | 显示块设备 | 大多数预装 |
| lspci | 显示 PCI 设备 | 可选 |
| lsusb | 显示 USB 设备 | 可选 |
| lsmod | 显示内核模块 | 大多数预装 |
| dmesg | 显示内核消息 | 大多数预装 |

## 归档压缩

| 命令 | 说明 | POSIX |
|------|------|-------|
| tar | 归档工具 | ✅ |
| gzip | gzip 压缩 | ✅ |
| gunzip | gzip 解压 | ✅ |
| bzip2 | bzip2 压缩 | 大多数预装 |
| bunzip2 | bzip2 解压 | 大多数预装 |
| xz | xz 压缩 | 大多数预装 |
| unzip | zip 解压 | 大多数预装 |
| zip | zip 压缩 | 大多数预装 |
| pigz | 并行 gzip | 可选 |
| lz4 | LZ4 压缩 | 可选 |
| zstd | Zstandard 压缩 | 可选 |
| 7z | 7-Zip 压缩 | 可选 |
| rar | RAR 压缩 | 可选 |
| unrar | RAR 解压 | 可选 |

## 网络工具

| 命令 | 说明 | POSIX |
|------|------|-------|
| curl | HTTP 客户端 | 大多数预装 |
| wget | 下载工具 | 大多数预装 |
| ssh | SSH 客户端 | 大多数预装 |
| scp | 安全复制 | 大多数预装 |
| rsync | 远程同步 | 大多数预装 |
| nc (netcat) | 网络工具 | 大多数预装 |
| ncat | 增强版 netcat | 可选 |
| socat | 多功能网络工具 | 可选 |
| traceroute | 路由追踪 | 大多数预装 |
| ping | 网络连通测试 | ✅ |
| dig | DNS 查询 | 可选 |
| nslookup | DNS 查询（旧） | 大多数预装 |
| host | DNS 查询（简） | 大多数预装 |
| ifconfig | 网络接口配置 | 大多数预装 |
| ip | 网络配置（iproute2） | 大多数预装 |
| ss | 套接字统计 | 大多数预装 |
| netstat | 网络统计（旧） | 大多数预装 |
| arp | ARP 表 | 大多数预装 |
| route | 路由表 | 大多数预装 |
| whois | WHOIS 查询 | 可选 |
| nmap | 网络扫描 | 可选 |

## 编辑器

| 命令 | 说明 | POSIX |
|------|------|-------|
| vi | POSIX 文本编辑器 | ✅ |
| vim | 改进版 vi | 大多数预装 |
| nano | 简单文本编辑器 | 大多数预装 |
| ed | 行编辑器 | ✅ |

## 开发工具

| 命令 | 说明 | POSIX |
|------|------|-------|
| make | 构建工具 | ✅ |
| gcc | GNU C 编译器 | 大多数预装 |
| g++ | GNU C++ 编译器 | 大多数预装 |
| gdb | GNU 调试器 | 可选 |
| strace | 系统调用跟踪 | 可选 |
| ltrace | 库调用跟踪 | 可选 |
| objdump | 目标文件反汇编 | 大多数预装 |
| nm | 符号列表 | 大多数预装 |
| ar | 归档工具 | 大多数预装 |
| ld | 链接器 | 大多数预装 |
| as | 汇编器 | 大多数预装 |
| pkg-config | 库配置工具 | 可选 |
| cmake | 跨平台构建系统 | 可选 |
| git | 版本控制 | 大多数预装 |
| autoconf | 自动配置 | 可选 |
| automake | 自动 Makefile | 可选 |
| libtool | 库构建工具 | 可选 |
| ctags | 代码索引 | 可选 |
| cscope | 代码浏览器 | 可选 |

## 运行时环境

| 命令 | 说明 | 平台 |
|------|------|------|
| python3 | Python 解释器 | 大多数预装 |
| pip3 | Python 包管理器 | 可选 |
| node | Node.js 运行时 | 可选 |
| npm | Node.js 包管理器 | 可选 |
| go | Go 编译器 | 可选 |
| java | Java 运行时 | 可选 |
| javac | Java 编译器 | 可选 |
| ruby | Ruby 解释器 | 可选 |
| perl | Perl 解释器 | 可选 |
| php | PHP 解释器 | 可选 |
| lua | Lua 解释器 | 可选 |
| rustc | Rust 编译器 | 可选 |
| cargo | Rust 包管理器 | 可选 |

## 容器/虚拟化

| 命令 | 说明 | 平台 |
|------|------|------|
| docker | Docker 客户端 | 可选 |
| podman | Podman 容器引擎 | 可选 |
| kubectl | Kubernetes CLI | 可选 |

---

## Alpine Linux 包映射

| 命令 | Alpine 包名 |
|------|-------------|
| bash | bash |
| coreutils | coreutils |
| grep | grep |
| sed | sed |
| gawk | gawk |
| findutils | findutils |
| less | less |
| tree | tree |
| htop | htop |
| curl | curl |
| wget | wget |
| openssh-client | openssh-client |
| git | git |
| make | make |
| gcc | gcc |
| g++ | gcc |
| cmake | cmake |
| python3 | python3 |
| node | nodejs |
| npm | npm |
| vim | vim |
| nano | nano |
| file | file |
| p7zip | p7zip |
| unrar | unrar |
| zstd | zstd |
| pigz | pigz |
| net-tools | net-tools |
| iproute2 | iproute2 |
| traceroute | traceroute |
| bind-tools | bind-tools |
| nmap-ncat | nmap-ncat |
| socat | socat |
| whois | whois |
| gdb | gdb |
| strace | strace |
| binutils | binutils |
| pciutils | pciutils |
| usbutils | usbutils |
| util-linux | lscpu, lsblk, etc. |
| procps | procps |
| dos2unix | dos2unix |
| jq | jq |
| tmux | tmux |
| screen | screen |

---

## 使用说明

1. **POSIX 命令**：所有系统都支持，无需额外安装
2. **预装命令**：大多数发行版默认包含
3. **可选命令**：需要通过包管理器安装（apk/apt-get/pkg/dnf/pacman）
4. **平台特有**：仅在特定平台可用（如 Docker、kubectl）
