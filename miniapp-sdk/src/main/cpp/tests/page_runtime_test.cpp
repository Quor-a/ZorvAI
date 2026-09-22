// End-to-end check: run the real demo pages (assets/miniprograms/**) inside the
// self-developed engine with the same glue the Android runtime injects.
// Desktop build only (see CMakeLists.txt).
#include "../js_engine.h"
#include <iostream>
#include <fstream>
#include <sstream>

using namespace mini::js;

static int gPass = 0, gFail = 0;

static void check(const std::string& name, const std::string& got, const std::string& expect) {
    if (got == expect) {
        gPass++;
        std::cout << "  PASS  " << name << "\n";
    } else {
        gFail++;
        std::cout << "  FAIL  " << name << "\n        expected: " << expect << "\n        got     : " << got << "\n";
    }
}

static std::string readFile(const std::string& path) {
    std::ifstream in(path);
    if (!in) return std::string();
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

// Same glue as LogicRuntime.kt injects (App / Page / setData / wx.*).
static const char* kGlue = R"JS(
var __page = null;
var __app = null;
var __pageData = {};
var __pageOptions = {};

function App(options) {
  __app = options || {};
  if (typeof __app.onLaunch === 'function') { __app.onLaunch({}); }
}

function Page(options) {
  __page = options || {};
  __pageData = {};
  var src = (__page.data || {});
  for (var k in src) { __pageData[k] = src[k]; }
  __page.data = __pageData;
  __page.setData = function (patch) {
    for (var k in patch) { __pageData[k] = patch[k]; }
    __native('setData', JSON.stringify(__pageData));
  };
  if (typeof __page.onLoad === 'function') { __page.onLoad(__pageOptions); }
}

function getApp() { return __app; }

var wx = {
  getSystemInfoSync: function () { return JSON.parse(__wx_getSystemInfoSync()); },
  showToast: function (o) { return __wx_showToast(o); },
  getStorageSync: function (k) { return __wx_getStorageSync(k); },
  setStorageSync: function (k, v) { return __wx_setStorageSync(k, v); }
};
globalThis.wx = wx;

function __dispatch(name, argsJson) {
  if (!__page) { return null; }
  var fn = __page[name];
  if (typeof fn !== 'function') { return null; }
  var args = [];
  try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }
  return fn.apply(__page, args);
}
)JS";

// Extracts the "v" payload from the engine envelope, unquoting strings.
static std::string payload(const std::string& envelope) {
    size_t p = envelope.find("\"v\":");
    if (p == std::string::npos) return envelope;
    std::string rest = envelope.substr(p + 4);
    size_t end = rest.find_last_of('}');
    std::string v = rest.substr(0, end == std::string::npos ? std::string::npos : end);
    if (v.size() >= 2 && v.front() == '"' && v.back() == '"') return v.substr(1, v.size() - 2);
    return v;
}

static std::string evalStr(Engine& e, const std::string& src) {
    std::string r = e.evaluate(src);
    if (r.find("\"error\"") != std::string::npos) {
        std::cout << "        engine error: " << r << "\n";
        return std::string();
    }
    return r;
}

static std::string evalValue(Engine& e, const std::string& src) {
    return payload(evalStr(e, src));
}


static std::string gLastSetData;

static void installHosts(Engine& e) {
    e.registerHostFunction("__native", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
        if (!args.empty() && args[0].isString() && args[0].str == "setData" && args.size() > 1) {
            gLastSetData = args[1].isString() ? args[1].str : std::string("{}");
        }
        return Value::of(true);
    });
    e.registerHostFunction("__wx_getSystemInfoSync", [](Interpreter*, const Value&, const std::vector<Value>&) -> Value {
        return Value::of(std::string("{\"platform\":\"android\",\"model\":\"Pixel\",\"windowWidth\":1080,\"windowHeight\":1920}"));
    });
    e.registerHostFunction("__wx_showToast", [](Interpreter*, const Value&, const std::vector<Value>&) -> Value {
        return Value::of(0.0);
    });
    static std::string storage;
    e.registerHostFunction("__wx_getStorageSync", [](Interpreter*, const Value&, const std::vector<Value>&) -> Value {
        return storage.empty() ? Value() : Value::of(storage);
    });
    e.registerHostFunction("__wx_setStorageSync", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
        if (args.size() > 1 && args[1].isString()) storage = args[1].str;
        return Value::of(true);
    });
}

int main(int argc, char** argv) {
    std::string base = argc > 1 ? argv[1] : "../../../../../app/src/main/assets/miniprograms";

    // ---------------- hello demo ----------------
    {
        Engine e;
        installHosts(e);
        evalStr(e, kGlue);
        std::string appJs = readFile(base + "/hello/app.js");
        std::string pageJs = readFile(base + "/hello/pages/index/index.js");
        check("hello: app.js 已加载", appJs.empty() ? "missing" : "ok", "ok");
        check("hello: page.js 已加载", pageJs.empty() ? "missing" : "ok", "ok");

        std::string r1 = e.evaluate(appJs);
        check("hello: app.js 无运行时错误", r1.find("\"error\"") != std::string::npos ? "error" : "ok", "ok");
        std::string r2 = e.evaluate(pageJs);
        check("hello: page.js 无运行时错误", r2.find("\"error\"") != std::string::npos ? "error" : "ok", "ok");

        check("hello: onLoad 初始化数据",
              evalValue(e, "JSON.stringify(__pageData.count)"), "0");
        evalStr(e, "__dispatch('onPlus')");
        evalStr(e, "__dispatch('onPlus')");
        evalStr(e, "__dispatch('onPlus')");
        check("hello: 三次 onPlus 后 count", evalValue(e, "JSON.stringify(__pageData.count)"), "3");
        check("hello: setData 已推送到宿主", gLastSetData.find("\"count\":3") != std::string::npos ? "ok" : gLastSetData, "ok");

        evalStr(e, "__dispatch('onMinus')");
        check("hello: onMinus 后 count", evalValue(e, "JSON.stringify(__pageData.count)"), "2");

        evalStr(e, "__dispatch('onSystemInfo')");
        check("hello: wx.getSystemInfoSync 结果写入 data",
              evalValue(e, "__pageData.systemInfo.indexOf('android') >= 0 ? 'yes' : 'no'"), "yes");
        check("hello: 列表数据渲染源", evalValue(e, "JSON.stringify(__pageData.items.length)"), "4");
        check("hello: 生命周期 onReady 可调用", evalValue(e, "__dispatch('onReady') === undefined ? 'ok' : 'ok'"), "ok");
    }

    // ---------------- todo demo ----------------
    {
        Engine e;
        installHosts(e);
        evalStr(e, kGlue);
        std::string appJs = readFile(base + "/todo/app.js");
        std::string pageJs = readFile(base + "/todo/pages/index/index.js");
        check("todo: 源码已加载", (appJs.empty() || pageJs.empty()) ? "missing" : "ok", "ok");

        std::string r1 = e.evaluate(appJs);
        check("todo: app.js 无运行时错误", r1.find("\"error\"") != std::string::npos ? "error" : "ok", "ok");
        std::string r2 = e.evaluate(pageJs);
        check("todo: page.js 无运行时错误", r2.find("\"error\"") != std::string::npos ? "error" : "ok", "ok");

        evalStr(e, "__pageData.draft = '写自研引擎';");
        evalStr(e, "__dispatch('onAdd')");
        evalStr(e, "__pageData.draft = '写自研布局';");
        evalStr(e, "__dispatch('onAdd')");
        check("todo: 添加两条", evalValue(e, "JSON.stringify(__pageData.todos.length)"), "2");
        check("todo: 统计 total", evalValue(e, "JSON.stringify(__pageData.total)"), "2");

        // 模拟点击 (e.target.dataset.index)
        evalStr(e, "__dispatch('onToggle', '[{\"target\":{\"dataset\":{\"index\":\"0\"}}}]')");
        check("todo: 勾选后 doneCount", evalValue(e, "JSON.stringify(__pageData.doneCount)"), "1");
        check("todo: 勾选后 done 标记", evalValue(e, "JSON.stringify(__pageData.todos[0].done)"), "true");

        evalStr(e, "__dispatch('onRemove', '[{\"target\":{\"dataset\":{\"index\":\"1\"}}}]')");
        check("todo: 删除后剩余", evalValue(e, "JSON.stringify(__pageData.todos.length)"), "1");

        evalStr(e, "__dispatch('onClearFinished')");
        check("todo: 清除已完成", evalValue(e, "JSON.stringify(__pageData.todos.length)"), "0");

        // 空输入不应新增
        evalStr(e, "__pageData.draft = '   ';");
        evalStr(e, "__dispatch('onAdd')");
        check("todo: 空输入被忽略", evalValue(e, "JSON.stringify(__pageData.todos.length)"), "0");
    }

    std::cout << "\n==== 页面运行时: 通过 " << gPass << " / 失败 " << gFail << " ====\n";
    return gFail == 0 ? 0 : 1;
}
