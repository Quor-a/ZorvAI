// =============================================================================
// L2 · JNI 支撑实现
// =============================================================================
#include "quro/jni_support.h"

#include <android/log.h>

namespace quro {
namespace llm {
namespace jni {

namespace {
JavaVM* g_vm = nullptr;

struct CallbackHandle {
    jobject globalRef = nullptr;
};
}  // namespace

void setJavaVm(JavaVM* vm) { g_vm = vm; }

JavaVM* javaVm() { return g_vm; }

JNIEnv* env() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* e = nullptr;
    const jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6);
    if (rc == JNI_OK && e != nullptr) return e;
    return nullptr;
}

Scope::Scope() {
    if (g_vm == nullptr) return;

    JNIEnv* e = nullptr;
    const jint rc = g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6);
    if (rc == JNI_OK && e != nullptr) {
        // 已经附加（主线程或此前 attach 过的工作线程）→ 不能 detach，那是别人的线程。
        env_ = e;
        attachedHere_ = false;
        return;
    }
    if (rc == JNI_EDETACHED) {
        // 这是引擎的工作线程，第一次进 JNI → attach。
        // 名字带上便于 native 崩溃栈定位（栈里会显示线程名）。
        JavaVMAttachArgs args{};
        args.version = JNI_VERSION_1_6;
        args.name = const_cast<char*>("quro-llm-worker");
        args.group = nullptr;
        if (g_vm->AttachCurrentThread(&e, &args) == JNI_OK && e != nullptr) {
            env_ = e;
            attachedHere_ = true;
        }
    }
}

Scope::~Scope() {
    // 只 detach 自己 attach 的。detach 别人附加的线程会让对方后续调用直接崩。
    if (attachedHere_ && g_vm != nullptr) {
        g_vm->DetachCurrentThread();
    }
}

bool registerNatives(JNIEnv* e, const char* className, const JNINativeMethod* methods,
                     int count, std::string* err) {
    if (e == nullptr) {
        if (err) *err = "JNIEnv 为空（JVM 未附加）";
        return false;
    }
    if (className == nullptr || methods == nullptr || count <= 0) {
        if (err) *err = "registerNatives 参数无效";
        return false;
    }

    jclass clazz = e->FindClass(className);
    if (clazz == nullptr) {
        if (e->ExceptionCheck()) e->ExceptionClear();
        if (err) *err = std::string("找不到 Java 类：") + className;
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "RegisterNatives 失败：类不存在 %s", className);
        return false;
    }

    const jint rc = e->RegisterNatives(clazz, methods, count);
    e->DeleteLocalRef(clazz);
    if (rc != JNI_OK) {
        if (e->ExceptionCheck()) e->ExceptionClear();
        if (err) {
            *err = std::string("RegisterNatives 失败（rc=") + std::to_string(rc) +
                   "），通常是方法名或签名与 Java 声明不一致：" + className;
        }
        __android_log_print(ANDROID_LOG_ERROR, "QuroLlm.Jni",
                            "RegisterNatives 失败 rc=%d class=%s", rc, className);
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, "QuroLlm.Jni",
                        "RegisterNatives 成功：%s（%d 个方法）", className, count);
    return true;
}

void* retainCallback(JNIEnv* e, jobject callback) {
    if (e == nullptr || callback == nullptr) return nullptr;
    // GlobalRef：回调会从引擎工作线程触发，跨越多个 JNI 帧与线程，
    // LocalRef 在帧退出后立即失效，用它必然崩。
    jobject g = e->NewGlobalRef(callback);
    if (g == nullptr) return nullptr;
    CallbackHandle* h = new CallbackHandle();
    h->globalRef = g;
    return h;
}

void releaseCallback(void* handle) {
    if (handle == nullptr) return;
    CallbackHandle* h = static_cast<CallbackHandle*>(handle);
    if (h->globalRef != nullptr) {
        JNIEnv* e = env();
        if (e != nullptr) {
            e->DeleteGlobalRef(h->globalRef);
        } else {
            // 拿不到 env 时至少不能泄漏 GlobalRef 计数：attach 一次再删。
            Scope scope;
            if (scope.ok()) scope.env()->DeleteGlobalRef(h->globalRef);
        }
    }
    delete h;
}

jobject callbackObject(void* handle) {
    if (handle == nullptr) return nullptr;
    return static_cast<CallbackHandle*>(handle)->globalRef;
}

void throwIllegalState(JNIEnv* e, const std::string& message) {
    if (e == nullptr) return;
    jclass clazz = e->FindClass("java/lang/IllegalStateException");
    if (clazz == nullptr) {
        if (e->ExceptionCheck()) e->ExceptionClear();
        return;
    }
    e->ThrowNew(clazz, message.c_str());
    e->DeleteLocalRef(clazz);
}

void throwIllegalArgument(JNIEnv* e, const std::string& message) {
    if (e == nullptr) return;
    jclass clazz = e->FindClass("java/lang/IllegalArgumentException");
    if (clazz == nullptr) {
        if (e->ExceptionCheck()) e->ExceptionClear();
        return;
    }
    e->ThrowNew(clazz, message.c_str());
    e->DeleteLocalRef(clazz);
}

}  // namespace jni
}  // namespace llm
}  // namespace quro
