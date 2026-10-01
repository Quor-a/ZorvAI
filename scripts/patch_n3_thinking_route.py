# -*- coding: utf-8 -*-
"""N3 · 把引擎侧已分流的「思考段」上行到 Java 的 onThinking 通道。

背景（为什么必须做这一步）：
    L4 的 ThinkSplitter 落地后，引擎侧**已经把 `<think>` 标签吃掉**并把内容分成
    两个通道（TokenChunk::isThinking）。但 Java 侧的 JNI 回调原本只有
    `onToken(String):Boolean` —— 两个通道都被塞进 onToken。
    而 Java 侧那套 StreamingThinkStripper 是**靠标签**来分流的，
    标签被吃掉后它会把思考原文当成正文 → **思考过程实时上屏**（回归）。

本脚本在 JNI 层做「按通道分别上行」，两条路都不会让思考原文裸奔：
    首选：Java 侧实现了 onThinking(String):Boolean → 思考内容走独立通道；
    回退：Java 侧没有该方法 → 把思考内容**包回** `<think>…</think>` 再走 onToken，
          让上层那套文本剥离器照常工作（行为与改造前完全一致）。

跑法：
    python scripts/patch_n3_thinking_route.py
"""
import io
import os
import sys

ROUTE_DOC = '''    // ── 思考段路由（L4 分流器 → Java 上行）────────────────────────────────
    // 引擎侧已经把 `<think>` 标签吃掉，并把内容分成两个通道
    // （TokenChunk::isThinking）。所以这里必须**按通道分别上行**：
    //
    //   首选：Java 侧实现了 onThinking(String):Boolean → 思考内容走独立通道，
    //         上层直接送进"思考区"，不需要任何文本标签。
    //   回退：Java 侧没有该方法（旧实现 / 未同步升级）→ 把思考内容**包回**
    //         `<think>…</think>` 再走 onToken，让上层那套文本剥离器照常工作。
    //
    // 为什么回退也一定要包标签：**绝不能把思考原文裸发**。
    // 上层剥离器只认标签；一旦标签被引擎吃掉、它又拿不到 isThinking，
    // 思考过程就会**实时上屏** —— 这是最容易被忽略、但用户一眼就看得见的回归。
'''

ROUTE_BODY = '''    std::string wrapped;
    const char* payload = chunk.text;
    size_t payloadLen = chunk.len;
    jmethodID mid = sink->midOnToken;
    if (chunk.isThinking) {
        if (sink->midOnThinking != nullptr) {
            mid = sink->midOnThinking;
        } else {
            wrapped.reserve(chunk.len + 16);
            wrapped.append("<think>");
            wrapped.append(chunk.text, chunk.len);
            wrapped.append("</think>");
            payload = wrapped.data();
            payloadLen = wrapped.size();
        }
    }
'''

FAIL = []


def patch(path, edits):
    if not os.path.exists(path):
        FAIL.append("文件不存在: " + path)
        return
    s = io.open(path, encoding="utf-8").read()
    crlf = "\r\n" in s
    if crlf:
        s = s.replace("\r\n", "\n")
    for tag, old, new in edits:
        n = s.count(old)
        if n != 1:
            FAIL.append("%s [%s] 锚点命中 %d 次（期望 1）" % (path, tag, n))
            continue
        s = s.replace(old, new)
    out = s.replace("\n", "\r\n") if crlf else s
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("已打补丁: %s (CRLF=%s)" % (path, crlf))


# ═══════════════════════════════════════════════════════════════════════════
# llama
# ═══════════════════════════════════════════════════════════════════════════
LLAMA = r"llm/llama/src/main/cpp/llama_jni.cpp"

llama_struct_old = '''struct JavaTokenSink {
    void* callbackRef = nullptr;      // jni::retainCallback 拿到的 GlobalRef 封装
    jmethodID midOnToken = nullptr;
    jmethodID midOnProgress = nullptr;'''
llama_struct_new = '''struct JavaTokenSink {
    void* callbackRef = nullptr;      // jni::retainCallback 拿到的 GlobalRef 封装
    jmethodID midOnToken = nullptr;
    jmethodID midOnProgress = nullptr;

    /// 思考段上行通道（`onThinking(String):Boolean`）。
    /// **可为 null**：Java 侧没实现该方法时保持 null，此时思考内容会带上
    /// `<think>…</think>` 包装走 onToken 回退 —— 见 sinkOnToken 里的说明。
    jmethodID midOnThinking = nullptr;'''

llama_sink_old = '''    jstring jdelta = bytesUtf8ToJstring(env, std::string(chunk.text, chunk.len));
    if (jdelta == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const jboolean keepGoing = env->CallBooleanMethod(callbackObj, sink->midOnToken, jdelta);
    env->DeleteLocalRef(jdelta);'''
llama_sink_new = ROUTE_DOC + ROUTE_BODY + '''
    jstring jdelta = bytesUtf8ToJstring(env, std::string(payload, payloadLen));
    if (jdelta == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const jboolean keepGoing = env->CallBooleanMethod(callbackObj, mid, jdelta);
    env->DeleteLocalRef(jdelta);'''

llama_bind_old = '''    jmethodID midOnProgress = env->GetMethodID(cbCls, "onProgress", "(Ljava/lang/String;II)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnProgress = nullptr;
    }
    env->DeleteLocalRef(cbCls);

    JavaTokenSink sink;
    sink.midOnToken = midOnToken;
    sink.midOnProgress = midOnProgress;'''
llama_bind_new = '''    jmethodID midOnProgress = env->GetMethodID(cbCls, "onProgress", "(Ljava/lang/String;II)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnProgress = nullptr;
    }
    // 思考段上行通道，同样是**可选**的：Java 侧没有 onThinking 时静默降级
    // （思考内容带上 <think> 包装走 onToken），而不是让整次生成失败。
    // 必须在 DeleteLocalRef(cbCls) **之前**取，否则 cbCls 已失效。
    jmethodID midOnThinking = env->GetMethodID(cbCls, "onThinking", "(Ljava/lang/String;)Z");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnThinking = nullptr;
    }
    env->DeleteLocalRef(cbCls);

    JavaTokenSink sink;
    sink.midOnToken = midOnToken;
    sink.midOnProgress = midOnProgress;
    sink.midOnThinking = midOnThinking;'''

patch(LLAMA, [
    ("struct", llama_struct_old, llama_struct_new),
    ("sinkOnToken", llama_sink_old, llama_sink_new),
    ("bind", llama_bind_old, llama_bind_new),
])

# ═══════════════════════════════════════════════════════════════════════════
# MNN
# ═══════════════════════════════════════════════════════════════════════════
MNN = r"llm/mnn/src/main/cpp/mnn_jni.cpp"

mnn_struct_old = '''struct JavaTokenSink {
    void* callbackRef = nullptr;
    jmethodID midOnToken = nullptr;
    bool javaRejected = false;'''
mnn_struct_new = '''struct JavaTokenSink {
    void* callbackRef = nullptr;
    jmethodID midOnToken = nullptr;

    /// 思考段上行通道（`onThinking(String):Boolean`）。
    /// **可为 null**：Java 侧没实现该方法时保持 null，此时思考内容会带上
    /// `<think>…</think>` 包装走 onToken 回退 —— 见 sinkOnToken 里的说明。
    jmethodID midOnThinking = nullptr;

    bool javaRejected = false;'''

mnn_sink_old = '''    jstring jtoken = bytesUtf8ToJstring(env, std::string(chunk.text, chunk.len));
    if (jtoken == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const jboolean keepGoing = env->CallBooleanMethod(callbackObj, sink->midOnToken, jtoken);
    env->DeleteLocalRef(jtoken);'''
mnn_sink_new = ROUTE_DOC + ROUTE_BODY + '''
    jstring jtoken = bytesUtf8ToJstring(env, std::string(payload, payloadLen));
    if (jtoken == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const jboolean keepGoing = env->CallBooleanMethod(callbackObj, mid, jtoken);
    env->DeleteLocalRef(jtoken);'''

mnn_bind_old = '''    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cbCls);
    if (midOnToken == nullptr) {
        env->ExceptionClear();
        LOGE("回调缺少 onToken(Ljava/lang/String;)Z 方法");
        return false;
    }

    JavaTokenSink sink;
    sink.midOnToken = midOnToken;'''
mnn_bind_new = '''    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    // 思考段上行通道，**可选**：Java 侧没有 onThinking 时静默降级
    // （思考内容带上 <think> 包装走 onToken），而不是让整次生成失败。
    // 必须在 DeleteLocalRef(cbCls) **之前**取，否则 cbCls 已失效。
    jmethodID midOnThinking = env->GetMethodID(cbCls, "onThinking", "(Ljava/lang/String;)Z");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        midOnThinking = nullptr;
    }
    env->DeleteLocalRef(cbCls);
    if (midOnToken == nullptr) {
        env->ExceptionClear();
        LOGE("回调缺少 onToken(Ljava/lang/String;)Z 方法");
        return false;
    }

    JavaTokenSink sink;
    sink.midOnToken = midOnToken;
    sink.midOnThinking = midOnThinking;'''

patch(MNN, [
    ("struct", mnn_struct_old, mnn_struct_new),
    ("sinkOnToken", mnn_sink_old, mnn_sink_new),
    ("bind", mnn_bind_old, mnn_bind_new),
])

if FAIL:
    print("\n!! 失败项:")
    for f in FAIL:
        print("   -", f)
    sys.exit(1)
print("\n全部补丁成功")
