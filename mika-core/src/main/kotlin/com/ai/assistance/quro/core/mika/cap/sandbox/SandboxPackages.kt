package com.ai.assistance.quro.core.mika.cap.sandbox

/**
 * 运行环境里装的包:Python 与 Node,外加技能脚本清单里 `apk:` 需要的
 * (py3-pypdf:PDF 取字;py3-pillow 现在没有内置技能用到,包本身先留着),以及跟随手机时区所需的 tzdata。
 * 都在 Alpine 3.22 的 main/community 里,构建时装进自带的运行环境
 * (tools/sandbox/build-runtime.py 的 WANT 与此一致,BundledRuntimeTest 核对)。
 */
val SANDBOX_PACKAGES: List<String> = listOf("python3", "nodejs", "py3-pillow", "py3-pypdf", "tzdata")
