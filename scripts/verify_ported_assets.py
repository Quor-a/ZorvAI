#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验「从小布（OPPO Breeno）包内字节级搬入」的原件没被改过。

## 为什么需要它
`app/src/main/assets/proto/*` 与 `assets/lottie/*` 不是我们写的代码，是从
`Breeno.apk`（com.heytap.speechassist v13.3.5）里逐字节拷出来的**协议契约与动效资源**。
这类文件最容易被无意改坏，而且不会报错：
- git 行尾转换（autocrlf）会悄悄把 LF 改成 CRLF，文件"看起来一样"但 SHA 全变；
- 编辑器"格式化"一下 proto，缩进变了，字段语义没变但原件已经不在了；
- 有人图省事改一行 proto 让它编译过 —— 那就不再是引用，而是自己编的协议。

清单 `app/src/main/assets/PORTED.sha256` 记录了每个原件的 SHA-256 与它在 Breeno.apk 内的原始路径。

## 用法
    python scripts/verify_ported_assets.py                      # 校验工作区文件
    python scripts/verify_ported_assets.py <apk路径>            # 同时校验工作区文件与 APK 内副本
    python scripts/verify_ported_assets.py --apk out.apk        # 校验打包后的 APK 内的 assets

退出码：0 全通过；1 有差异；2 清单缺失。
"""
import hashlib
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
MANIFEST = os.path.join(ASSETS, "PORTED.sha256")


def parse_manifest():
    if not os.path.isfile(MANIFEST):
        print("MISSING 清单不存在: %s" % MANIFEST)
        return None
    out = []
    with open(MANIFEST, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            # <sha256>  <local>  <-  <apkInner>  [## DIFF!]
            parts = line.split("<-")
            if len(parts) != 2:
                continue
            left = parts[0].split()
            if len(left) < 2:
                continue
            sha, local = left[0], left[1]
            inner = parts[1].split()[0] if parts[1].split() else ""
            out.append((sha, local, inner))
    return out


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_workspace(entries):
    ok = fail = 0
    for sha, local, inner in entries:
        p = os.path.join(ASSETS, local.replace("/", os.sep))
        if not os.path.isfile(p):
            print("MISSING  %-38s  (源: %s)" % (local, inner))
            fail += 1
            continue
        got = sha256_of(p)
        if got == sha:
            print("OK       %-38s  %6d B" % (local, os.path.getsize(p)))
            ok += 1
        else:
            print("MODIFIED %-38s  期望 %s 实得 %s" % (local, sha[:16], got[:16]))
            fail += 1
    return ok, fail


def verify_apk(entries, apk_path):
    if not os.path.isfile(apk_path):
        print("APK 不存在: %s" % apk_path)
        return 0, len(entries)
    ok = fail = 0
    with zipfile.ZipFile(apk_path) as z:
        names = set(z.namelist())
        for sha, local, _ in entries:
            entry = "assets/" + local
            if entry not in names:
                print("APK-MISSING %-34s" % entry)
                fail += 1
                continue
            got = hashlib.sha256(z.read(entry)).hexdigest()
            if got == sha:
                ok += 1
            else:
                print("APK-MODIFIED %-33s  期望 %s 实得 %s" % (entry, sha[:16], got[:16]))
                fail += 1
    print("APK 内原件: %d/%d 字节一致" % (ok, ok + fail))
    return ok, fail


def main():
    args = [a for a in sys.argv[1:] if a]
    apk = None
    if args and args[0] == "--apk":
        apk = args[1] if len(args) > 1 else None
    elif args:
        apk = args[0]

    entries = parse_manifest()
    if entries is None:
        return 2

    print("=== 工作区原件校验（Breeno.apk 字节级副本，共 %d 个）===" % len(entries))
    ok, fail = verify_workspace(entries)
    print("工作区: %d/%d 字节一致" % (ok, ok + fail))

    if apk:
        print("\n=== APK 内原件校验 ===")
        a_ok, a_fail = verify_apk(entries, apk)
        ok += a_ok
        fail += a_fail

    print()
    if fail:
        print("FAIL 有 %d 个原件不一致 —— 它们应当与 Breeno.apk 逐字节相同" % fail)
        return 1
    print("PASS 全部原件与 Breeno.apk 逐字节一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
