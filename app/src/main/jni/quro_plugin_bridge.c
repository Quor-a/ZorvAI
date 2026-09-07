/*
 * quro_plugin_bridge.c — JNI 桥：把插件逻辑层引擎跑在 QuickJS 里。
 *
 * 设计要点（对应评审报告"沙箱硬化"与"my.* 权限网关"）：
 *  - 每个插件一个 JSRuntime（隔离边界），JS_SetMemoryLimit 限制堆，JS_SetInterruptHandler 做超时中断（防死循环 DoS）。
 *  - 插件模式（allowEval=0）：删除全局 eval / Function，关闭动态代码执行（沙箱最小面）。
 *  - 脚本包模式（allowEval=1，SandboxPackage / code_runner / ToolPkg）：保留 eval / Function——
 *    CommonJS require() 需要 new Function(moduleCode) 包装模块体；安全靠内存上限 + 超时中断 +
 *    Kotlin 侧 hostCallApi 权限网关（fs/net/system 只暴露白名单操作 + 工作区根目录沙箱）。
 *  - 插件通过两个全局函数与宿主通信：
 *      hostSetData(path:string, value:any)  -> 把 setData 的 path-diff 回传 Kotlin（再推给渲染层 WebView）。
 *      hostCallApi(api:string, paramsJson:string) -> 调宿主能力（my.* / Tools.*），同步返回结果 JSON。
 *  - 事件入口：渲染层 WebView 把 tap/input 经 JSBridge 交给 Kotlin，Kotlin 调 nativeInvokeMethod 执行页面方法。
 *
 * 注意：本文件只负责"引擎 + 桥"，不实现具体 my.* / Tools.* 能力（那是 Kotlin 侧按 manifest 权限网关决定）。
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "quickjs.h"
#include "quickjs-libc.h"

#define QJS_PKG "com/ai/assistance/quro/plugin/QuickJsEngine"

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM    *vm;
    jobject    callback;   /* global ref: PluginSetDataCallback */
    jlong      deadline_ms;/* 超时截止（毫秒，单调时钟）；0=不限 */
} QuroEngine;

/* ---------- 工具：调用 Kotlin 的 setData 回调 ---------- */
static void invoke_setdata(QuroEngine *e, const char *path, const char *valueJson) {
    if (!e || !e->callback || !e->vm) return;
    JNIEnv *env = NULL;
    if ((*e->vm)->GetEnv(e->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return;
    jclass cls = (*env)->GetObjectClass(env, e->callback);
    if (!cls) return;
    jmethodID mid = (*env)->GetMethodID(env, cls, "onSetData",
                                        "(Ljava/lang/String;Ljava/lang/String;)V");
    if (mid) {
        jstring jp = (*env)->NewStringUTF(env, path ? path : "");
        jstring jv = (*env)->NewStringUTF(env, valueJson ? valueJson : "null");
        (*env)->CallVoidMethod(env, e->callback, mid, jp, jv);
        if (jp) (*env)->DeleteLocalRef(env, jp);
        if (jv) (*env)->DeleteLocalRef(env, jv);
    }
    (*env)->DeleteLocalRef(env, cls);
}

/* ---------- hostSetData(path, value) ---------- */
static JSValue js_host_setdata(JSContext *ctx, JSValueConst this_val,
                               int argc, JSValueConst *argv) {
    QuroEngine *e = JS_GetContextOpaque(ctx);
    if (!e) return JS_UNDEFINED;
    const char *path = (argc > 0) ? JS_ToCString(ctx, argv[0]) : NULL;
    JSValue jsonVal = JS_JSONStringify(ctx, (argc > 1) ? argv[1] : JS_UNDEFINED,
                                       JS_UNDEFINED, JS_UNDEFINED);
    const char *valueJson = JS_IsException(jsonVal) ? NULL : JS_ToCString(ctx, jsonVal);
    invoke_setdata(e, path ? path : "", valueJson ? valueJson : "null");
    if (path) JS_FreeCString(ctx, path);
    if (valueJson) JS_FreeCString(ctx, valueJson);
    JS_FreeValue(ctx, jsonVal);
    return JS_UNDEFINED;
}

/* ---------- hostCallApi(api, paramsJson) -> resultJson ---------- */
static JSValue js_host_callapi(JSContext *ctx, JSValueConst this_val,
                               int argc, JSValueConst *argv) {
    QuroEngine *e = JS_GetContextOpaque(ctx);
    if (!e || !e->callback || !e->vm) return JS_UNDEFINED;
    const char *api = (argc > 0) ? JS_ToCString(ctx, argv[0]) : "";
    const char *params = (argc > 1) ? JS_ToCString(ctx, argv[1]) : "{}";

    JNIEnv *env = NULL;
    if ((*e->vm)->GetEnv(e->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (api) JS_FreeCString(ctx, api);
        if (params) JS_FreeCString(ctx, params);
        return JS_UNDEFINED;
    }
    jclass cls = (*env)->GetObjectClass(env, e->callback);
    jmethodID mid = cls ? (*env)->GetMethodID(env, cls, "onHostApi",
                                             "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") : NULL;
    JSValue ret = JS_UNDEFINED;
    if (mid) {
        jstring japi = (*env)->NewStringUTF(env, api ? api : "");
        jstring jparams = (*env)->NewStringUTF(env, params ? params : "{}");
        jstring jres = (jstring)(*env)->CallObjectMethod(env, e->callback, mid, japi, jparams);
        if (jres) {
            const char *cres = (*env)->GetStringUTFChars(env, jres, NULL);
            if (cres) {
                ret = JS_ParseJSON(ctx, cres, strlen(cres), "hostCallApi");
                (*env)->ReleaseStringUTFChars(env, jres, cres);
            }
            (*env)->DeleteLocalRef(env, jres);
        }
        if (japi) (*env)->DeleteLocalRef(env, japi);
        if (jparams) (*env)->DeleteLocalRef(env, jparams);
    }
    if (cls) (*env)->DeleteLocalRef(env, cls);
    if (api) JS_FreeCString(ctx, api);
    if (params) JS_FreeCString(ctx, params);
    if (JS_IsException(ret)) {
        JSValue exc = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, exc);
        ret = JS_NewString(ctx, msg ? msg : "host api error");
        if (msg) JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, exc);
    }
    return ret;
}

/* ---------- 超时中断 ---------- */
static int js_interrupt(JSRuntime *rt, void *opaque) {
    QuroEngine *e = (QuroEngine *)opaque;
    if (!e || e->deadline_ms == 0) return 0;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    long now_ms = (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    return (now_ms >= e->deadline_ms) ? 1 : 0;
}

/* ===================== JNI ===================== */

JNIEXPORT jlong JNICALL
Java_com_ai_assistance_quro_plugin_QuickJsEngine_nativeCreateRuntime(
        JNIEnv *env, jobject thiz, jint memLimitBytes, jint timeoutMs, jint allowEval) {
    QuroEngine *e = (QuroEngine *)calloc(1, sizeof(QuroEngine));
    if (!e) return 0;

    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    if (!e->rt || !e->ctx) { free(e); return 0; }

    JS_SetContextOpaque(e->ctx, e);
    if (memLimitBytes > 0) JS_SetMemoryLimit(e->rt, (size_t)memLimitBytes);
    e->deadline_ms = 0;
    JS_SetInterruptHandler(e->rt, js_interrupt, e);

    (*env)->GetJavaVM(env, &e->vm);

    /* 沙箱硬化：插件模式删 eval/Function；脚本包模式保留（CommonJS require 需要） */
    if (!allowEval) {
        JSValue global = JS_GetGlobalObject(e->ctx);
        JS_DeleteProperty(e->ctx, global, JS_NewAtom(e->ctx, "eval"), 0);
        JS_DeleteProperty(e->ctx, global, JS_NewAtom(e->ctx, "Function"), 0);
        JS_FreeValue(e->ctx, global);
    }

    /* 注册宿主通信函数 */
    JSValue global = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, global,
                      "hostSetData", JS_NewCFunction(e->ctx, js_host_setdata, "hostSetData", 2));
    JS_SetPropertyStr(e->ctx, global,
                      "hostCallApi", JS_NewCFunction(e->ctx, js_host_callapi, "hostCallApi", 2));
    JS_FreeValue(e->ctx, global);

    (void)timeoutMs; /* 超时在 eval/invoke 时设置 */
    return (jlong)(intptr_t)e;
}

JNIEXPORT jstring JNICALL
Java_com_ai_assistance_quro_plugin_QuickJsEngine_nativeEvalPlugin(
        JNIEnv *env, jobject thiz, jlong ptr, jstring jsCode, jobject callback, jint timeoutMs) {
    QuroEngine *e = (QuroEngine *)(intptr_t)ptr;
    if (!e) return NULL;
    if (e->callback) { (*env)->DeleteGlobalRef(env, e->callback); e->callback = NULL; }
    if (callback) e->callback = (*env)->NewGlobalRef(env, callback);

    const char *code = (*env)->GetStringUTFChars(env, jsCode, NULL);

    /* 设超时截止 */
    if (timeoutMs > 0) {
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        e->deadline_ms = (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000 + timeoutMs;
    } else {
        e->deadline_ms = 0;
    }

    JSValue val = JS_Eval(e->ctx, code, strlen(code), "plugin.js", JS_EVAL_TYPE_GLOBAL);
    jstring err = NULL;
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *msg = JS_ToCString(e->ctx, exc);
        err = (*env)->NewStringUTF(env, msg ? msg : "eval exception");
        if (msg) JS_FreeCString(e->ctx, msg);
        JS_FreeValue(e->ctx, exc);
    }
    JS_FreeValue(e->ctx, val);
    if (code) (*env)->ReleaseStringUTFChars(env, jsCode, code);
    e->deadline_ms = 0;
    return err;
}

JNIEXPORT jstring JNICALL
Java_com_ai_assistance_quro_plugin_QuickJsEngine_nativeInvokeMethod(
        JNIEnv *env, jobject thiz, jlong ptr, jstring method,
        jstring datasetJson, jstring value) {
    QuroEngine *e = (QuroEngine *)(intptr_t)ptr;
    if (!e) return NULL;
    const char *m = (*env)->GetStringUTFChars(env, method, NULL);

    /* 设超时截止（2s 保底，防插件方法死循环） */
    {
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        e->deadline_ms = (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000 + 2000;
    }

    /* 构造参数对象 { dataset, value }，再调用 globalThis.__page[method](arg) */
    char buf[256];
    const char *ds = datasetJson ? (*env)->GetStringUTFChars(env, datasetJson, NULL) : "{}";
    const char *v  = value ? (*env)->GetStringUTFChars(env, value, NULL) : "null";
    snprintf(buf, sizeof(buf),
             "(function(){var d=%s;var v=%s;if(!globalThis.__page)return undefined;"
             "var f=globalThis.__page[%s];if(typeof f!=='function')return undefined;"
             "return f.call(globalThis.__page,v);})()",
             ds ? ds : "{}", v ? v : "null", m ? m : "''");

    JSValue val = JS_Eval(e->ctx, buf, strlen(buf), "invoke", JS_EVAL_TYPE_GLOBAL);
    jstring err = NULL;
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *msg = JS_ToCString(e->ctx, exc);
        err = (*env)->NewStringUTF(env, msg ? msg : "invoke exception");
        if (msg) JS_FreeCString(e->ctx, msg);
        JS_FreeValue(e->ctx, exc);
    }
    JS_FreeValue(e->ctx, val);
    if (m) (*env)->ReleaseStringUTFChars(env, method, m);
    if (ds) (*env)->ReleaseStringUTFChars(env, datasetJson, ds);
    if (v) (*env)->ReleaseStringUTFChars(env, value, v);
    return err;
}

JNIEXPORT void JNICALL
Java_com_ai_assistance_quro_plugin_QuickJsEngine_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong ptr) {
    QuroEngine *e = (QuroEngine *)(intptr_t)ptr;
    if (!e) return;
    if (e->callback) { (*env)->DeleteGlobalRef(env, e->callback); e->callback = NULL; }
    if (e->ctx) JS_FreeContext(e->ctx);
    if (e->rt) JS_FreeRuntime(e->rt);
    free(e);
}
