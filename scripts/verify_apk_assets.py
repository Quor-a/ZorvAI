#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 APK 里内嵌的 web 资产与源码是否逐字节一致。

为什么需要它：构建成功**不等于** APK 里装着刚改的东西。asset 合并、增量构建、
`UP-TO-DATE` 缓存都可能让你交付一个「源码是新的、包里是旧的」APK，
而这种错**不会有任何报错**——装机后只是「怎么还是老样子」。

比对 SHA-256 是「APK = 源码」的硬证据，比看文件大小靠谱得多。

用法：
    python scripts/verify_apk_assets.py <apk>                     # 默认关注集
    python scripts/verify_apk_assets.py <apk> web-chat zx         # 指定目录
    python scripts/verify_apk_assets.py <apk> web-chat/chat.js    # 指定单文件

默认关注集是「本轮改动过、且出错会静默」的那几个（web-chat 前端 + zxwidget 模块 +
单卡片运行时 + 契约）。同时会检查 `MUST_BE_GONE`：这些资产必须**不在**包里。
"""
import hashlib
import io
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

# 默认关注集：目录或单文件（相对于 app/src/main/assets）
FOCUS = ["web-chat", "zx", "zx_card_runtime.html", "zx_contracts.json"]

# 这些必须已经不在 APK 里（删掉的东西不许阴魂不散）
MUST_BE_GONE = ["zx_chat_runtime.html"]

CHUNK = 1 << 20


def sha256_file(path):
    h = hashlib.sha256()
    with io.open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def collect(targets):
    """把关注集展开成 {apk 内路径: 本地绝对路径}。"""
    out = {}
    for t in targets:
        p = os.path.join(ASSETS, t.replace("\\", "/"))
        if os.path.isfile(p):
            rel = t.replace("\\", "/")
            out["assets/" + rel] = p
        elif os.path.isdir(p):
            for dirpath, _dirnames, filenames in os.walk(p):
                for fn in filenames:
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, ASSETS).replace("\\", "/")
                    out["assets/" + rel] = full
        else:
            out[t] = None  # 标记为缺失，下面报出来
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    apk = sys.argv[1]
    if not os.path.isfile(apk):
        print("找不到 APK：%s" % apk)
        return 2

    targets = sys.argv[2:] or FOCUS
    wanted = collect(targets)

    missing_src = [k for k, v in wanted.items() if v is None]
    if missing_src:
        print("源码里找不到这些路径（改名了？）：")
        for k in missing_src:
            print("  · %s" % k)
        return 2

    zf = zipfile.ZipFile(apk)
    names = set(zf.namelist())

    print("APK  %s  (%.1f MB)" % (os.path.basename(apk), os.path.getsize(apk) / 1048576.0))
    print("关注 %d 个资产" % len(wanted))
    print("")

    bad = 0
    for apk_path in sorted(wanted):
        local = wanted[apk_path]
        src = sha256_file(local)
        src_size = os.path.getsize(local)

        if apk_path not in names:
            print("MISSING  %-34s  源码 %8d B  包内没有" % (apk_path, src_size))
            bad += 1
            continue

        h = hashlib.sha256()
        with zf.open(apk_path) as f:
            while True:
                b = f.read(CHUNK)
                if not b:
                    break
                h.update(b)
        embedded = h.hexdigest()
        apk_size = zf.getinfo(apk_path).file_size

        if embedded == src and apk_size == src_size:
            print("MATCH    %-34s %8d B  %s" % (apk_path, src_size, src[:12]))
        else:
            print("DIFF     %-34s  源码 %d B %s / 包内 %d B %s"
                  % (apk_path, src_size, src[:12], apk_size, embedded[:12]))
            bad += 1

    for gone in MUST_BE_GONE:
        rel = "assets/" + gone
        if rel in names:
            print("STALE    %-34s  已删除的资产仍在包里" % rel)
            bad += 1
        else:
            print("GONE     %-34s  已从包里移除（符合预期）" % rel)

    print("")
    if bad:
        print("FAIL %d 项不一致 —— 这个 APK 不能交付" % bad)
        return 1
    print("PASS 包内资产与源码逐字节一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
