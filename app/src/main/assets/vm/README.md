# ZorvAI VM 终端资产清单（Kalidroid 思路）

终端已重写为**三级后端**，优先级从高到低：

1. **AVF / pKVM**（Android Virtualization Framework）— 跑**完整 Linux 内核**，近原生性能。
2. **QEMU**（TCG 软件虚拟化）— 无需 KVM/pKVM，几乎全机型可用。
3. **proot**（用户态 Linux，Ubuntu 24.04）— 兜底，无 VM 资产时终端依旧可用。

`core/vm/QuroVmEnv` 在启动终端时探测能力；**资产缺失则自动降级 proot**，不会崩溃。
把下列资产放到本目录（`app/src/main/assets/vm/`）后重新构建，VM 模式即自动激活。

## 资产清单（缺一则对应后端不可用）

| 文件名 | 用途 | 格式/说明 |
|--------|------|-----------|
| `qemu-system-aarch64` | QEMU 系统模拟器（aarch64 静态链接最佳） | ELF 可执行，Android aarch64 |
| `Image` | Linux 内核（virt 机型） | 含 virtio 块/网卡/串口驱动 |
| `disk.qcow2` | 完整 Linux 根文件系统磁盘 | qcow2（或 raw），含 `/sbin/init`、bash、apt 等 |
| `vm_payload.apk` | (可选) AVF/pKVM 用 VM payload | 内置 `vm_config.json` + kernel/rootfs，需 `MANAGE_VIRTUAL_MACHINE` |

> 资产名与 `QuroVmEnv` 里的解析路径一一对应（`findVmBinary` 优先 `nativeLibraryDir` 的
> `libqemu.so / libvm_kernel.so / libvm_disk.so / libavf_vm_payload.so`，缺失再从 `assets/vm/` 解压）。
> 文件名必须严格一致，否则探测判为不可用。

## 推荐获取方式

- **QEMU**：AOSP `external/qemu` 交叉编译，或用预编译的 Android aarch64 静态 QEMU。
- **Kernel `Image`**：`arch/arm64/configs/defconfig` + `CONFIG_VIRTIO*=y`、`CONFIG_SERIAL_AMBA_PL011*=y`，
  目标 `mach-virt`。
- **`disk.qcow2`**：Alpine aarch64（`alpine-virt`）或 Ubuntu Cloud 镜像，`qemu-img convert -f raw -O qcow2`。
- **AVF payload**：参考 AOSP `packages/modules/Virtualization/microdroid`（或自定义完整 Linux payload）。

## 一键拉取（构建机执行）

```bash
python3 scripts/fetch_vm_assets.py --mirror <你的资产镜像基址> \
    --out app/src/main/assets/vm
```

脚本仅下载上表四个文件并校验大小；镜像基址由你提供（不内置任何外部下载源，
避免不可控/不可验证的二进制进入发布包）。下载完成后重新 `:app:assembleFullRelease` 即可。

## 真机验证

1. 安装含资产的 APK，进入终端。
2. 顶栏徽章显示 `VM/Linux` 即表示 VM 后端已接管（否则回退 `Linux`/设备 shell）。
3. 若 VM 启动失败，诊断日志写入 `files/vm/quro_qemu_diag.log` 或 `quro_avf_diag.log`，
   用手机文件管理器（无需 adb）即可取走排查。
