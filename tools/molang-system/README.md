# 墨语言自举的系统（ZorvAI 集成形态）

这是墨语言（v1.174.0）**自举出来的那套系统**，整理成 ZorvAI 自己的结构，可直接构建与运行。

## 组成

| 组件 | 说明 |
|---|---|
| `src/kernel64.mo` | ARM64 **裸机内核**。`mo2x kern-aarch64` 后端 → freestanding C → `zig cc -target aarch64-freestanding`。输出走 PL011 UART（0x09000000 MMIO），**不用系统调用**（它自己就是提供系统调用的那一层） |
| `src/uspace2.mo` | **完整用户空间**。`mo2x cfs-aarch64` → freestanding C → `zig cc -target aarch64-linux-musl -static -nostdlib`。含层次化 VFS、24 条内建命令的 shell、环境变量与 `$?`、包管理（版本比较 / 依赖拓扑与环检测 / **签名** / pack-unpack / 升级 / 拒绝降级）、状态序列化写盘、跨运行 restore、12 个互斥负数码 |
| `src/uspace.mo` | 最小用户空间（uspace2 的前身，7 条命令）——保留作对照 |
| `src/boot/` | 内核入口汇编 `entry64.S` 与链接脚本 `linker64.ld`（加载基址 0x40000000 = QEMU virt RAM） |

## 三层关系（勿混）

```
proot（用户态模拟器，与 Ubuntu 无必然关系）
  └── 墨语言自举的系统（kernel64 + uspace2）
        └── 墨语言程序（由 uspace2 的包管理装载执行）
```

- **dsh 与此无关**：dsh 是宿主 app 域的 Node 程序，不在这条链上。
- **不需要 Ubuntu**：uspace2 自带 VFS 与 shell，proot 只需给它一个能落地的目录。

## 构建（设备上，ZorvAI 墨语言页 → 系统镜像）

```
mo2x(x86_64, 经 box64)  →  freestanding C  →  zig(aarch64, 静态)  →  ELF
```

- 工具链 moc/mo2x 是 x86_64 ELF：设备是 arm64，所以用 **box64**（NDK 交叉编译的
  aarch64 版）执行它们 —— arm64 内核不能直接 exec x86_64 程序。
- zig 是 AArch64 静态二进制，proot 直接 exec。

## 产物

```
build/rootfs/          proot 直接用的 rootfs
build/molang-system.iso  可分发的 ISO9660 镜像（同源）
```
