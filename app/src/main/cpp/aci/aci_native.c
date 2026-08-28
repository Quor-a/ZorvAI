#include "aci_native.h"

#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/* 供 C 侧 FindClass 用的桥接类（见 AciNativeBridge.kt）。 */
#define ACI_BRIDGE_CLASS "com/ai/assistance/quro/core/aidlaci/AciNativeBridge"

static JavaVM *g_vm = NULL;

/* 线程局部错误串，避免多线程互相覆盖 */
static __thread char t_last_err[256];

static void set_err(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(t_last_err, sizeof(t_last_err), fmt, ap);
    va_end(ap);
}

/* ─────────────────── JNI 环境 ─────────────────── */

/**
 * 取当前线程的 JNIEnv；若当前是纯原生线程则自动 attach。
 * 调用成功后，若 *out_attached 非 0，用毕必须 DetachCurrentThread。
 */
static int aci_get_env(JNIEnv **out_env, int *out_attached) {
    *out_attached = 0;
    *out_env = NULL;
    if (g_vm == NULL) {
        set_err("无 JavaVM：JNI_OnLoad 未执行或本库未被 JVM 进程加载");
        return ACI_ERR_NO_VM;
    }
    if ((*g_vm)->GetEnv(g_vm, (void **) out_env, JNI_VERSION_1_6) == JNI_OK) {
        return ACI_OK;  /* 已是 Java 线程 */
    }
    if ((*g_vm)->AttachCurrentThread(g_vm, out_env, NULL) == JNI_OK) {
        *out_attached = 1;
        return ACI_OK;
    }
    *out_env = NULL;
    set_err("AttachCurrentThread 失败");
    return ACI_ERR_ATTACH;
}

/** 把 Java 返回的字符串拷进 C 缓冲区；缓冲区不足则截断。 */
static int aci_copy_jstring(JNIEnv *env, jstring js, char *out, size_t outcap) {
    if (out == NULL || outcap == 0) {
        set_err("输出缓冲区为空");
        return ACI_ERR_NOBUF;
    }
    out[0] = '\0';
    if (js == NULL) {
        set_err("Java 侧返回 null");
        return ACI_ERR_NULLRES;
    }
    const char *c = (*env)->GetStringUTFChars(env, js, NULL);
    if (c == NULL) {
        set_err("GetStringUTFChars 失败");
        return ACI_ERR_GETSTR;
    }
    size_t n = strlen(c);
    int truncated = 0;
    if (n >= outcap) {
        n = outcap - 1;
        truncated = 1;
    }
    memcpy(out, c, n);
    out[n] = '\0';
    (*env)->ReleaseStringUTFChars(env, js, c);
    if (truncated) set_err("输出被缓冲区截断（容量 %zu）", outcap);
    return ACI_OK;
}

/* ─────────────────── 对外 API ─────────────────── */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;          /* 保存 JavaVM，供后续任意原生线程 attach 后回调 Java */
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    g_vm = NULL;
}

int aci_available(void) {
    JNIEnv *env = NULL;
    int attached = 0;
    int rc = aci_get_env(&env, &attached);
    if (rc != ACI_OK) return rc;

    int ret = 0;
    jclass cls = (*env)->FindClass(env, ACI_BRIDGE_CLASS);
    if (cls == NULL) {
        set_err("找不到桥接类 %s", ACI_BRIDGE_CLASS);
        ret = ACI_ERR_NOCLASS;
    } else {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "isReady", "()Z");
        if (mid == NULL) {
            set_err("找不到 isReady()");
            ret = ACI_ERR_NOMETHOD;
        } else {
            jboolean ok = (*env)->CallStaticBooleanMethod(env, cls, mid);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionDescribe(env);
                (*env)->ExceptionClear(env);
                set_err("isReady() 抛异常");
                ret = ACI_ERR_EXCEPTION;
            } else {
                ret = ok ? 1 : 0;
            }
        }
        (*env)->DeleteLocalRef(env, cls);
    }

    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return ret;
}

int aci_call(const char *target_pkg,
             const char *capability,
             const char *args_json,
             int confirmed,
             char *out, size_t outcap) {
    JNIEnv *env = NULL;
    int attached = 0;
    int rc = aci_get_env(&env, &attached);
    if (rc != ACI_OK) return rc;

    int ret = ACI_OK;
    jclass cls = (*env)->FindClass(env, ACI_BRIDGE_CLASS);
    if (cls == NULL) {
        set_err("找不到桥接类 %s", ACI_BRIDGE_CLASS);
        ret = ACI_ERR_NOCLASS;
        goto done;
    }

    jmethodID mid = (*env)->GetStaticMethodID(
        env, cls, "callJson",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;");
    if (mid == NULL) {
        set_err("找不到 callJson(String,String,String,boolean)");
        ret = ACI_ERR_NOMETHOD;
        goto done;
    }

    jstring jpkg  = (*env)->NewStringUTF(env, target_pkg ? target_pkg : "");
    jstring jcap  = (*env)->NewStringUTF(env, capability ? capability : "");
    jstring jargs = (*env)->NewStringUTF(env, args_json ? args_json : "");

    jstring jres = (jstring) (*env)->CallStaticObjectMethod(
        env, cls, mid, jpkg, jcap, jargs, confirmed ? JNI_TRUE : JNI_FALSE);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_err("callJson() 抛异常");
        ret = ACI_ERR_EXCEPTION;
    } else {
        ret = aci_copy_jstring(env, jres, out, outcap);
    }

    (*env)->DeleteLocalRef(env, jpkg);
    (*env)->DeleteLocalRef(env, jcap);
    (*env)->DeleteLocalRef(env, jargs);
    if (jres != NULL) (*env)->DeleteLocalRef(env, jres);

done:
    if (cls != NULL) (*env)->DeleteLocalRef(env, cls);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return ret;
}

int aci_list(char *out, size_t outcap) {
    JNIEnv *env = NULL;
    int attached = 0;
    int rc = aci_get_env(&env, &attached);
    if (rc != ACI_OK) return rc;

    int ret = ACI_OK;
    jclass cls = (*env)->FindClass(env, ACI_BRIDGE_CLASS);
    if (cls == NULL) {
        set_err("找不到桥接类 %s", ACI_BRIDGE_CLASS);
        ret = ACI_ERR_NOCLASS;
        goto done;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "listJson", "()Ljava/lang/String;");
    if (mid == NULL) {
        set_err("找不到 listJson()");
        ret = ACI_ERR_NOMETHOD;
        goto done;
    }

    jstring jres = (jstring) (*env)->CallStaticObjectMethod(env, cls, mid);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        set_err("listJson() 抛异常");
        ret = ACI_ERR_EXCEPTION;
    } else {
        ret = aci_copy_jstring(env, jres, out, outcap);
    }
    if (jres != NULL) (*env)->DeleteLocalRef(env, jres);

done:
    if (cls != NULL) (*env)->DeleteLocalRef(env, cls);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return ret;
}

const char *aci_last_error(void) {
    return t_last_err[0] ? t_last_err : "(no error)";
}
