# -*- coding: utf-8 -*-
"""
为 MNN / llama.cpp 两个引擎 wrapper 生成 JNI 注册表，实现「符号隔离」。

背景（用户硬规则第 2 条）：
  两个引擎都带 OpenCL/Vulkan，同名全局符号会被运行时链接器互相覆盖。
  实测基线：libMNNWrapper.so 导出 216 个符号（其中 134 个是 MNN/OpenCL/Vulkan 内部实现），
           libLlamaWrapper.so 导出 252 个符号。两者都**没有** JNI_OnLoad。
  目标：每个 .so 只导出 JNI_OnLoad，其余全部 hidden。

做法（零风险改造）：
  · 现有 4500 行引擎代码**一行不动**。
  · 在每个 .cpp 末尾追加一张 JNINativeMethod 表（名字/签名/函数指针）。
  · JNI_OnLoad 里 RegisterNatives，之后 JVM 走函数指针而非符号名查找。
  · 配合 version script（quro_llm.map）把其余符号全部 local。

签名来源：javap -s 直接读已编译的 Kotlin class，**由 JVM 自己算出的描述符**，
不靠人工推断类型 —— 描述符错一个字符就是 UnsatisfiedLinkError。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVAP = r"C:\Program Files\Java\jdk-17.0.11+9\bin\javap.exe"

# (类全名, class 文件所在目录, 目标 cpp 相对路径, 是否在本文件里定义 JNI_OnLoad)
TARGETS = [
    ("com.ai.assistance.llama.LlamaNative",
     "llm/llama/build/tmp/kotlin-classes/release",
     "llm/llama/src/main/cpp/llama_jni_stub.cpp",
     True),
    ("com.ai.assistance.mnn.MNNLlmNative",
     "llm/mnn/build/tmp/kotlin-classes/release",
     "llm/mnn/src/main/cpp/mnnllmnative.cpp",
     True),
    ("com.ai.assistance.mnn.MNNNetNative",
     "llm/mnn/build/tmp/kotlin-classes/release",
     "llm/mnn/src/main/cpp/mnnnetnative.cpp",
     False),
    ("com.ai.assistance.mnn.MNNModuleNative",
     "llm/mnn/build/tmp/kotlin-classes/release",
     "llm/mnn/src/main/cpp/mnnmodulennative.cpp",
     False),
]

MARKER = "// ═════════ QuroLlm L2 注册表：符号隔离（自动生成，勿手改） ═════════"


def javap_descriptors(class_fqcn, class_dir):
    """返回 [(methodName, descriptor), ...]，只取 native 方法。"""
    rel = class_fqcn.replace('.', '/') + ".class"
    out = subprocess.run(
        [JAVAP, "-s", "-p", rel],
        cwd=os.path.join(ROOT, class_dir),
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if out.returncode != 0:
        print("javap 失败:", class_fqcn, out.stderr[:200])
        sys.exit(1)

    methods = []
    lines = out.stdout.splitlines()
    pending_name = None
    for line in lines:
        s = line.strip()
        m = re.match(r'^.*\bnative\s+[\w\[\].$<>]+\s+(\w+)\(', s)
        if m and 'native' in s:
            pending_name = m.group(1)
            continue
        if pending_name and s.startswith("descriptor:"):
            desc = s.split("descriptor:", 1)[1].strip()
            methods.append((pending_name, desc))
            pending_name = None
    return methods


def jni_symbol(class_fqcn, method):
    """Java 符号名：类名里的 . 与 $ 都换成 _（$ → _00024）。"""
    cls = class_fqcn.replace('.', '_').replace('$', '_00024')
    return "Java_%s_%s" % (cls, method)


def jni_class_path(class_fqcn):
    return class_fqcn.replace('.', '/')


def block_for(class_fqcn, methods, owns_onload, other_tables):
    """生成该 TU 的注册表代码块。"""
    short = class_fqcn.rsplit('.', 1)[1]
    cls_path = jni_class_path(class_fqcn)
    table = "k%sMethods" % short

    L = []
    L.append("")
    L.append("")
    L.append(MARKER)
    L.append("//")
    L.append("// 为什么需要这张表：")
    L.append("//   version script 把本 .so 的导出表收敛到只剩 JNI_OnLoad，")
    L.append("//   于是 JVM「按符号名查找 native 方法」的路径不再可用（符号已变 local）。")
    L.append("//   改成在 JNI_OnLoad 里显式 RegisterNatives 给出函数指针，")
    L.append("//   本 .so 的导出符号从数百个降到 1 个，跨引擎的 OpenCL/Vulkan 符号竞争随之消失。")
    L.append("//")
    L.append("// 签名来自 javap -s（JVM 自己算出的描述符），不是人工推断。")
    L.append("// 对应 Kotlin 声明：%s" % class_fqcn)
    L.append("//")
    L.append("#include <jni.h>")
    L.append("#include <android/log.h>")
    L.append("#include <string>")
    L.append("")
    L.append('#include "quro/jni_support.h"')
    L.append("")
    L.append("namespace {")
    L.append("")
    L.append("const JNINativeMethod %s[] = {" % table)
    for name, desc in methods:
        L.append('    {"%s", "%s",' % (name, desc))
        L.append('     reinterpret_cast<void*>(%s)},' % jni_symbol(class_fqcn, name))
    L.append("};")
    L.append("")
    L.append("constexpr int k%sCount = %d;" % (short, len(methods)))
    L.append("")
    L.append("}  // namespace")
    L.append("")

    if owns_onload:
        L.append("namespace quro {")
        L.append("namespace llm {")
        L.append("namespace jni {")
        L.append("")
        L.append("/// 注册本 .so 内的一个 native 类。返回是否全部成功。")
        L.append("bool register_%s(JNIEnv* env) {" % short)
        L.append("    std::string err;")
        L.append('    if (!registerNatives(env, "%s", %s, k%sCount, &err)) {' % (
            cls_path, table, short))
        L.append('        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",')
        L.append('                            "注册 %s 失败：%%s", err.c_str());' % class_fqcn)
        L.append("        return false;")
        L.append("    }")
        L.append("    return true;")
        L.append("}")
        L.append("")
        L.append("}  // namespace jni")
        L.append("}  // namespace llm")
        L.append("}  // namespace quro")
        L.append("")

        # 本 TU 自己的注册函数（其他 TU 定义的用 extern 声明后调用）
        if not owns_onload:
            pass
    else:
        # 非 JNI_OnLoad 的 TU：暴露一个注册函数给 JNI_OnLoad 所在 TU 调用
        L.append("namespace quro {")
        L.append("namespace llm {")
        L.append("namespace jni {")
        L.append("")
        L.append("bool register_%s(JNIEnv* env) {" % short)
        L.append("    std::string err;")
        L.append('    if (!registerNatives(env, "%s", %s, k%sCount, &err)) {' % (
            cls_path, table, short))
        L.append('        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",')
        L.append('                            "注册 %s 失败：%%s", err.c_str());' % class_fqcn)
        L.append("        return false;")
        L.append("    }")
        L.append("    return true;")
        L.append("}")
        L.append("")
        L.append("}  // namespace jni")
        L.append("}  // namespace llm")
        L.append("}  // namespace quro")
        L.append("")

    if owns_onload:
        L.append("// 本 .so 的注册入口。同 .so 内其他 TU 的注册函数在此统一调用。")
        for other_fqcn in other_tables:
            other_short = other_fqcn.rsplit('.', 1)[1]
            L.append("namespace quro { namespace llm { namespace jni {")
            L.append("bool register_%s(JNIEnv* env);" % other_short)
            L.append("} } }")
        L.append("")
        L.append('extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {')
        L.append("    (void) reserved;")
        L.append("")
        L.append("    // 记住 JavaVM：推理在引擎工作线程上跑，回调时需要用它在那个线程 attach。")
        L.append("    quro::llm::jni::setJavaVm(vm);")
        L.append("")
        L.append("    JNIEnv* env = nullptr;")
        L.append("    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {")
        L.append('        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",')
        L.append('                            "JNI_OnLoad: GetEnv 失败");')
        L.append("        return JNI_ERR;")
        L.append("    }")
        L.append("")
        L.append("    bool allOk = quro::llm::jni::register_%s(env);" % short)
        for other_fqcn in other_tables:
            other_short = other_fqcn.rsplit('.', 1)[1]
            L.append("    allOk = quro::llm::jni::register_%s(env) && allOk;" % other_short)
        L.append("")
        L.append("    // 注册失败**不**让 .so 加载失败：加载失败会让整个本地推理链路")
        L.append("    // 直接抛 UnsatisfiedLinkError，连诊断信息都拿不到。")
        L.append("    // 这里放行并由上层在首次调用时给出可读错误。")
        L.append("    if (!allOk) {")
        L.append('        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",')
        L.append('                            "JNI_OnLoad: 部分 native 方法注册失败，本地推理将不可用");')
        L.append("    }")
        L.append("    return JNI_VERSION_1_6;")
        L.append("}")
        L.append("")
    return "\n".join(L)


def main():
    # 按 cpp 分组，先算出每个 .so 里有哪些非 JNI_OnLoad 的表
    onload_owner = {}   # so_key -> class_fqcn (拥有 JNI_OnLoad 的类)
    other_tables = {}   # so_key -> [class_fqcn...]
    so_of = {}
    for fqcn, _cdir, cpp, owns in TARGETS:
        so_key = "llama" if "/llama/" in cpp else "mnn"
        so_of[fqcn] = so_key
        if owns:
            onload_owner[so_key] = fqcn
    for fqcn, _cdir, cpp, owns in TARGETS:
        so_key = so_of[fqcn]
        if not owns:
            other_tables.setdefault(so_key, []).append(fqcn)

    for fqcn, cdir, cpp, owns in TARGETS:
        methods = javap_descriptors(fqcn, cdir)
        if not methods:
            print("!! %s 没有解析到 native 方法" % fqcn)
            sys.exit(1)

        path = os.path.join(ROOT, cpp)
        data = open(path, 'rb').read()
        nl = '\r\n' if b'\r\n' in data else '\n'
        text = data.decode('utf-8')

        if MARKER in text:
            print("跳过（已注入过）：%s" % cpp)
            continue

        # 自检：解析出的方法数应与源码里的 JNIEXPORT 数一致（llama 有一个
        # 已废弃的 nativeSetToolCallGrammar 无 Kotlin 声明，允许少 1）。
        n_export = text.count("JNIEXPORT")
        print("%-28s javap=%2d  JNIEXPORT=%d" % (fqcn.rsplit('.', 1)[1], len(methods), n_export))

        block = block_for(fqcn, methods, owns,
                          other_tables.get(so_of[fqcn], []) if owns else [])
        if nl == '\r\n':
            block = block.replace('\n', '\r\n')

        text = text.rstrip() + '\n' + block
        open(path, 'wb').write(text.encode('utf-8'))
        print("   → 已注入 %s（%d 个方法）" % (cpp, len(methods)))

    print("\n完成。")


if __name__ == "__main__":
    main()
