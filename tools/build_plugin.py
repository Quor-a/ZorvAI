#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ZorvAI APK 级插件构建工具 —— 生成插件模块并直接产出一枚「可被宿主安装」的签名 APK。

设计取向（与参考实现不同）：
  生成的不是脱离仓库的独立工程，而是**直接落在宿主仓库内的 Gradle 模块**（plugin-<短名>），
  并自动写进 settings.gradle.kts。好处是：
    1. 复用仓库的 libs.versions.toml 与 :plugin-contract 模块，不需要手工准备 plugin-contract.aar；
    2. 直接复用根目录 keystore.properties 的签名配置 —— 插件必须与宿主同签名才能被安装；
    3. 一条命令：生成 → 编译 → 产出签名 APK，路径可直接喂给宿主的 plugin_install 工具。

用法：
    python tools/build_plugin.py \
        --name "快递查询" \
        --package com.zorv.plugin.express \
        --tool express_query \
        --desc "根据快递单号查询物流轨迹"

产出：plugin-express/build/outputs/apk/release/plugin-express-release.apk（已完成签名）
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TPL_DIR = os.path.join(REPO, "ai-template")


def read_tpl(name: str) -> str:
    with open(os.path.join(TPL_DIR, "src", name), encoding="utf-8") as f:
        return f.read()


def main():
    ap = argparse.ArgumentParser(description="构建 ZorvAI APK 级插件")
    ap.add_argument("--name", required=True, help="插件显示名")
    ap.add_argument("--package", required=True, help="插件包名，同时作为插件 ID")
    ap.add_argument("--entry", default=None, help="入口类名，默认 <Name-title>Entry")
    ap.add_argument("--tool", required=True, help="AI 工具名（全局唯一，建议加插件前缀）")
    ap.add_argument("--desc", required=True, help="工具描述（给 LLM 看，写清「什么时候用」）")
    ap.add_argument("--source", default=None, help="已写好的 Entry 源码路径；不填则按模板生成骨架")
    ap.add_argument("--build", default="auto", choices=["auto", "yes", "no"],
                    help="是否构建；auto=仓库内自动构建")
    ap.add_argument("--gradle", default=None, help="gradlew 路径，默认 <repo>/gradlew.bat")
    a = ap.parse_args()

    entry = a.entry or (re.sub(r"[^A-Za-z]", "", a.name.title()) + "Entry")
    pkg_path = a.package.replace(".", "/")
    short = a.package.split(".")[-1]
    mod_name = f"plugin-{short}"
    mod_dir = os.path.join(REPO, mod_name)

    # ---------- 1. 生成源码 ----------
    code = None
    if a.source:
        with open(a.source, encoding="utf-8") as f:
            code = f.read()
    else:
        code = (read_tpl("PluginEntry.template.kt")
                .replace("__PACKAGE__", a.package)
                .replace("__PLUGIN_NAME__", a.name)
                .replace("__ENTRY_CLASS__", entry)
                .replace("__TOOL_NAME__", a.tool)
                .replace("__TOOL_DESC__", a.desc)
                .replace("__PARAM_DESC__", "输入内容"))
        print(f"[1/5] 已按模板生成插件源码骨架（入口类 {entry}）")

    java_dir = os.path.join(mod_dir, "src", "main", "java", pkg_path)
    os.makedirs(java_dir, exist_ok=True)
    os.makedirs(os.path.join(mod_dir, "src", "main", "res", "values"), exist_ok=True)
    with open(os.path.join(java_dir, f"{entry}.kt"), "w", encoding="utf-8") as f:
        f.write(code)

    # ---------- 2. 清单 ----------
    mf = (read_tpl("AndroidManifest.template.xml")
          .replace("__PLUGIN_NAME__", a.name)
          .replace("__PACKAGE__", a.package)
          .replace("__ENTRY_CLASS__", entry))
    with open(os.path.join(mod_dir, "src", "main", "AndroidManifest.xml"), "w", encoding="utf-8") as f:
        f.write(mf)
    with open(os.path.join(mod_dir, "src", "main", "res", "values", "strings.xml"), "w",
              encoding="utf-8") as f:
        f.write(f'<resources>\n    <string name="plugin_name">{a.name}</string>\n</resources>\n')
    print(f"[2/5] 模块已生成：{mod_dir}")

    # ---------- 3. build.gradle.kts（与宿主同签名 + 依赖 :plugin-contract） ----------
    gradle_kts = f'''import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 与宿主共用签名：插件必须与宿主同签名，宿主 PluginInstaller 才会放行安装。
val ksFile = rootProject.file("keystore.properties")
val ks = Properties()
if (ksFile.exists()) ks.load(ksFile.inputStream())
val ksPath = (ks["storeFile"] as String?).orEmpty().removePrefix("../")

plugins {{
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}}

android {{
    namespace = "{a.package}"
    compileSdk = 36
    defaultConfig {{
        applicationId = "{a.package}"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }}
    signingConfigs {{
        create("release") {{
            if (ksPath.isNotEmpty()) {{
                storeFile = rootProject.file(ksPath)
                storePassword = ks["storePassword"] as String
                keyAlias = ks["keyAlias"] as String
                keyPassword = ks["keyPassword"] as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }}
        }}
    }}
    buildTypes {{
        release {{
            isMinifyEnabled = false
            if (ksPath.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }}
        debug {{
            if (ksPath.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }}
    }}
    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}
    buildFeatures {{ buildConfig = false }}
    lint {{ abortOnError = false; checkReleaseBuilds = false }}
}}

kotlin {{ compilerOptions {{ jvmTarget = JvmTarget.JVM_17 }} }}

dependencies {{
    // 契约层：编译期可见、运行期由宿主提供（绝不能打进 APK，否则接口 Class 会与宿主不一致）
    compileOnly(project(":plugin-contract"))
    compileOnly(libs.coroutines.core)
}}
'''
    with open(os.path.join(mod_dir, "build.gradle.kts"), "w", encoding="utf-8") as f:
        f.write(gradle_kts)

    # ---------- 4. 写进 settings.gradle.kts ----------
    sg_path = os.path.join(REPO, "settings.gradle.kts")
    with open(sg_path, encoding="utf-8") as f:
        sg = f.read()
    token = f'":{mod_name}"'
    if token not in sg:
        m = re.search(r'include\((.*?)\)\s*\n', sg, re.S)
        if m:
            old = m.group(0)
            new = old.rstrip(")\n").rstrip() + f', {token})\n'
            sg = sg.replace(old, new, 1)
            with open(sg_path, "w", encoding="utf-8") as f:
                f.write(sg)
            print(f"[3/5] 已加入 settings.gradle.kts：{token}")
        else:
            print("[3/5] 警告：未能自动改写 settings.gradle.kts，请手工加入 " + token)
    else:
        print(f"[3/5] settings.gradle.kts 已包含 {token}")

    # ---------- 5. 构建 ----------
    gradle = a.gradle or os.path.join(REPO, "gradlew.bat")
    do_build = a.build == "yes" or (a.build == "auto" and os.path.exists(gradle))
    if not do_build:
        print(f"[4/5] 跳过构建。请执行： ./gradlew :{mod_name}:assembleRelease")
        print(f"[5/5] 产物路径：{mod_name}/build/outputs/apk/release/{mod_name}-release.apk")
        return

    task = f":{mod_name}:assembleRelease"
    print(f"[4/5] 开始构建 {task}（需要 Android SDK）")
    # 必须 cd 到仓库根目录：从别处调 gradlew.bat 会因 %~dp0 解析错根目录而静默失败
    r = subprocess.run([gradle, task], cwd=REPO, capture_output=True, text=True, shell=False)
    if r.returncode != 0:
        tail = (r.stdout or "")[-3000:]
        print(tail)
        sys.exit("编译失败")
    apk = os.path.join(mod_dir, "build", "outputs", "apk", "release", f"{mod_name}-release.apk")
    if os.path.exists(apk):
        print(f"[5/5] 构建完成：{apk}")
        print(f"      （已完成签名，可直接用 plugin_install 工具安装，或拷到设备后手动导入）")
    else:
        print("[5/5] 构建成功但未找到预期产物，请检查输出目录。")


if __name__ == "__main__":
    main()
