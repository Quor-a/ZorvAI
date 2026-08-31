#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fetch_vm_assets.py — 准备 ZorvAI VM 终端所需的重资产到 app/src/main/assets/vm/

资产清单（缺一则对应 VM 后端不可用，终端自动回退 proot）：
  - qemu-system-aarch64 : Android aarch64 静态链接 QEMU 系统模拟（TCG）
  - Image               : aarch64 Linux 内核（virt 机型，含 virtio 驱动）
  - disk.qcow2          : 含完整 Linux 根文件系统的磁盘镜像（Alpine/Ubuntu Cloud）
  - vm_payload.apk      : (可选) AVF/pKVM 用 VM payload

用法：
  python3 fetch_vm_assets.py --mirror <URL基址> [--out app/src/main/assets/vm] [--dry-run]

mirror 目录下需存在上述同名文件。默认 --dry-run 仅打印缺失项，不下载。
不内置任何外部下载源——镜像基址由调用方提供，避免不可控二进制进入发布包。
"""
import argparse
import os
import sys
import urllib.request

ASSETS = [
    ("qemu-system-aarch64", "QEMU 系统模拟器 (aarch64 静态链接)"),
    ("Image", "Linux 内核 (virt 机型)"),
    ("disk.qcow2", "完整 Linux 根文件系统磁盘镜像"),
    ("vm_payload.apk", "AVF/pKVM VM payload (可选)"),
]


def human(n: int) -> str:
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f}{u}"
        n /= 1024
    return f"{n:.0f}TB"


def main() -> int:
    ap = argparse.ArgumentParser(description="Fetch ZorvAI VM terminal assets")
    ap.add_argument("--mirror", required=True, help="资产镜像基址，如 https://example.com/vm")
    ap.add_argument("--out", default="app/src/main/assets/vm", help="输出目录")
    ap.add_argument("--dry-run", action="store_true", help="仅检查，不下载")
    args = ap.parse_args()

    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    base = args.mirror.rstrip("/")

    missing = []
    for name, desc in ASSETS:
        dst = os.path.join(out_dir, name)
        if os.path.exists(dst):
            size = os.path.getsize(dst)
            print(f"[已存在] {name:24s} {human(size):>8s}  ({desc})")
        else:
            missing.append((name, desc))
            print(f"[缺失]   {name:24s}  ({desc})")

    if not missing:
        print("\n✅ 全部 VM 资产就绪，重新构建即可启用 VM 终端。")
        return 0

    if args.dry_run:
        print(f"\n⚠ {len(missing)} 个资产缺失。去掉 --dry-run 并确认 mirror 可用后执行下载。")
        return 0

    print(f"\n开始从 {base} 下载 {len(missing)} 个资产 ...")
    for name, desc in missing:
        url = f"{base}/{name}"
        dst = os.path.join(out_dir, name)
        try:
            print(f"  ↓ {name} ...", end=" ", flush=True)
            urllib.request.urlretrieve(url, dst)
            os.chmod(dst, 0o755)
            print(human(os.path.getsize(dst)))
        except Exception as e:  # noqa: BLE001
            print(f"失败: {e}")
            if os.path.exists(dst):
                os.remove(dst)
            print("  ⚠ 下载失败，请检查 mirror 路径/网络；缺失资产时终端将回退 proot。")
            return 1

    print("\n✅ VM 资产下载完成，重新 :app:assembleFullRelease 即可启用 VM 终端。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
