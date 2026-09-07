/*
 * pybridge.c — CPython 3.14 嵌入桥（libquropybridge.so）
 * =====================================================
 * 运行时 dlopen libpython3.14.so（APK lib 目录，full 风味 jniLibs 打包提供），
 * dlsym 解析 Python C API，由 Kotlin 侧 com.ai.assistance.quro.core.python.PyEngine
 * 经 JNI 调用。fdroid 风味不含预编译库 → dlopen 失败 → Kotlin 侧自动降级（Brython）。
 *
 * 初始化遵循 python.org 官方 Android 嵌入指南（docs.python.org/3/using/android.html）：
 *  - PYTHONHOME 指向首启从 assets 解压出的 filesDir/python；
 *  - 标准库在其 lib/python3.14 下，lib-dynload 的 C 扩展随标准库一起解压加载；
 *  - libpython3.14.so / libssl_python.so / libcrypto_python.so / libsqlite3_python.so 走 jniLibs。
 *
 * 输出捕获：Python 层把 sys.stdout / sys.stderr 换成 io.StringIO，
 * 代码跑完经 C API getvalue() 取回，一次运行同时拿到 stdout 与 stderr。
 * 超时中断：Kotlin 侧看门狗线程到点调 nativeInterrupt → PyErr_SetInterrupt
 * → 字节码边界抛 KeyboardInterrupt，安全打断（不杀进程、不破解释器）。
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "PyBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct _object PyObject;

static void *pylib = NULL;
static int initialized = 0;
static pthread_mutex_t init_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- dlsym 解析的 Python C API ---- */
static void (*p_Py_Initialize)(void);
static int (*p_Py_IsInitialized)(void);
static int (*p_PyRun_SimpleString)(const char *);
static PyObject *(*p_PyImport_ImportModule)(const char *);
static PyObject *(*p_PyObject_GetAttrString)(PyObject *, const char *);
static PyObject *(*p_PyObject_CallMethod)(PyObject *, const char *, const char *, ...);
static const char *(*p_PyUnicode_AsUTF8)(PyObject *);
static void (*p_Py_DecRef)(PyObject *);
static int (*p_PyGILState_Ensure)(void);   /* PyGILState_STATE 是 int 枚举 */
static void (*p_PyGILState_Release)(int);
static void (*p_PyErr_SetInterrupt)(void);

static int resolve_all(void) {
#define RESOLVE(sym, name) \
    do { \
        *(void **)(&sym) = dlsym(pylib, name); \
        if (!(sym)) { LOGE("dlsym 缺少 %s", name); return -1; } \
    } while (0)
    RESOLVE(p_Py_Initialize, "Py_Initialize");
    RESOLVE(p_Py_IsInitialized, "Py_IsInitialized");
    RESOLVE(p_PyRun_SimpleString, "PyRun_SimpleString");
    RESOLVE(p_PyImport_ImportModule, "PyImport_ImportModule");
    RESOLVE(p_PyObject_GetAttrString, "PyObject_GetAttrString");
    RESOLVE(p_PyObject_CallMethod, "PyObject_CallMethod");
    RESOLVE(p_PyUnicode_AsUTF8, "PyUnicode_AsUTF8");
    RESOLVE(p_Py_DecRef, "Py_DecRef");
    RESOLVE(p_PyGILState_Ensure, "PyGILState_Ensure");
    RESOLVE(p_PyGILState_Release, "PyGILState_Release");
    RESOLVE(p_PyErr_SetInterrupt, "PyErr_SetInterrupt");
#undef RESOLVE
    return 0;
}

/** sys.stdout / sys.stderr 上调 getvalue()，把结果变成 jstring（失败返回 NULL）。 */
static jstring stream_to_jstring(JNIEnv *env, PyObject *stream_obj) {
    if (!stream_obj) return NULL;
    PyObject *val = p_PyObject_CallMethod(stream_obj, "getvalue", NULL);
    if (!val) return NULL;
    const char *utf8 = p_PyUnicode_AsUTF8(val);
    jstring result = utf8 ? (*env)->NewStringUTF(env, utf8) : NULL;
    p_Py_DecRef(val);
    return result;
}

static jstring attr_stream_to_jstring(JNIEnv *env, PyObject *module, const char *attr) {
    PyObject *stream_obj = p_PyObject_GetAttrString(module, attr);
    if (!stream_obj) return NULL;
    jstring result = stream_to_jstring(env, stream_obj);
    p_Py_DecRef(stream_obj);
    return result;
}

/*
 * class:  com.ai.assistance.quro.core.python.PyEngine
 * method: nativeInit(String home) → null=就绪；否则错误说明。
 */
JNIEXPORT jstring JNICALL
Java_com_ai_assistance_quro_core_python_PyEngine_nativeInit(JNIEnv *env, jobject thiz, jstring jhome) {
    (void) thiz;
    pthread_mutex_lock(&init_lock);
    if (initialized) { pthread_mutex_unlock(&init_lock); return NULL; }

    const char *home = (*env)->GetStringUTFChars(env, jhome, NULL);
    if (!home) { pthread_mutex_unlock(&init_lock); return (*env)->NewStringUTF(env, "GetStringUTFChars 失败"); }

    char errbuf[512];
    pylib = dlopen("libpython3.14.so", RTLD_NOW | RTLD_GLOBAL);
    if (!pylib) {
        snprintf(errbuf, sizeof errbuf, "dlopen libpython3.14.so 失败：%s", dlerror());
        (*env)->ReleaseStringUTFChars(env, jhome, home);
        pthread_mutex_unlock(&init_lock);
        return (*env)->NewStringUTF(env, errbuf);
    }
    if (resolve_all() != 0) {
        (*env)->ReleaseStringUTFChars(env, jhome, home);
        pthread_mutex_unlock(&init_lock);
        return (*env)->NewStringUTF(env, "libpython3.14.so 符号解析不完整（版本不匹配？）");
    }

    setenv("PYTHONHOME", home, 1);
    setenv("PYTHONPATH", home, 1);
    p_Py_Initialize();

    /* 装 SIGINT 默认处理器，保证 PyErr_SetInterrupt 表现为 KeyboardInterrupt */
    p_PyRun_SimpleString(
        "import signal\n"
        "try:\n"
        "    signal.signal(signal.SIGINT, signal.default_int_handler)\n"
        "except (ValueError, OSError):\n"
        "    pass\n");

    initialized = 1;
    (*env)->ReleaseStringUTFChars(env, jhome, home);
    pthread_mutex_unlock(&init_lock);
    return NULL;
}

/*
 * method: nativeRun(String code, String[] out) → 0=执行完毕（无论用户代码是否抛错）。
 * out[0]=stdout，out[1]=stderr（traceback 在这里）。引擎未初始化返回 -1。
 */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_quro_core_python_PyEngine_nativeRun(JNIEnv *env, jobject thiz, jstring jcode, jobjectArray out) {
    (void) thiz;
    if (!initialized) return -1;

    const char *code = (*env)->GetStringUTFChars(env, jcode, NULL);
    if (!code) return -1;

    int gil = p_PyGILState_Ensure();

    /* 1. 输出重定向到 StringIO */
    int rc = p_PyRun_SimpleString(
        "import sys, io\n"
        "__quro_out = io.StringIO()\n"
        "__quro_err = io.StringIO()\n"
        "sys.stdout = __quro_out\n"
        "sys.stderr = __quro_err\n");

    /* 2. 用户代码：PyRun_SimpleString 出错时自身会把 traceback 打进 sys.stderr（__quro_err） */
    if (rc == 0) rc = p_PyRun_SimpleString(code);

    /* 3. 取回两个流的内容（同时恢复 sys 流，避免脏状态留给下一次） */
    jstring jout = NULL, jerr = NULL;
    PyObject *sysmod = p_PyImport_ImportModule("sys");
    if (sysmod) {
        jout = attr_stream_to_jstring(env, sysmod, "stdout");
        jerr = attr_stream_to_jstring(env, sysmod, "stderr");
        p_Py_DecRef(sysmod);
    }
    p_PyRun_SimpleString(
        "import sys\n"
        "sys.stdout = sys.__stdout__\n"
        "sys.stderr = sys.__stderr__\n");

    if (out && (*env)->GetArrayLength(env, out) >= 2) {
        (*env)->SetObjectArrayElement(env, out, 0,
            jout ? jout : (*env)->NewStringUTF(env, ""));
        (*env)->SetObjectArrayElement(env, out, 1,
            jerr ? jerr : (*env)->NewStringUTF(env, ""));
    }

    (*env)->ReleaseStringUTFChars(env, jcode, code);
    p_PyGILState_Release(gil);
    return 0;
}

/*
 * method: nativeInterrupt() — 看门狗超时调用：设置解释器中断标志，
 * 用户代码在下一个字节码边界收到 KeyboardInterrupt。
 */
JNIEXPORT void JNICALL
Java_com_ai_assistance_quro_core_python_PyEngine_nativeInterrupt(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (initialized && p_Py_IsInitialized()) p_PyErr_SetInterrupt();
}
