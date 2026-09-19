// JNI bridge between the Kotlin SDK layer and the self-developed C++ JS engine.
#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>

#include "js_engine.h"
#include "js_value.h"

using namespace mini::js;

static JavaVM* g_vm = nullptr;

// Cache of callback targets resolved lazily on first host call.
static jclass g_bridgeClass = nullptr;
static jmethodID g_invokeHost = nullptr;
static jmethodID g_logHost = nullptr;
static jmethodID g_timerHost = nullptr;
static jmethodID g_clearTimerHost = nullptr;

static std::mutex g_registryMutex;
static std::unordered_map<std::string, bool> g_pendingHostNames;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

static JNIEnv* attachEnv() {
  if (!g_vm) return nullptr;
  JNIEnv* env = nullptr;
  if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env) return env;
  if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
  return nullptr;
}

static void detachIfNeeded() {
  // Keep the thread attached: the logic thread is long-lived and reuses it.
}

static void ensureBridgeClass(JNIEnv* env) {
  if (g_bridgeClass) return;
  jclass local = env->FindClass("com/yuanbao/miniapp/js/JsBridge");
  if (!local) return;
  g_bridgeClass = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  if (!g_bridgeClass) return;
  g_invokeHost = env->GetStaticMethodID(g_bridgeClass, "invokeHost",
                                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
  g_logHost = env->GetStaticMethodID(g_bridgeClass, "onLog",
                                     "(Ljava/lang/String;Ljava/lang/String;)V");
  g_timerHost = env->GetStaticMethodID(g_bridgeClass, "onScheduleTimer", "(III)I");
  g_clearTimerHost = env->GetStaticMethodID(g_bridgeClass, "onClearTimer", "(I)V");
}

static std::string jstringToStd(JNIEnv* env, jstring s) {
  if (!s) return std::string();
  const char* c = env->GetStringUTFChars(s, nullptr);
  if (!c) return std::string();
  std::string out(c);
  env->ReleaseStringUTFChars(s, c);
  return out;
}

static jstring stdToJstring(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

// Convert a JSON string produced by the Kotlin side into a JS Value.
static Value jsonToValue(Interpreter* I, const std::string& json) {
  Value global = I->getGlobal("JSON");
  Value parse = I->getMember(global, "parse");
  if (!parse.isFunction()) return Value();
  return I->callValue(parse, global, {Value::of(json)});
}

static std::string valueToJson(Interpreter* I, const Value& v) {
  Value global = I->getGlobal("JSON");
  Value stringify = I->getMember(global, "stringify");
  if (!stringify.isFunction()) return "null";
  Value r = I->callValue(stringify, global, {v});
  return r.isString() ? r.str : std::string("null");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeCreate(JNIEnv* env, jclass) {
  ensureBridgeClass(env);
  Engine* engine = new Engine();

  engine->setLogger([](const std::string& level, const std::string& msg) {
    JNIEnv* e = attachEnv();
    if (!e || !g_logHost) return;
    jstring l = stdToJstring(e, level);
    jstring m = stdToJstring(e, msg);
    e->CallStaticVoidMethod(g_bridgeClass, g_logHost, l, m);
    e->DeleteLocalRef(l);
    e->DeleteLocalRef(m);
  });

  engine->setTimerBridge(
      [](int delayMs, bool repeat, int fnId) -> int {
        JNIEnv* e = attachEnv();
        if (!e || !g_timerHost) return 0;
        return e->CallStaticIntMethod(g_bridgeClass, g_timerHost, delayMs, repeat ? 1 : 0, fnId);
      },
      [](int id) {
        JNIEnv* e = attachEnv();
        if (!e || !g_clearTimerHost) return;
        e->CallStaticVoidMethod(g_bridgeClass, g_clearTimerHost, id);
      });

  return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  delete engine;
  detachIfNeeded();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeEvaluate(JNIEnv* env, jclass, jlong handle, jstring source) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (!engine) return stdToJstring(env, "{\"t\":\"error\",\"v\":\"no engine\"}");
  std::string result = engine->evaluate(jstringToStd(env, source));
  return stdToJstring(env, result);
}

// Registers a host function: when JS calls `name(...)`, the Kotlin bridge callback runs.
extern "C" JNIEXPORT void JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeRegisterHostFunction(JNIEnv* env, jclass, jlong handle,
                                                                jstring name) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (!engine) return;
  std::string fnName = jstringToStd(env, name);
  engine->registerHostFunction(fnName, [fnName](Interpreter* I, const Value&,
                                                const std::vector<Value>& args) -> Value {
    JNIEnv* e = attachEnv();
    if (!e || !g_invokeHost) return Value();
    std::string argsJson = "[";
    for (size_t i = 0; i < args.size(); i++) {
      if (i) argsJson += ",";
      argsJson += valueToJson(I, args[i]);
    }
    argsJson += "]";
    jstring n = stdToJstring(e, fnName);
    jstring a = stdToJstring(e, argsJson);
    jstring ret = static_cast<jstring>(e->CallStaticObjectMethod(g_bridgeClass, g_invokeHost, n, a));
    std::string out = jstringToStd(e, ret);
    e->DeleteLocalRef(n);
    e->DeleteLocalRef(a);
    if (ret) e->DeleteLocalRef(ret);
    if (out.empty()) return Value();
    return jsonToValue(I, out);
  });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeInvokeFunction(JNIEnv* env, jclass, jlong handle,
                                                          jint fnId, jstring argsJson) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (!engine) return stdToJstring(env, "null");
  std::string out = engine->invokeFunctionById(fnId, jstringToStd(env, argsJson));
  return stdToJstring(env, out);
}

// Evaluates `source` and stores the resulting function in the engine function table;
// returns the id so Kotlin can invoke it later (setData callbacks, event handlers...).
extern "C" JNIEXPORT jint JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeCompileFunction(JNIEnv* env, jclass, jlong handle,
                                                           jstring source) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (!engine) return -1;
  std::string wrapped = std::string("(") + jstringToStd(env, source) + ")";
  Value v = engine->interp()->eval(wrapped);
  if (!v.isFunction()) return -1;
  return engine->registerFunction(v);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeCallGlobal(JNIEnv* env, jclass, jlong handle,
                                                      jstring name, jstring argsJson) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (!engine) return stdToJstring(env, "null");
  std::string fnName = jstringToStd(env, name);
  Value fn = engine->interp()->getGlobal(fnName);
  if (!fn.isFunction()) return stdToJstring(env, "null");
  std::vector<Value> args;
  Value parsed = jsonToValue(engine->interp(), jstringToStd(env, argsJson));
  if (parsed.isObject() && parsed.obj->className == "Array") {
    auto it = parsed.obj->props.find("length");
    double len = it == parsed.obj->props.end() ? 0 : toNumber(it->second);
    for (size_t i = 0; i < (size_t)len; i++) args.push_back(parsed.obj->props[std::to_string(i)]);
  }
  try {
    Value r = engine->interp()->callValue(fn, Value(), args);
    return stdToJstring(env, valueToJson(engine->interp(), r));
  } catch (JsException&) {
    return stdToJstring(env, "null");
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuanbao_miniapp_js_JsNative_nativeLastError(JNIEnv* env, jclass, jlong handle) {
  Engine* engine = reinterpret_cast<Engine*>(handle);
  if (!engine) return stdToJstring(env, "");
  return stdToJstring(env, engine->lastError());
}
