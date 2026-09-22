#include "js_engine.h"
#include <memory>
#include <sstream>

namespace mini {
namespace js {

// ---- JSON <-> Value helpers (shared with the JNI layer) ----
namespace jsonbridge {

void jsonStringifyValue(Interpreter* I, const Value& v, std::string& out);

std::string escape(const std::string& s) {
  std::string out;
  for (unsigned char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char b[8];
          std::snprintf(b, sizeof(b), "\\u%04x", c);
          out += b;
        } else out.push_back((char)c);
    }
  }
  return out;
}

void jsonStringifyValue(Interpreter* I, const Value& v, std::string& out) {
  switch (v.type) {
    case Type::Undefined: out += "{\"t\":\"undefined\"}"; break;
    case Type::Null: out += "{\"t\":\"null\"}"; break;
    case Type::Bool: out += std::string("{\"t\":\"boolean\",\"v\":") + (v.boolean ? "true" : "false") + "}"; break;
    case Type::Number:
      if (std::isnan(v.num) || std::isinf(v.num)) out += "{\"t\":\"null\"}";
      else out += "{\"t\":\"number\",\"v\":" + numberToString(v.num) + "}";
      break;
    case Type::String: out += "{\"t\":\"string\",\"v\":\"" + escape(v.str) + "\"}"; break;
    case Type::Function: {
      int id = I->functionTableId(v);
      out += "{\"t\":\"function\",\"v\":" + std::to_string(id) + "}";
      break;
    }
    case Type::Object: {
      std::string raw;
      jsonStringifyInto(I, raw, v, 0, 0, {});
      out += "{\"t\":\"object\",\"v\":" + raw + "}";
      break;
    }
  }
}

}  // namespace jsonbridge

Engine::Engine() : interp_(new Interpreter()) {
  Interpreter* I = interp_.get();
  I->functionTableRef = &functionTable_;
  I->registerNative("__registerFunction", [this](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isFunction()) return Value::of(-1.0);
    return Value::of((double)registerFunction(args[0]));
  });
  I->registerNative("__invokeFunction", [this](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty()) return Value();
    int id = (int)toNumber(args[0]);
    std::string json = args.size() > 1 ? toString(interp_.get(), args[1]) : "[]";
    (void)json;
    return Value();
  });
}

Engine::~Engine() {}

int Engine::registerFunction(const Value& fn) {
  functionTable_.push_back(fn);
  return (int)functionTable_.size() - 1;
}

void Engine::registerHostFunction(const std::string& name, NativeFn fn) { interp_->registerNative(name, std::move(fn)); }

void Engine::setLogger(std::function<void(const std::string&, const std::string&)> log) {
  interp_->hostLog = std::move(log);
}

void Engine::setTimerBridge(std::function<int(int, bool, int)> setTimer, std::function<void(int)> clear) {
  interp_->hostTimer = std::move(setTimer);
  interp_->hostClearTimer = std::move(clear);
}

std::string Engine::evaluate(const std::string& source) {
  try {
    Value v = interp_->eval(source);
    std::string out;
    jsonbridge::jsonStringifyValue(interp_.get(), v, out);
    return out;
  } catch (JsException& e) {
    return std::string("{\"t\":\"error\",\"v\":\"") + jsonbridge::escape(interp_->lastError) + "\"}";
  } catch (std::exception& ex) {
    return std::string("{\"t\":\"error\",\"v\":\"") + jsonbridge::escape(ex.what()) + "\"}";
  }
}

std::string Engine::callFunction(const std::string& name, const std::string& argsJson) {
  (void)argsJson;
  try {
    Value fn = interp_->getGlobal(name);
    if (!fn.isFunction()) return "{\"t\":\"error\",\"v\":\"not a function: " + name + "\"}";
    Value r = interp_->callValue(fn, Value(), {});
    std::string out;
    jsonbridge::jsonStringifyValue(interp_.get(), r, out);
    return out;
  } catch (JsException& e) {
    return std::string("{\"t\":\"error\",\"v\":\"") + jsonbridge::escape(interp_->lastError) + "\"}";
  }
}

std::string Engine::invokeFunctionById(int fnId, const std::string& argsJson) {
  try {
    if (fnId < 0 || fnId >= (int)functionTable_.size()) return "{\"t\":\"error\",\"v\":\"bad function id\"}";
    std::vector<Value> args;
    if (!argsJson.empty() && argsJson != "null") {
      Value parsed = interp_->getGlobal("JSON");
      Value parseFn = interp_->getMember(parsed, "parse");
      Value arr = interp_->callValue(parseFn, parsed, {Value::of(argsJson)});
      if (arr.isObject() && arr.obj->className == "Array") {
        double len = toNumber(arr.obj->props["length"]);
        for (size_t i = 0; i < (size_t)len; i++) args.push_back(arr.obj->props[std::to_string(i)]);
      } else {
        args.push_back(arr);
      }
    }
    Value r = interp_->callValue(functionTable_[fnId], Value(), args);
    std::string out;
    jsonbridge::jsonStringifyValue(interp_.get(), r, out);
    return out;
  } catch (JsException& e) {
    return std::string("{\"t\":\"error\",\"v\":\"") + jsonbridge::escape(interp_->lastError) + "\"}";
  }
}

}  // namespace js
}  // namespace mini
