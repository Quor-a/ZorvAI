# VM 终端资产（Kalidroid 思路：pKVM / AVF / QEMU）

本目录的 4 个二进制资产**不在发布包内**（数百 MB，且需真机验证），由构建机按下方链接拉取后重新打包激活。
代码层已就绪：`QuroVmEnv` 三级探测（AVF → QEMU → proot），资产到位即自动启用 VM 真内核，缺失则两窗格都回退 proot，终端始终可用。

## 资产清单（严格对应 `QuroVmEnv` 路径）

| 资产 | 期望文件名 | 用途 |
|------|-----------|------|
| QEMU 二进制 | `qemu-system-aarch64` | TCG 软件虚拟化控制台（无 AVF 时回退） |
| Linux 内核 | `Image`（Alpine 用 `vmlinuz-virt`） | `-kernel` 启动 guest |
| initramfs | `initramfs-virt` | 含 9p 模块，`-initrd` 注入，挂 9p rootfs 为根 |
| 根文件系统 | `rootfs.tar.gz` | 完整 Linux rootfs，运行时解压到 `files/vm/rootfs`，经 virtio-9p 挂为 guest 根 |

> 放置位置：`app/src/main/assets/vm/{qemu-system-aarch64,Image,initramfs-virt,rootfs.tar.gz}`
> 重新构建：`./gradlew :app:assembleFullRelease`

## 已验证可下载链接（2026-08-31）

仓库构建机若在国内，优先用阿里云镜像（已实测 206 可达）：

- **rootfs**：`https://mirrors.aliyun.com/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz`
- **内核**：`https://mirrors.aliyun.com/alpine/v3.20/releases/aarch64/netboot/vmlinuz-virt`
- **initramfs**：`https://mirrors.aliyun.com/alpine/v3.20/releases/aarch64/netboot/initramfs-virt`
- **QEMU 二进制**：`https://mirrors.aliyun.com/termux/termux-main/pool/main/q/qemu-system-aarch64-headless/qemu-system-aarch64-headless_1%3A11.0.3_aarch64.deb`

> 官方 CDN（dl-cdn.alpinelinux.org / deb.debian.org / cloud.debian.org）在多数国内网络被墙，请勿直接用。

## 启用 VM 的注意事项（重要）

1. **QEMU 必须带运行库**：Termux 的 `qemu-system-aarch64-headless.deb` 动态链接 glib / pixman / zlib / libfdt 等。
   直接拷二进制会在 Android 上因缺 `.so` 秒退。两种可行做法：
   - 用 `scripts/fetch_vm_assets.py` 一并下载 qemu deb + 其依赖 deb（glib / pixman …），解包后把 `qemu-system-aarch64` 与依赖 `.so` 一起放入 `assets/vm/`，构建时随 APK 进 `lib/`；或
   - 用**静态链接**的 qemu（如自行 NDK 编译 `--static`，或社区静态构建），单文件即可。
2. **AVF/pKVM 优先**：若设备已开启 pKVM（Android 14+ 且 Virt 特性可用），无需 qemu，只需 `vm_payload.apk`（microdroid payload）即可走 `VirtualizationService`。多数消费级手机未开 pKVM，`start()` 会失败并自动降级 QEMU/proot。
3. **真机验证**：装好 VM 资产后，左窗格徽章显示 `VM/Linux` 即代表真内核已起；若起不来，诊断日志在 `Android/data/<pkg>/files/vm/quro_qemu_diag.log`，用文件管理器取走即可（无需 adb）。

## fetch 脚本

`scripts/fetch_vm_assets.py` 支持 `--mirror <基址>` 指定镜像源、`--out` 指定输出目录、`--dry-run` 仅检查缺失项。
默认 dry-run，不会从不可控外部源自动下载；镜像基址由调用方显式传入。
