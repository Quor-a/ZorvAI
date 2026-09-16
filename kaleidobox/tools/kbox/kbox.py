#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
kbox —— KaleidoBox 插件打包工具（"一个插件 = 一个 App"）

把一份 Kotlin/Java 源码 + 一份 kaleido.json 清单 编译/打包成可分发的 .kbox 包：

    1. 用 ecj/javac 把源码编译成 class（需要 kaleidobox-api 的 classpath —— 即宿主导出的 API jar）。
    2. 用 Android d8 把 class 转成 classes.dex（运行时用 DexClassLoader 加载）。
    3. 把 kaleido.json + classes.dex（+ 可选 res/ 资源）打成 <name>.kbox（就是个 zip）。

用法：
    python kbox.py build \
        --src   examples/HelloApp.java \
        --manifest examples/hello.kaleido.json \
        --api-jar /path/to/kaleidobox-api.jar \
        --out   hello.kbox

依赖（在 PATH 或可经 --ecj/--d8 指定）：
    - ecj   （Eclipse Java 编译器，jar 形式传入 --ecj         可用 javac 替代，--javac）
    - d8    （Android 脱糖/DEX 工具，java -jar d8.jar 形式经 --d8 传入）

宿主侧加载：KaleidoBoxHost.runtime.install(ZipSource("hello.kbox")) 即可，
对宿主而言这就是"安装一个 App"。
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

# 一个最小能力清单校验，避免打出无法安装的包。
REQUIRED_MANIFEST_KEYS = ("id", "name", "version", "runtime", "ui")


def log(msg: str) -> None:
    print(f"[kbox] {msg}")


def fail(msg: str) -> None:
    print(f"[kbox][ERROR] {msg}", file=sys.stderr)
    sys.exit(1)


def parse_manifest(path: str) -> dict:
    if not os.path.isfile(path):
        fail(f"清单不存在: {path}")
    try:
        with open(path, "r", encoding="utf-8") as f:
            m = json.load(f)
    except Exception as e:  # noqa: BLE001
        fail(f"清单 JSON 解析失败: {e}")
    for k in REQUIRED_MANIFEST_KEYS:
        if k not in m:
            fail(f"清单缺少必需字段: {k}")
    if isinstance(m["runtime"], list) and len(m["runtime"]) == 0:
        fail("runtime 不能为空")
    return m


def compile_java(src: str, api_jar: str, out_dir: str, javac: str, ecj: str | None) -> None:
    os.makedirs(out_dir, exist_ok=True)
    cp = api_jar if api_jar else "."
    # 多文件：把 src 目录或单文件都加进去
    sources = [src] if os.path.isfile(src) else [
        os.path.join(root, f) for root, _, files in os.walk(src)
        for f in files if f.endswith((".java", ".kt"))
    ]
    if not sources:
        fail(f"没有可编译的源文件: {src}")
    if ecj:
        cmd = ["java", "-jar", ecj, "-cp", cp, "-d", out_dir, "-source", "17", "-target", "17", *sources]
    else:
        cmd = [javac, "-cp", cp, "-d", out_dir, *sources]
    log("编译源码: " + " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        fail(f"编译失败:\n{r.stdout}\n{r.stderr}")


def d8_dex(class_dir: str, out_dex: str, d8: str) -> None:
    cmd = ["java", "-jar", d8, "--output", out_dex, "--lib",
           # 用宿主 platform 的 android.jar；若没有就交给运行时 ART 兜底
           class_dir]
    # d8 需要至少一个输入；把 class 目录当输入
    cmd_input = [class_dir]
    cmd = ["java", "-jar", d8, "--output", out_dex, *cmd_input]
    log("DEX 转换: " + " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0 or not os.path.isfile(out_dex):
        fail(f"DEX 失败:\n{r.stdout}\n{r.stderr}")


def package(manifest_path: str, dex_path: str, res_dir: str | None, out: str) -> None:
    if not out.endswith(".kbox"):
        out += ".kbox"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(manifest_path, "kaleido.json")
        z.write(dex_path, "classes.dex")
        if res_dir and os.path.isdir(res_dir):
            for root, _, files in os.walk(res_dir):
                for f in files:
                    full = os.path.join(root, f)
                    arc = os.path.join("res", os.path.relpath(full, res_dir))
                    z.write(full, arc)
    log(f"已生成插件包: {out} ({os.path.getsize(out)} bytes)")


def main() -> None:
    p = argparse.ArgumentParser(prog="kbox", description="KaleidoBox 插件打包器")
    sub = p.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="编译并打包 .kbox")
    b.add_argument("--src", required=True, help="源码文件或目录（.java/.kt）")
    b.add_argument("--manifest", required=True, help="kaleido.json 清单路径")
    b.add_argument("--api-jar", required=True, help="kaleidobox-api.jar（宿主导出的插件 SDK）")
    b.add_argument("--out", required=True, help="输出 .kbox 路径")
    b.add_argument("--res", default=None, help="可选资源目录，会打包进 res/")
    b.add_argument("--ecj", default=None, help="ecj jar 路径（默认用 javac）")
    b.add_argument("--javac", default="javac", help="javac 可执行（默认 PATH 上）")
    b.add_argument("--d8", required=True, help="d8 jar 路径（Android SDK build-tools）")

    args = p.parse_args()
    if args.cmd == "build":
        m = parse_manifest(args.manifest)
        log(f"构建插件: {m['id']} v{m['version']}")
        tmp = tempfile.mkdtemp(prefix="kbox_")
        try:
            classes = os.path.join(tmp, "classes")
            compile_java(args.src, args.api_jar, classes, args.javac, args.ecj)
            dex = os.path.join(tmp, "classes.dex")
            d8_dex(classes, dex, args.d8)
            package(args.manifest, dex, args.res, args.out)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        log("完成。")


if __name__ == "__main__":
    main()
