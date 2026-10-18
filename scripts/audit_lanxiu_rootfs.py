#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LANXIU vs Ubuntu rootfs 内容层审计（proot 外来者排查）。只读。
修正：tar 成员名带 './' 前缀，统一归一化后再比对。
"""
import tarfile, os

LANXIU = "D:/Calw OS-project/QuroAI/terminal-core/src/main/assets/lanxiu-aarch64-rootfs-20261017.tar.xz"
UBUNTU = "D:/Calw OS-project/QuroAI/terminal-core/src/main/assets/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"

TEXT_PROBE = [
    "etc/passwd", "etc/group", "etc/profile", "etc/bash.bashrc", "etc/environment",
    "etc/os-release", "etc/lanxiu-release", "etc/ld.so.conf",
    "etc/resolv.conf", "etc/hosts", "etc/hostname",
    "etc/profile.d/lanxiu.sh", "etc/ld.so.conf.d/lanxiu-aarch64.conf",
    "etc/qysudoers", "etc/qyinit.d/hostname.unit", "etc/qyinit.d/qyboot.unit",
    "etc/qyinit.d/sysctl.unit", "etc/qyinit.d/qynet.unit", "etc/qyinit.d/sshd.unit",
]

def norm(name):
    return name[2:] if name.startswith("./") else name

def open_tar(path):
    if not os.path.exists(path):
        print(f"  [缺失] {path}"); return None
    try:
        return tarfile.open(path, "r:xz")
    except Exception as e:
        print(f"  [打开失败] {path}: {e}"); return None

def audit(name, path):
    print("=" * 78)
    print(f"## {name}  ({path})")
    tf = open_tar(path)
    if tf is None: return
    members = tf.getmembers()
    print(f"   成员总数: {len(members)}")
    bynorm = {norm(m.name): m for m in members}

    print("--- 顶层关键项（目录 vs usrmerge 链接）---")
    for top in ["bin", "lib", "sbin", "lib64", "usr", "root", "etc", "dev", "var", "home", "tmp"]:
        if top in bynorm:
            m = bynorm[top]
            if m.issym():
                print(f"   /{top} -> 符号链接 -> {m.linkname}")
            elif m.isdir():
                print(f"   /{top} -> 真实目录 mode={oct(m.mode)} uid={m.uid} gid={m.gid}")
            else:
                print(f"   /{top} -> 文件 mode={oct(m.mode)}")
        else:
            print(f"   /{top} -> [不存在]")

    print("--- 属主分布（tar 元数据，安卓 tar xof 提取不生效）---")
    uidset = {}
    for m in members:
        uidset[m.uid] = uidset.get(m.uid, 0) + 1
    for uid, c in sorted(uidset.items()):
        print(f"   uid={uid}: {c} 个成员")

    print("--- 关键文本文件 ---")
    for rel in TEXT_PROBE:
        if rel in bynorm:
            m = bynorm[rel]
            try:
                data = tf.extractfile(m).read().decode("utf-8", "replace")
            except Exception as e:
                print(f"   {rel}: 读取失败 {e}"); continue
            lines = data.splitlines()
            print(f"   ### {rel} ({len(lines)} 行) ###")
            for ln in lines[:50]:
                print(f"      {ln}")
            if len(lines) > 50:
                print(f"      ... (省略 {len(lines)-50} 行)")
        else:
            print(f"   {rel}: [不存在]")

    # qyinit.d 全部脚本里是否有硬编码设备路径（proot 下不存在的路径）
    print("--- qyinit.d 脚本里的可疑硬编码路径（/dev /sys /run /proc 之外的真实块设备）---")
    suspicious = []
    for rel, m in bynorm.items():
        if rel.startswith("etc/qyinit.d/") and m.isfile():
            try:
                data = tf.extractfile(m).read().decode("utf-8", "replace")
            except Exception:
                continue
            for ln in data.splitlines():
                s = ln.strip()
                if not s or s.startswith("#"):
                    continue
                # 找疑似真实块设备/硬件节点引用
                for kw in ["/dev/block", "/dev/sda", "/dev/mmc", "/dev/nvme", "mount ", "system.img",
                           "/data/", "android", "/vendor/", "boot.img"]:
                    if kw in s.lower():
                        suspicious.append((rel, s)); break
    if suspicious:
        for rel, s in suspicious[:40]:
            print(f"   {rel}: {s[:120]}")
    else:
        print("   未发现明显硬件/块设备硬编码（qyinit.d 在 proot 下不自动运行，影响有限）")

    tf.close()
    print()

if __name__ == "__main__":
    audit("LANXIU (澜岫) rootfs", LANXIU)
    audit("Ubuntu (noble) rootfs", UBUNTU)
