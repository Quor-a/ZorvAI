// =============================================================================
// L2 · JNI 支撑 —— RegisterNatives 底座 + 跨线程回调桥
// =============================================================================
// 为什么必须用 RegisterNatives 而不是靠符号名自动查找：
//
//   用户的硬规则第 2 条要求 `.so` 上只导出 `JNI_OnLoad`，其余符号全部 hidden。
//   而 JVM 靠**符号名**（`Java_com_x_Y_z`）自动绑定 native 方法的前提，
//   是那个符号在动态符号表里可见。两者直接冲突。
//
//   解法：把绑定从"加载时按名字查"改成"加载时显式注册"：
//     JNI_OnLoad → RegisterNatives(类, 方法表) → 之后所有调用走函数指针
//   于是 .so 可以只导出 JNI_OnLoad，其余全部 local（version script 收敛）。
//   附带收益：符号表从几千个缩小到 1 个，
//   两个引擎（都带 OpenCL/Vulkan）之间互相覆盖同名全局符号的风险随之消失。
//
// 跨线程回调：
//   推理在引擎自己的工作线程上跑，回吐 token 时那个线程对 JVM 是"未附加"的。
//   直接调 Java 方法会崩。JniScope 负责 attach/Detach（且只在不属于本线程时 detach），
//   这是唯一安全的做法 —— 曾经用 "AttachCurrentThread 但从不 detach" 的写法
//   会在长会话里泄漏线程结构，最终 OOM/崩。
// =============================================================================
#ifndef QURO_LLM_JNI_SUPPORT_H
#define QURO_LLM_JNI_SUPPORT_H

#include <jni.h>

#include <string>

namespace quro {
namespace llm {
namespace jni {

/// JNI_OnLoad 里调一次，记住 JavaVM（后续跨线程 attach 要用）。
void setJavaVm(JavaVM* vm);
JavaVM* javaVm();

/// 取当前线程的 JNIEnv（必要时 attach）。返回 nullptr 表示不可用。
JNIEnv* env();

/// RAII：保证当前线程已 attach，退出时按需 detach。
class Scope {
public:
    Scope();
    ~Scope();
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;

    JNIEnv* env() const { return env_; }
    bool ok() const { return env_ != nullptr; }

private:
    JNIEnv* env_ = nullptr;
    bool attachedHere_ = false;
};

/// 注册 native 方法。失败时 err 给出可读原因（类找不到 / 方法签名不匹配）。
bool registerNatives(JNIEnv* env, const char* className,
                     const JNINativeMethod* methods, int count, std::string* err);

/**
 * 把一个 Java 回调对象封成 native 侧可跨线程调用的句柄。
 *
 * 内部持有 GlobalRef（跨线程/跨方法调用必须用 GlobalRef，LocalRef 在 JNI 帧弹出后即失效）。
 * 返回的指针交给上层（通常是 L1 编排层）保管，用完调 releaseCallback()。
 */
void* retainCallback(JNIEnv* env, jobject callback);

/// 释放 retainCallback 取得的句柄（会 DeleteGlobalRef）。
void releaseCallback(void* handle);

/// 取回封装在句柄里的 jobject（GlobalRef）。句柄为空返回 nullptr。
jobject callbackObject(void* handle);

/// 抛出 Java 异常（供 native 侧把错误结构化地传回 Kotlin）。
void throwIllegalState(JNIEnv* env, const std::string& message);
void throwIllegalArgument(JNIEnv* env, const std::string& message);

}  // namespace jni
}  // namespace llm
}  // namespace quro

#endif  // QURO_LLM_JNI_SUPPORT_H
