#include "js_runtime.h"
#include "js_lexer.h"
#include "js_parser.h"
#include <sstream>
#include <iomanip>
#include <cmath>
#include <cstdio>

namespace mini {
namespace js {

// ---------------- conversions ----------------
bool toBool(const Value& v) {
  switch (v.type) {
    case Type::Undefined:
    case Type::Null: return false;
    case Type::Bool: return v.boolean;
    case Type::Number: return !(std::isnan(v.num) || v.num == 0);
    case Type::String: return !v.str.empty();
    default: return true;
  }
}

std::string numberToString(double d) {
  if (std::isnan(d)) return "NaN";
  if (std::isinf(d)) return d > 0 ? "Infinity" : "-Infinity";
  if (d == static_cast<double>(static_cast<long long>(d)) && std::fabs(d) < 1e21) {
    return std::to_string(static_cast<long long>(d));
  }
  std::ostringstream os;
  os << std::setprecision(17) << d;
  std::string s = os.str();
  // trim trailing zeros
  if (s.find('.') != std::string::npos) {
    while (s.size() > 1 && s.back() == '0') s.pop_back();
    if (s.back() == '.') s.pop_back();
  }
  return s;
}

double toNumber(const Value& v) {
  switch (v.type) {
    case Type::Undefined: return NAN;
    case Type::Null: return 0;
    case Type::Bool: return v.boolean ? 1 : 0;
    case Type::Number: return v.num;
    case Type::String: {
      std::string s = v.str;
      size_t i = 0;
      while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
      std::string rest = s.substr(i);
      if (rest.empty()) return 0;
      if (rest[0] == '+' || rest[0] == '-' || std::isdigit((unsigned char)rest[0]) || rest[0] == '.') {
        try {
          size_t used = 0;
          double d = std::stod(rest, &used);
          return d;
        } catch (...) {
          return NAN;
        }
      }
      return NAN;
    }
    case Type::Object: {
      auto it = v.obj->props.find("length");
      (void)it;
      return NAN;
    }
    default: return NAN;
  }
}

std::string toString(Interpreter* interp, const Value& v) {
  switch (v.type) {
    case Type::Undefined: return "undefined";
    case Type::Null: return "null";
    case Type::Bool: return v.boolean ? "true" : "false";
    case Type::Number: return numberToString(v.num);
    case Type::String: return v.str;
    case Type::Function: {
      if (v.fn && !v.fn->name.empty()) return "function " + v.fn->name + "()";
      return "function ()";
    }
    case Type::Object: {
      // try toString / join
      Value ts = interp ? interp->getMember(v, "toString") : Value();
      if (ts.isFunction() && ts.fn && !ts.fn->isNative) {
        Value r = interp->callFunction(ts.fn, v, {});
        if (r.isString()) return r.str;
      }
      if (v.obj && v.obj->className == "Array") {
        Value jn = interp ? interp->getMember(v, "join") : Value();
        if (jn.isFunction()) {
          Value r = interp->callValue(jn, v, {Value::of(",")});
          if (r.isString()) return r.str;
        }
      }
      return "[object Object]";
    }
  }
  return "";
}

bool strictEquals(const Value& a, const Value& b) {
  if (a.type != b.type) return false;
  switch (a.type) {
    case Type::Undefined:
    case Type::Null: return true;
    case Type::Bool: return a.boolean == b.boolean;
    case Type::Number: return a.num == b.num;
    case Type::String: return a.str == b.str;
    default: return a.obj == b.obj || a.fn == b.fn;
  }
}

bool looseEquals(Interpreter* interp, const Value& a, const Value& b) {
  if (a.type == b.type) return strictEquals(a, b);
  if ((a.isNull() && b.isUndefined()) || (a.isUndefined() && b.isNull())) return true;
  if (a.isNumber() && b.isString()) return looseEquals(interp, a, Value::of(toNumber(b)));
  if (a.isString() && b.isNumber()) return looseEquals(interp, Value::of(toNumber(a)), b);
  if (a.isBool()) return looseEquals(interp, Value::of((double)(a.boolean ? 1 : 0)), b);
  if (b.isBool()) return looseEquals(interp, a, Value::of((double)(b.boolean ? 1 : 0)));
  if ((a.isNumber() || a.isString()) && (b.isObject() || b.isFunction())) {
    Value prim = interp ? interp->getMember(b, "valueOf") : Value();
    if (prim.isFunction()) {
      Value r = interp->callValue(prim, b, {});
      if (!r.isObject()) return looseEquals(interp, a, r);
    }
    return false;
  }
  return false;
}

// ---------------- Interpreter ----------------
Interpreter::Interpreter() {
  objectProto = std::make_shared<JsObject>();
  objectProto->className = "Object";
  arrayProto = std::make_shared<JsObject>();
  arrayProto->className = "Array";
  arrayProto->proto = objectProto;
  functionProto = std::make_shared<JsObject>();
  functionProto->className = "Function";
  functionProto->proto = objectProto;
  stringProto = std::make_shared<JsObject>();
  stringProto->proto = objectProto;
  numberProto = std::make_shared<JsObject>();
  numberProto->proto = objectProto;
  boolProto = std::make_shared<JsObject>();
  boolProto->proto = objectProto;
  errorProto = std::make_shared<JsObject>();
  errorProto->proto = objectProto;
  regexProto = std::make_shared<JsObject>();
  regexProto->proto = objectProto;

  global_ = std::make_shared<Environment>();
  global_->isFunctionScope = true;
  initBuiltins();
}

Interpreter::~Interpreter() {}

void Interpreter::registerNative(const std::string& name, NativeFn fn) {
  auto f = std::make_shared<JsFunction>();
  f->isNative = true;
  f->name = name;
  f->native = std::move(fn);
  Value v;
  v.type = Type::Function;
  v.fn = f;
  v.obj = makeFunctionObject(f);
  f->obj = v.obj;
  global_->vars[name] = v;
}

void Interpreter::setGlobal(const std::string& name, const Value& v) { global_->vars[name] = v; }
Value Interpreter::getGlobal(const std::string& name) { return global_->get(name); }

std::shared_ptr<JsObject> Interpreter::makeObject(const std::string& cls) {
  auto o = std::make_shared<JsObject>();
  o->className = cls;
  o->proto = objectProto;
  return o;
}

std::shared_ptr<JsObject> Interpreter::makeArrayObject() {
  auto o = std::make_shared<JsObject>();
  o->className = "Array";
  o->proto = arrayProto;
  o->props["length"] = Value::of(0.0);
  return o;
}

std::shared_ptr<JsObject> Interpreter::makeFunctionObject(std::shared_ptr<JsFunction> fn) {
  auto o = std::make_shared<JsObject>();
  o->className = "Function";
  o->proto = functionProto;
  auto proto = makeObject("Object");
  proto->props["constructor"] = [&]() {
    Value v;
    v.type = Type::Function;
    v.fn = fn;
    return v;
  }();
  o->props["prototype"] = Value{Type::Object, 0, false, "", proto, nullptr};
  o->props["name"] = Value::of(fn->name);
  o->props["length"] = Value::of((double)fn->params.size());
  return o;
}

Value Interpreter::makeStringObject(const std::string& s) {
  auto o = std::make_shared<JsObject>();
  o->className = "String";
  o->proto = stringProto;
  o->props["length"] = Value::of((double)s.size());
  for (size_t i = 0; i < s.size(); i++) {
    o->props[std::to_string(i)] = Value::of(std::string(1, s[i]));
  }
  Value v;
  v.type = Type::Object;
  v.obj = o;
  return v;
}

Value Interpreter::makeError(const std::string& msg, const std::string& cls) {
  auto o = std::make_shared<JsObject>();
  o->className = cls;
  o->proto = errorProto;
  o->props["message"] = Value::of(msg);
  o->props["name"] = Value::of(cls);
  o->props["stack"] = Value::of(cls + ": " + msg);
  Value v;
  v.type = Type::Object;
  v.obj = o;
  return v;
}

void Interpreter::throwError(const std::string& msg, const std::string& cls) {
  Value e = makeError(msg, cls);
  lastError = cls + ": " + msg;
  throw JsException(e, cls + ": " + msg);
}

void Interpreter::throwValue(const Value& v) {
  lastError = toString(this, v);
  throw JsException(v, lastError);
}

Value Interpreter::getMemberRecursive(std::shared_ptr<JsObject> obj, const std::string& key) {
  std::shared_ptr<JsObject> cur = obj;
  int guard = 0;
  while (cur && guard++ < 64) {
    auto it = cur->props.find(key);
    if (it != cur->props.end()) return it->second;
    cur = cur->proto.lock();
  }
  return Value();
}

Value Interpreter::getMember(const Value& obj, const std::string& key) {
  if (obj.type == Type::Object && obj.obj->className == "Global") {
    return global_->get(key);
  }
  if (obj.type == Type::Object) {
    if (obj.obj->className == "Array" && key == "length") {
      auto it = obj.obj->props.find("length");
      return it == obj.obj->props.end() ? Value::of(0.0) : it->second;
    }
    if (obj.obj->className == "String") {
      auto it = obj.obj->props.find(key);
      if (it != obj.obj->props.end()) return it->second;
      return getMemberRecursive(obj.obj, key);
    }
    return getMemberRecursive(obj.obj, key);
  }
  if (obj.type == Type::String) {
    if (key == "length") return Value::of((double)obj.str.size());
    if (!obj.str.empty() && key.size() > 0 && key.find_first_not_of("0123456789") == std::string::npos) {
      size_t i = (size_t)std::stoll(key);
      if (i < obj.str.size()) return Value::of(std::string(1, obj.str[i]));
    }
    return getMemberRecursive(stringProto, key);
  }
  if (obj.type == Type::Number) return getMemberRecursive(numberProto, key);
  if (obj.type == Type::Bool) return getMemberRecursive(boolProto, key);
  if (obj.type == Type::Function) {
    if (obj.obj) {
      auto it = obj.obj->props.find(key);
      if (it != obj.obj->props.end()) return it->second;
      return getMemberRecursive(obj.obj, key);
    }
    if (obj.fn && obj.fn->obj) return getMemberRecursive(obj.fn->obj, key);
    return getMemberRecursive(functionProto, key);
  }
  if (obj.type == Type::Undefined || obj.type == Type::Null) {
    throwError("Cannot read property '" + key + "' of " + obj.typeName(), "TypeError");
  }
  return Value();
}

void Interpreter::defineProperty(std::shared_ptr<JsObject> obj, const std::string& key, const Value& val) {
  obj->props[key] = val;
}

void Interpreter::setMember(const Value& obj, const std::string& key, const Value& val) {
  if (obj.type == Type::Object) {
    obj.obj->props[key] = val;
    if (obj.obj->className == "Array" && key.find_first_not_of("0123456789") == std::string::npos) {
      size_t i = (size_t)std::stoll(key);
      double len = toNumber(obj.obj->props["length"]);
      if (i + 1 > len) obj.obj->props["length"] = Value::of((double)(i + 1));
    }
    return;
  }
  if (obj.type == Type::Function) {
    if (obj.obj) { obj.obj->props[key] = val; return; }
    if (obj.fn && obj.fn->obj) { obj.fn->obj->props[key] = val; return; }
    if (obj.fn) { obj.fn->obj = makeFunctionObject(obj.fn); obj.fn->obj->props[key] = val; return; }
    throwError("Cannot set property '" + key + "' of function", "TypeError");
  }
  throwError("Cannot set property '" + key + "' of " + obj.typeName(), "TypeError");
}

bool Interpreter::deleteMember(const Value& obj, const std::string& key) {
  if (obj.type == Type::Object) {
    auto it = obj.obj->props.find(key);
    if (it == obj.obj->props.end()) return true;
    obj.obj->props.erase(it);
    if (obj.obj->className == "Array" && key == "length") obj.obj->props["length"] = Value::of(0.0);
    return true;
  }
  return true;
}

Value Interpreter::callValue(const Value& fnVal, Value self, const std::vector<Value>& args) {
  if (!fnVal.isFunction()) throwError(toString(this, fnVal) + " is not a function", "TypeError");
  return callFunction(fnVal.fn, self, args, false);
}

Value Interpreter::callFunction(std::shared_ptr<JsFunction> fn, Value self,
                                const std::vector<Value>& args, bool asConstructor) {
  if (asConstructor) {
    Value protoVal;
    if (fn->obj) {
      auto it = fn->obj->props.find("prototype");
      if (it != fn->obj->props.end()) protoVal = it->second;
    }
    auto inst = protoVal.isObject() ? makeObject(protoVal.obj->className) : makeObject("Object");
    if (protoVal.isObject()) {
      inst->proto = protoVal.obj;   // [[Prototype]] = Constructor.prototype
    }
    Value thisVal;
    thisVal.type = Type::Object;
    thisVal.obj = inst;
    Value r = callFunction(fn, thisVal, args, false);
    if (r.isObject() || r.isFunction()) return r;
    return thisVal;
  }
  // 箭头函数：this 取定义处捕获值（词法 this），忽略调用点传入
  if (fn->isArrow) self = fn->capturedThis;
  if (self.isUndefined() && globalThisValue.isObject()) self = globalThisValue;
  if (fn->isNative) {
    return fn->native(this, self, args);
  }
  EnvPtr env = newEnv(fn->closure ? fn->closure : global_, true);
  env->vars["this"] = self;
  env->vars["arguments"] = Value();
  auto argsObj = makeArrayObject();
  for (size_t i = 0; i < args.size(); i++) argsObj->props[std::to_string(i)] = args[i];
  argsObj->props["length"] = Value::of((double)args.size());
  Value argv;
  argv.type = Type::Object;
  argv.obj = argsObj;
  env->vars["arguments"] = argv;
  for (size_t i = 0; i < fn->params.size(); i++) {
    if (i < args.size()) {
      env->vars[fn->params[i]] = args[i];
    } else if (i < fn->paramDefaults.size() && fn->paramDefaults[i]) {
      env->vars[fn->params[i]] = exec(fn->paramDefaults[i], env);   // 默认参数按需求值
    } else {
      env->vars[fn->params[i]] = Value();
    }
  }
  EnvPtr savedGlobal = global_;
  (void)savedGlobal;
  try {
    exec(fn->body, env);
  } catch (ReturnSignal& rs) {
    return rs.value;
  }
  return Value();
}

int Interpreter::functionTableId(const Value& fn) {
  if (!functionTableRef) return -1;
  for (size_t i = 0; i < functionTableRef->size(); i++) {
    if ((*functionTableRef)[i].fn == fn.fn) return (int)i;
  }
  functionTableRef->push_back(fn);
  return (int)functionTableRef->size() - 1;
}

}  // namespace js
}  // namespace mini
