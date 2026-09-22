#include "js_runtime.h"
#include <cmath>
#include <ctime>
#include <regex>
#include <sstream>
#include <algorithm>
#include <random>
#include <chrono>
#include <limits>
#include <iomanip>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace mini {
namespace js {

static void def(Interpreter* I, const std::shared_ptr<JsObject>& obj, const std::string& name, NativeFn fn) {
  auto f = std::make_shared<JsFunction>();
  f->isNative = true;
  f->name = name;
  f->native = std::move(fn);
  Value v;
  v.type = Type::Function;
  v.fn = f;
  v.obj = I->makeFunctionObject(f);
  obj->props[name] = v;
}

static std::shared_ptr<JsObject> asObj(Interpreter* I, const Value& v, const char* what) {
  if (!v.isObject()) I->throwError(std::string(what) + " called on non-object", "TypeError");
  return v.obj;
}

static double arrLen(const Value& self) {
  auto it = self.obj->props.find("length");
  return it == self.obj->props.end() ? 0 : toNumber(it->second);
}
static void arrSetLen(const Value& self, double len) { self.obj->props["length"] = Value::of(len); }
static Value arrGet(const Value& self, size_t i) {
  auto it = self.obj->props.find(std::to_string(i));
  return it == self.obj->props.end() ? Value() : it->second;
}
static void arrSet(const Value& self, size_t i, const Value& v) { self.obj->props[std::to_string(i)] = v; }

static std::vector<Value> toVec(const Value& self) {
  std::vector<Value> out;
  double len = arrLen(self);
  for (size_t i = 0; i < (size_t)len; i++) out.push_back(arrGet(self, i));
  return out;
}

static Value newArrayFrom(Interpreter* I, const std::vector<Value>& items) {
  auto o = I->makeArrayObject();
  for (size_t i = 0; i < items.size(); i++) o->props[std::to_string(i)] = items[i];
  o->props["length"] = Value::of((double)items.size());
  Value v;
  v.type = Type::Object;
  v.obj = o;
  return v;
}

// ---------------- JSON ----------------
struct JsonParser {
  std::string s;          // 按值保存: 调用方传入的是临时字符串
  size_t i = 0;
  Interpreter* I;
  JsonParser(std::string src, Interpreter* interp) : s(std::move(src)), I(interp) {}
  void ws() {
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
  }
  Value parse() {
    ws();
    Value v = value();
    ws();
    return v;
  }
  Value value() {
    if (i >= s.size()) I->throwError("JSON.parse: unexpected end", "SyntaxError");
    char c = s[i];
    if (c == '{') return object();
    if (c == '[') return array();
    if (c == '"') return Value::of(string());
    if (c == 't') { i += 4; return Value::of(true); }
    if (c == 'f') { i += 5; return Value::of(false); }
    if (c == 'n') { i += 4; return Value::null(); }
    if (c == '-' || std::isdigit((unsigned char)c)) {
      size_t start = i;
      while (i < s.size() && (std::isdigit((unsigned char)s[i]) || s[i] == '.' || s[i] == '-' ||
                              s[i] == '+' || s[i] == 'e' || s[i] == 'E')) i++;
      return Value::of(std::stod(s.substr(start, i - start)));
    }
    I->throwError(std::string("JSON.parse: unexpected '") + c + "'", "SyntaxError");
  }
  Value object() {
    i++;  // {
    auto o = I->makeObject("Object");
    ws();
    if (i < s.size() && s[i] == '}') { i++; return Value{Type::Object, 0, false, "", o, nullptr}; }
    while (i < s.size()) {
      ws();
      std::string k = string();
      ws();
      if (i < s.size() && s[i] == ':') i++;
      ws();
      o->props[k] = value();
      ws();
      if (i < s.size() && s[i] == ',') { i++; continue; }
      if (i < s.size() && s[i] == '}') { i++; break; }
      break;
    }
    return Value{Type::Object, 0, false, "", o, nullptr};
  }
  Value array() {
    i++;  // [
    std::vector<Value> items;
    ws();
    if (i < s.size() && s[i] == ']') { i++; return newArrayFrom(I, items); }
    while (i < s.size()) {
      ws();
      items.push_back(value());
      ws();
      if (i < s.size() && s[i] == ',') { i++; continue; }
      if (i < s.size() && s[i] == ']') { i++; break; }
      break;
    }
    return newArrayFrom(I, items);
  }
  std::string string() {
    i++;  // quote
    std::string out;
    while (i < s.size() && s[i] != '"') {
      if (s[i] == '\\') {
        i++;
        char e = s[i++];
        switch (e) {
          case 'n': out.push_back('\n'); break;
          case 't': out.push_back('\t'); break;
          case 'r': out.push_back('\r'); break;
          case 'b': out.push_back('\b'); break;
          case 'f': out.push_back('\f'); break;
          case 'u': {
            unsigned short code = (unsigned short)std::stoi(s.substr(i, 4), nullptr, 16);
            i += 4;
            if (code < 0x80) out.push_back((char)code);
            else if (code < 0x800) {
              out.push_back((char)(0xC0 | (code >> 6)));
              out.push_back((char)(0x80 | (code & 0x3F)));
            } else {
              out.push_back((char)(0xE0 | (code >> 12)));
              out.push_back((char)(0x80 | ((code >> 6) & 0x3F)));
              out.push_back((char)(0x80 | (code & 0x3F)));
            }
            break;
          }
          default: out.push_back(e); break;
        }
        continue;
      }
      out.push_back(s[i++]);
    }
    i++;
    return out;
  }
};

void jsonStringifyInto(Interpreter* I, std::string& out, const Value& v, int indent,
                              int depth, const std::vector<const JsObject*>& stack) {
  if (v.isUndefined() || v.isFunction()) return;
  if (v.isNull()) { out += "null"; return; }
  if (v.isBool()) { out += v.boolean ? "true" : "false"; return; }
  if (v.isNumber()) {
    if (std::isnan(v.num) || std::isinf(v.num)) out += "null";
    else out += numberToString(v.num);
    return;
  }
  if (v.isString()) {
    out += '"';
    for (char c : v.str) {
      switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        default:
          if ((unsigned char)c < 0x20) {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "\\u%04x", (unsigned char)c);
            out += buf;
          } else out += c;
      }
    }
    out += '"';
    return;
  }
  if (v.isObject()) {
    if (std::find(stack.begin(), stack.end(), v.obj.get()) != stack.end()) {
      I->throwError("Converting circular structure to JSON", "TypeError");
    }
    Value toJson = I->getMember(v, "toJSON");
    if (toJson.isFunction()) {
      Value r = I->callValue(toJson, v, {});
      std::vector<const JsObject*> st = stack;
      st.push_back(v.obj.get());
      jsonStringifyInto(I, out, r, indent, depth, st);
      return;
    }
    std::vector<const JsObject*> st = stack;
    st.push_back(v.obj.get());
    std::string nl = indent > 0 ? "\n" : "";
    std::string pad1, pad2;
    if (indent > 0) {
      pad1 = std::string((depth + 1) * indent, ' ');
      pad2 = std::string(depth * indent, ' ');
    }
    if (v.obj->className == "Array") {
      double len = arrLen(v);
      out += "[";
      for (size_t i = 0; i < (size_t)len; i++) {
        if (i) out += ",";
        out += nl + pad1;
        jsonStringifyInto(I, out, arrGet(v, i), indent, depth + 1, st);
      }
      if (len > 0) out += nl + pad2;
      out += "]";
      return;
    }
    out += "{";
    bool first = true;
    std::vector<std::string> keys;
    for (auto& kv : v.obj->props) keys.push_back(kv.first);
    std::sort(keys.begin(), keys.end());
    for (auto& k : keys) {
      Value val = v.obj->props[k];
      if (val.isUndefined() || val.isFunction()) continue;
      if (!first) out += ",";
      first = false;
      out += nl + pad1;
      jsonStringifyInto(I, out, Value::of(k), 0, 0, st);
      out += ":";
      if (indent > 0) out += " ";
      jsonStringifyInto(I, out, val, indent, depth + 1, st);
    }
    if (!first) out += nl + pad2;
    out += "}";
  }
}

// ---------------- regex helpers ----------------
static std::regex buildRegex(Interpreter* I, const Value& reVal) {
  std::string src = toString(I, I->getMember(reVal, "source"));
  std::string flags = toString(I, I->getMember(reVal, "flags"));
  auto flagsType = std::regex::ECMAScript;
  if (flags.find('i') != std::string::npos) flagsType |= std::regex::icase;
  try {
    return std::regex(src, flagsType);
  } catch (...) {
    I->throwError("Invalid regular expression: " + src, "SyntaxError");
  }
}

// ---------------- builtins ----------------
void Interpreter::initBuiltins() {
  Interpreter* I = this;

  // ---- Object ----
  auto objCtor = std::make_shared<JsFunction>();
  objCtor->isNative = true;
  objCtor->name = "Object";
  objCtor->native = [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (!args.empty() && args[0].isObject()) return args[0];
    if (!args.empty() && args[0].isString()) return ip->makeStringObject(args[0].str);
    auto o = ip->makeObject("Object");
    Value v;
    v.type = Type::Object;
    v.obj = o;
    return v;
  };
  Value objCtorVal;
  objCtorVal.type = Type::Function;
  objCtorVal.fn = objCtor;
  objCtorVal.obj = makeFunctionObject(objCtor);
  objectProto->props["constructor"] = objCtorVal;

  def(I, objectProto, "hasOwnProperty", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    if (!self.isObject()) return Value::of(false);
    return Value::of(self.obj->props.count(toString(ip, args.empty() ? Value() : args[0])) > 0);
  });
  def(I, objectProto, "toString", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    if (self.isObject() && self.obj->className == "Array") {
      Value jn = ip->getMember(self, "join");
      if (jn.isFunction()) return ip->callValue(jn, self, {Value::of(",")});
    }
    std::string cls = self.isObject() ? self.obj->className : "Object";
    return Value::of("[object " + cls + "]");
  });
  def(I, objectProto, "valueOf", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value { return self; });

  def(I, objCtorVal.obj, "keys", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::vector<Value> out;
    if (!args.empty() && args[0].isObject()) {
      std::vector<std::string> keys;
      for (auto& kv : args[0].obj->props) {
        if (args[0].obj->className == "Array" && kv.first == "length") continue;
        keys.push_back(kv.first);
      }
      std::sort(keys.begin(), keys.end());
      for (auto& k : keys) out.push_back(Value::of(k));
    }
    return newArrayFrom(ip, out);
  });
  def(I, objCtorVal.obj, "values", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::vector<Value> out;
    if (!args.empty() && args[0].isObject()) {
      std::vector<std::string> keys;
      for (auto& kv : args[0].obj->props) {
        if (args[0].obj->className == "Array" && kv.first == "length") continue;
        keys.push_back(kv.first);
      }
      std::sort(keys.begin(), keys.end());
      for (auto& k : keys) out.push_back(args[0].obj->props[k]);
    }
    return newArrayFrom(ip, out);
  });
  def(I, objCtorVal.obj, "assign", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isObject()) return args.empty() ? Value() : args[0];
    for (size_t i = 1; i < args.size(); i++) {
      if (!args[i].isObject()) continue;
      for (auto& kv : args[i].obj->props) args[0].obj->props[kv.first] = kv.second;
    }
    return args[0];
  });
  def(I, objCtorVal.obj, "create", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    auto o = ip->makeObject("Object");
    if (!args.empty() && args[0].isObject()) o->proto = args[0].obj;
    Value v;
    v.type = Type::Object;
    v.obj = o;
    return v;
  });
  setGlobal("Object", objCtorVal);

  // ---- Object 静态方法补充（values / entries / fromEntries） ----
  def(I, objCtorVal.obj, "values", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isObject()) return newArrayFrom(ip, {});
    std::vector<Value> out;
    for (auto& kv : args[0].obj->props) {
      if (kv.first == "length") continue;
      out.push_back(kv.second);
    }
    return newArrayFrom(ip, out);
  });
  def(I, objCtorVal.obj, "entries", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isObject()) return newArrayFrom(ip, {});
    std::vector<Value> out;
    for (auto& kv : args[0].obj->props) {
      if (kv.first == "length") continue;
      std::vector<Value> pair{Value::of(kv.first), kv.second};
      out.push_back(newArrayFrom(ip, pair));
    }
    return newArrayFrom(ip, out);
  });
  def(I, objCtorVal.obj, "fromEntries", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    auto o = ip->makeObject("Object");
    if (!args.empty() && args[0].isObject()) {
      auto arr = args[0].obj;
      auto lit = arr->props.find("length");
      long len = lit == arr->props.end() ? 0 : (long)toNumber(lit->second);
      for (long i = 0; i < len; i++) {
        auto it = arr->props.find(std::to_string(i));
        if (it == arr->props.end() || !it->second.isObject()) continue;
        auto& pair = it->second.obj->props;
        auto k = pair.find("0"); auto v = pair.find("1");
        if (k != pair.end()) o->props[toString(ip, k->second)] = v != pair.end() ? v->second : Value();
      }
    }
    Value rv; rv.type = Type::Object; rv.obj = o; return rv;
  });

  // ---- Function.prototype ----
  def(I, functionProto, "call", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> rest;
    for (size_t i = 1; i < args.size(); i++) rest.push_back(args[i]);
    return ip->callValue(self, args.empty() ? Value() : args[0], rest);
  });
  def(I, functionProto, "apply", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> rest;
    if (args.size() > 1 && args[1].isObject()) {
      auto it = args[1].obj->props.find("length");
      double len = it == args[1].obj->props.end() ? 0 : toNumber(it->second);
      for (size_t i = 0; i < (size_t)len; i++) rest.push_back(args[1].obj->props[std::to_string(i)]);
    }
    return ip->callValue(self, args.empty() ? Value() : args[0], rest);
  });
  def(I, functionProto, "bind", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    Value boundThis = args.empty() ? Value() : args[0];
    std::vector<Value> bound;
    for (size_t i = 1; i < args.size(); i++) bound.push_back(args[i]);
    auto f = std::make_shared<JsFunction>();
    f->isNative = true;
    f->name = "bound";
    f->native = [self, boundThis, bound](Interpreter* p, const Value&, const std::vector<Value>& more) -> Value {
      std::vector<Value> all = bound;
      for (auto& v : more) all.push_back(v);
      return p->callValue(self, boundThis, all);
    };
    Value v;
    v.type = Type::Function;
    v.fn = f;
    v.obj = ip->makeFunctionObject(f);
    f->obj = v.obj;
    return v;
  });

  // ---- Array ----
  def(I, arrayProto, "push", [](Interpreter*, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (auto& a : args) { arrSet(self, (size_t)len, a); len++; }
    arrSetLen(self, len);
    return Value::of(len);
  });
  def(I, arrayProto, "pop", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    double len = arrLen(self);
    if (len <= 0) { arrSetLen(self, 0); return Value(); }
    Value v = arrGet(self, (size_t)(len - 1));
    self.obj->props.erase(std::to_string((size_t)(len - 1)));
    arrSetLen(self, len - 1);
    return v;
  });
  def(I, arrayProto, "shift", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::vector<Value> items = toVec(self);
    if (items.empty()) return Value();
    Value first = items[0];
    items.erase(items.begin());
    // rebuild
    std::vector<std::string> idxKeys;
    for (auto& kv : self.obj->props) {
      if (kv.first != "length" && kv.first.find_first_not_of("0123456789") == std::string::npos) idxKeys.push_back(kv.first);
    }
    for (auto& k : idxKeys) self.obj->props.erase(k);
    for (size_t i = 0; i < items.size(); i++) arrSet(self, i, items[i]);
    arrSetLen(self, (double)items.size());
    return first;
  });
  def(I, arrayProto, "unshift", [](Interpreter*, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> items = toVec(self);
    items.insert(items.begin(), args.begin(), args.end());
    std::vector<std::string> idxKeys;
    for (auto& kv : self.obj->props) {
      if (kv.first != "length" && kv.first.find_first_not_of("0123456789") == std::string::npos) idxKeys.push_back(kv.first);
    }
    for (auto& k : idxKeys) self.obj->props.erase(k);
    for (size_t i = 0; i < items.size(); i++) arrSet(self, i, items[i]);
    arrSetLen(self, (double)items.size());
    return Value::of((double)items.size());
  });
  def(I, arrayProto, "slice", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> items = toVec(self);
    long long len = (long long)items.size();
    long long start = args.empty() ? 0 : (long long)toNumber(args[0]);
    long long end = args.size() > 1 ? (long long)toNumber(args[1]) : len;
    if (start < 0) start = std::max(0LL, len + start);
    if (end < 0) end = std::max(0LL, len + end);
    start = std::min(start, len);
    end = std::min(end, len);
    std::vector<Value> out;
    for (long long i = start; i < end; i++) out.push_back(items[(size_t)i]);
    return newArrayFrom(ip, out);
  });
  def(I, arrayProto, "splice", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> items = toVec(self);
    long long len = (long long)items.size();
    long long start = args.empty() ? 0 : (long long)toNumber(args[0]);
    if (start < 0) start = std::max(0LL, len + start);
    start = std::min(start, len);
    long long del = args.size() > 1 ? (long long)toNumber(args[1]) : len - start;
    del = std::max(0LL, std::min(del, len - start));
    std::vector<Value> removed(items.begin() + start, items.begin() + start + del);
    items.erase(items.begin() + start, items.begin() + start + del);
    std::vector<Value> ins;
    for (size_t i = 2; i < args.size(); i++) ins.push_back(args[i]);
    items.insert(items.begin() + start, ins.begin(), ins.end());
    std::vector<std::string> idxKeys;
    for (auto& kv : self.obj->props) {
      if (kv.first != "length" && kv.first.find_first_not_of("0123456789") == std::string::npos) idxKeys.push_back(kv.first);
    }
    for (auto& k : idxKeys) self.obj->props.erase(k);
    for (size_t i = 0; i < items.size(); i++) arrSet(self, i, items[i]);
    arrSetLen(self, (double)items.size());
    return newArrayFrom(ip, removed);
  });
  def(I, arrayProto, "concat", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> out = toVec(self);
    for (auto& a : args) {
      if (a.isObject() && a.obj->className == "Array") {
        double l = arrLen(a);
        for (size_t i = 0; i < (size_t)l; i++) out.push_back(arrGet(a, i));
      } else out.push_back(a);
    }
    return newArrayFrom(ip, out);
  });
  def(I, arrayProto, "join", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string sep = args.empty() ? "," : toString(ip, args[0]);
    double len = arrLen(self);
    std::string out;
    for (size_t i = 0; i < (size_t)len; i++) {
      if (i) out += sep;
      Value v = arrGet(self, i);
      if (v.isUndefined() || v.isNull()) continue;
      out += toString(ip, v);
    }
    return Value::of(out);
  });
  def(I, arrayProto, "indexOf", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) if (strictEquals(arrGet(self, i), args.empty() ? Value() : args[0])) return Value::of((double)i);
    (void)ip;
    return Value::of(-1.0);
  });
  def(I, arrayProto, "lastIndexOf", [](Interpreter*, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (long long i = (long long)len - 1; i >= 0; i--) if (strictEquals(arrGet(self, (size_t)i), args.empty() ? Value() : args[0])) return Value::of((double)i);
    return Value::of(-1.0);
  });
  def(I, arrayProto, "includes", [](Interpreter*, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) if (strictEquals(arrGet(self, i), args.empty() ? Value() : args[0])) return Value::of(true);
    return Value::of(false);
  });
  def(I, arrayProto, "forEach", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isFunction()) ip->throwError("forEach: callback required", "TypeError");
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) {
      std::vector<Value> cbArgs{arrGet(self, i), Value::of((double)i), self};
      try { ip->callValue(args[0], args.size() > 1 ? args[1] : Value(), cbArgs); }
      catch (BreakSignal&) { break; }
      catch (ContinueSignal&) { continue; }
    }
    return Value();
  });
  def(I, arrayProto, "map", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isFunction()) ip->throwError("map: callback required", "TypeError");
    double len = arrLen(self);
    std::vector<Value> out;
    for (size_t i = 0; i < (size_t)len; i++) {
      std::vector<Value> cbArgs{arrGet(self, i), Value::of((double)i), self};
      out.push_back(ip->callValue(args[0], args.size() > 1 ? args[1] : Value(), cbArgs));
    }
    return newArrayFrom(ip, out);
  });
  def(I, arrayProto, "filter", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isFunction()) ip->throwError("filter: callback required", "TypeError");
    double len = arrLen(self);
    std::vector<Value> out;
    for (size_t i = 0; i < (size_t)len; i++) {
      Value v = arrGet(self, i);
      std::vector<Value> cbArgs{v, Value::of((double)i), self};
      if (toBool(ip->callValue(args[0], args.size() > 1 ? args[1] : Value(), cbArgs))) out.push_back(v);
    }
    return newArrayFrom(ip, out);
  });
  def(I, arrayProto, "some", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) {
      std::vector<Value> cbArgs{arrGet(self, i), Value::of((double)i), self};
      if (toBool(ip->callValue(args[0], Value(), cbArgs))) return Value::of(true);
    }
    return Value::of(false);
  });
  def(I, arrayProto, "every", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) {
      std::vector<Value> cbArgs{arrGet(self, i), Value::of((double)i), self};
      if (!toBool(ip->callValue(args[0], Value(), cbArgs))) return Value::of(false);
    }
    return Value::of(true);
  });
  def(I, arrayProto, "find", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) {
      Value v = arrGet(self, i);
      std::vector<Value> cbArgs{v, Value::of((double)i), self};
      if (toBool(ip->callValue(args[0], Value(), cbArgs))) return v;
    }
    return Value();
  });
  def(I, arrayProto, "findIndex", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    double len = arrLen(self);
    for (size_t i = 0; i < (size_t)len; i++) {
      std::vector<Value> cbArgs{arrGet(self, i), Value::of((double)i), self};
      if (toBool(ip->callValue(args[0], Value(), cbArgs))) return Value::of((double)i);
    }
    return Value::of(-1.0);
  });
  def(I, arrayProto, "reduce", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> items = toVec(self);
    if (args.empty() || !args[0].isFunction()) ip->throwError("reduce: callback required", "TypeError");
    Value acc;
    size_t start = 0;
    if (args.size() > 1) acc = args[1];
    else if (!items.empty()) { acc = items[0]; start = 1; }
    for (size_t i = start; i < items.size(); i++) {
      acc = ip->callValue(args[0], Value(), {acc, items[i], Value::of((double)i), self});
    }
    return acc;
  });
  def(I, arrayProto, "reverse", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::vector<Value> items = toVec(self);
    std::reverse(items.begin(), items.end());
    for (size_t i = 0; i < items.size(); i++) arrSet(self, i, items[i]);
    return self;
  });
  def(I, arrayProto, "sort", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::vector<Value> items = toVec(self);
    if (!args.empty() && args[0].isFunction()) {
      std::stable_sort(items.begin(), items.end(), [&](const Value& a, const Value& b) {
        return toNumber(ip->callValue(args[0], Value(), {a, b})) < 0;
      });
    } else {
      std::stable_sort(items.begin(), items.end(), [&](const Value& a, const Value& b) {
        return toString(ip, a) < toString(ip, b);
      });
    }
    for (size_t i = 0; i < items.size(); i++) arrSet(self, i, items[i]);
    return self;
  });

  // Array constructor
  registerNative("Array", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::vector<Value> items;
    if (args.size() == 1 && args[0].isNumber()) {
      double n = args[0].num;
      for (double i = 0; i < n; i++) items.push_back(Value());
    } else {
      items = args;
    }
    return newArrayFrom(ip, items);
  });
  getGlobal("Array").obj->props["prototype"] = Value{Type::Object, 0, false, "", arrayProto, nullptr};
  def(I, getGlobal("Array").obj, "isArray", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(!args.empty() && args[0].isObject() && args[0].obj->className == "Array");
  });

  // ---- String ----
  def(I, stringProto, "padStart", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    long target = args.empty() ? 0 : (long)toNumber(args[0]);
    std::string pad = args.size() > 1 ? toString(ip, args[1]) : " ";
    if (pad.empty() || (long)s.size() >= target) return Value::of(s);
    std::string out = s;
    while ((long)out.size() < target) out = pad + out;
    if ((long)out.size() > target) out = out.substr(out.size() - target);
    return Value::of(out);
  });
  def(I, stringProto, "padEnd", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    long target = args.empty() ? 0 : (long)toNumber(args[0]);
    std::string pad = args.size() > 1 ? toString(ip, args[1]) : " ";
    if (pad.empty() || (long)s.size() >= target) return Value::of(s);
    std::string out = s;
    while ((long)out.size() < target) out += pad;
    if ((long)out.size() > target) out = out.substr(0, target);
    return Value::of(out);
  });
  def(I, stringProto, "trim", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    size_t a = s.find_first_not_of(" \t\r\n\f\v");
    if (a == std::string::npos) return Value::of("");
    size_t b = s.find_last_not_of(" \t\r\n\f\v");
    return Value::of(s.substr(a, b - a + 1));
  });
  def(I, stringProto, "trimStart", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    size_t a = s.find_first_not_of(" \t\r\n\f\v");
    return Value::of(a == std::string::npos ? "" : s.substr(a));
  });
  def(I, stringProto, "trimEnd", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    size_t b = s.find_last_not_of(" \t\r\n\f\v");
    return Value::of(b == std::string::npos ? "" : s.substr(0, b + 1));
  });
  def(I, stringProto, "charAt", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    size_t i = args.empty() ? 0 : (size_t)toNumber(args[0]);
    return i < s.size() ? Value::of(std::string(1, s[i])) : Value::of(std::string(""));
  });
  def(I, stringProto, "charCodeAt", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    size_t i = args.empty() ? 0 : (size_t)toNumber(args[0]);
    return i < s.size() ? Value::of((double)(unsigned char)s[i]) : Value::of(NAN);
  });
  def(I, stringProto, "indexOf", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    std::string needle = args.empty() ? "" : toString(ip, args[0]);
    size_t from = args.size() > 1 ? (size_t)toNumber(args[1]) : 0;
    size_t p = s.find(needle, from);
    return Value::of(p == std::string::npos ? -1.0 : (double)p);
  });
  def(I, stringProto, "lastIndexOf", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    std::string needle = args.empty() ? "" : toString(ip, args[0]);
    size_t p = s.rfind(needle);
    return Value::of(p == std::string::npos ? -1.0 : (double)p);
  });
  def(I, stringProto, "slice", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    long long len = (long long)s.size();
    long long a = args.empty() ? 0 : (long long)toNumber(args[0]);
    long long b = args.size() > 1 ? (long long)toNumber(args[1]) : len;
    if (a < 0) a = std::max(0LL, len + a);
    if (b < 0) b = std::max(0LL, len + b);
    a = std::min(a, len); b = std::min(b, len);
    if (b <= a) return Value::of(std::string(""));
    return Value::of(s.substr((size_t)a, (size_t)(b - a)));
  });
  def(I, stringProto, "substring", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    long long a = args.empty() ? 0 : (long long)toNumber(args[0]);
    long long b = args.size() > 1 ? (long long)toNumber(args[1]) : (long long)s.size();
    if (a > b) std::swap(a, b);
    a = std::max(0LL, std::min(a, (long long)s.size()));
    b = std::max(0LL, std::min(b, (long long)s.size()));
    return Value::of(s.substr((size_t)a, (size_t)(b - a)));
  });
  def(I, stringProto, "substr", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    long long a = args.empty() ? 0 : (long long)toNumber(args[0]);
    if (a < 0) a = std::max(0LL, (long long)s.size() + a);
    long long n = args.size() > 1 ? (long long)toNumber(args[1]) : (long long)s.size();
    return Value::of(s.substr((size_t)a, (size_t)std::max(0LL, n)));
  });
  def(I, stringProto, "split", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    std::vector<Value> out;
    if (args.empty() || (args[0].isUndefined())) {
      out.push_back(Value::of(s));
      return newArrayFrom(ip, out);
    }
    if (args[0].isObject() && args[0].obj->className == "RegExp") {
      std::regex re = buildRegex(ip, args[0]);
      std::sregex_token_iterator it(s.begin(), s.end(), re, -1);
      std::sregex_token_iterator end;
      for (; it != end; ++it) out.push_back(Value::of(it->str()));
      return newArrayFrom(ip, out);
    }
    std::string sep = toString(ip, args[0]);
    if (sep.empty()) {
      for (char c : s) out.push_back(Value::of(std::string(1, c)));
      return newArrayFrom(ip, out);
    }
    size_t pos = 0;
    while (true) {
      size_t p = s.find(sep, pos);
      if (p == std::string::npos) { out.push_back(Value::of(s.substr(pos))); break; }
      out.push_back(Value::of(s.substr(pos, p - pos)));
      pos = p + sep.size();
    }
    return newArrayFrom(ip, out);
  });
  def(I, stringProto, "toUpperCase", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return (char)std::toupper(c); });
    return Value::of(s);
  });
  def(I, stringProto, "toLowerCase", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return (char)std::tolower(c); });
    return Value::of(s);
  });
  auto trimmer = [](const std::string& s) {
    size_t a = s.find_first_not_of(" \t\n\r\f\v");
    if (a == std::string::npos) return std::string("");
    size_t b = s.find_last_not_of(" \t\n\r\f\v");
    return s.substr(a, b - a + 1);
  };
  def(I, stringProto, "trim", [&](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    return Value::of(trimmer(toString(ip, self)));
  });
  def(I, stringProto, "trimStart", [&](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    size_t a = s.find_first_not_of(" \t\n\r\f\v");
    return Value::of(a == std::string::npos ? std::string("") : s.substr(a));
  });
  def(I, stringProto, "trimEnd", [&](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::string s = toString(ip, self);
    size_t b = s.find_last_not_of(" \t\n\r\f\v");
    return Value::of(b == std::string::npos ? std::string("") : s.substr(0, b + 1));
  });
  def(I, stringProto, "replace", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    if (args.size() < 2) return Value::of(s);
    std::string rep = toString(ip, args[1]);
    if (args[0].isObject() && args[0].obj->className == "RegExp") {
      std::regex re = buildRegex(ip, args[0]);
      std::string flags = toString(ip, ip->getMember(args[0], "flags"));
      if (flags.find('g') != std::string::npos) return Value::of(std::regex_replace(s, re, rep));
      return Value::of(std::regex_replace(s, re, rep, std::regex_constants::format_first_only));
    }
    std::string pat = toString(ip, args[0]);
    size_t p = s.find(pat);
    if (p == std::string::npos) return Value::of(s);
    return Value::of(s.substr(0, p) + rep + s.substr(p + pat.size()));
  });
  def(I, stringProto, "match", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    if (args.empty()) return Value::null();
    std::regex re;
    bool global = false;
    if (args[0].isObject() && args[0].obj->className == "RegExp") {
      re = buildRegex(ip, args[0]);
      global = toBool(ip->getMember(args[0], "global"));
    } else {
      re = std::regex(toString(ip, args[0]));
    }
    std::vector<Value> out;
    if (global) {
      std::sregex_iterator it(s.begin(), s.end(), re), end;
      for (; it != end; ++it) out.push_back(Value::of(it->str()));
    } else {
      std::smatch m;
      if (std::regex_search(s, m, re)) {
        auto arr = ip->makeArrayObject();
        for (size_t i = 0; i < m.size(); i++) arr->props[std::to_string(i)] = Value::of(m[i].str());
        arr->props["length"] = Value::of((double)m.size());
        arr->props["index"] = Value::of((double)m.position());
        arr->props["input"] = Value::of(s);
        Value v; v.type = Type::Object; v.obj = arr; return v;
      }
      return Value::null();
    }
    return newArrayFrom(ip, out);
  });
  def(I, stringProto, "search", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    std::regex re = args[0].isObject() && args[0].obj->className == "RegExp" ? buildRegex(ip, args[0])
                                                                            : std::regex(toString(ip, args[0]));
    std::smatch m;
    return Value::of(std::regex_search(s, m, re) ? (double)m.position() : -1.0);
  });
  def(I, stringProto, "includes", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    return Value::of(toString(ip, self).find(args.empty() ? "" : toString(ip, args[0])) != std::string::npos);
  });
  def(I, stringProto, "startsWith", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    std::string p = args.empty() ? "" : toString(ip, args[0]);
    return Value::of(s.rfind(p, 0) == 0);
  });
  def(I, stringProto, "endsWith", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    std::string p = args.empty() ? "" : toString(ip, args[0]);
    if (p.size() > s.size()) return Value::of(false);
    return Value::of(s.compare(s.size() - p.size(), p.size(), p) == 0);
  });
  def(I, stringProto, "repeat", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    long long n = args.empty() ? 0 : (long long)toNumber(args[0]);
    std::string out;
    for (long long i = 0; i < n; i++) out += s;
    return Value::of(out);
  });
  def(I, stringProto, "concat", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::string s = toString(ip, self);
    for (auto& a : args) s += toString(ip, a);
    return Value::of(s);
  });
  def(I, stringProto, "valueOf", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    return Value::of(toString(ip, self));
  });
  registerNative("String", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(args.empty() ? std::string("") : toString(ip, args[0]));
  });
  def(I, getGlobal("String").obj, "fromCharCode", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    std::string s;
    for (auto& a : args) s.push_back((char)(unsigned char)toNumber(a));
    return Value::of(s);
  });
  getGlobal("String").obj->props["prototype"] = Value{Type::Object, 0, false, "", stringProto, nullptr};

  // ---- Number ----
  def(I, numberProto, "toFixed", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    int digits = args.empty() ? 0 : (int)toNumber(args[0]);
    std::ostringstream os;
    os << std::fixed << std::setprecision(digits) << toNumber(self);
    (void)ip;
    return Value::of(os.str());
  });
  def(I, numberProto, "toString", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    (void)ip;
    int radix = args.empty() ? 10 : (int)toNumber(args[0]);
    long long n = (long long)toNumber(self);
    if (radix == 16) {
      std::ostringstream os;
      os << std::hex << n;
      return Value::of(os.str());
    }
    return Value::of(numberToString((double)n));
  });
  def(I, numberProto, "valueOf", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    return Value::of(toNumber(self));
  });
  registerNative("Number", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(args.empty() ? 0.0 : toNumber(args[0]));
  });
  getGlobal("Number").obj->props["prototype"] = Value{Type::Object, 0, false, "", numberProto, nullptr};
  def(I, getGlobal("Number").obj, "isInteger", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isNumber()) return Value::of(false);
    double d = args[0].num;
    return Value::of(std::isfinite(d) && d == std::floor(d));
  });
  def(I, getGlobal("Number").obj, "isFinite", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(!args.empty() && args[0].isNumber() && std::isfinite(args[0].num));
  });

  registerNative("parseInt", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::string s = args.empty() ? "" : toString(ip, args[0]);
    int radix = args.size() > 1 ? (int)toNumber(args[1]) : 10;
    if (radix != 10 && radix != 16) radix = 10;
    size_t i = 0;
    while (i < s.size() && std::isspace((unsigned char)s[i])) i++;
    bool neg = false;
    if (i < s.size() && (s[i] == '-' || s[i] == '+')) { neg = s[i] == '-'; i++; }
    long long val = 0;
    bool any = false;
    while (i < s.size()) {
      int d = -1;
      if (std::isdigit((unsigned char)s[i])) d = s[i] - '0';
      else if (radix == 16 && std::isxdigit((unsigned char)s[i])) d = std::tolower(s[i]) - 'a' + 10;
      if (d < 0 || d >= radix) break;
      val = val * radix + d;
      any = true;
      i++;
    }
    if (!any) return Value::of(NAN);
    return Value::of((double)(neg ? -val : val));
  });
  registerNative("parseFloat", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::string s = args.empty() ? "" : toString(ip, args[0]);
    size_t i = 0, start = 0;
    while (i < s.size() && std::isspace((unsigned char)s[i])) i++;
    start = i;
    if (i < s.size() && (s[i] == '-' || s[i] == '+')) i++;
    while (i < s.size() && (std::isdigit((unsigned char)s[i]) || s[i] == '.')) i++;
    if (i == start) return Value::of(NAN);
    return Value::of(std::stod(s.substr(start, i - start)));
  });
  registerNative("isNaN", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(args.empty() ? true : std::isnan(toNumber(args[0])));
  });
  registerNative("isFinite", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(args.empty() ? false : !std::isnan(toNumber(args[0])) && !std::isinf(toNumber(args[0])));
  });
  registerNative("Boolean", [](Interpreter*, const Value&, const std::vector<Value>& args) -> Value {
    return Value::of(!args.empty() && toBool(args[0]));
  });

  // ---- Math ----
  auto mathObj = makeObject("Math");
  Value mathVal;
  mathVal.type = Type::Object;
  mathVal.obj = mathObj;
  mathObj->props["PI"] = Value::of(M_PI);
  mathObj->props["E"] = Value::of(M_E);
  mathObj->props["LN2"] = Value::of(M_LN2);
  mathObj->props["LN10"] = Value::of(M_LN10);
  static std::mt19937 rng((unsigned)std::chrono::system_clock::now().time_since_epoch().count());
  def(I, mathObj, "floor", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::floor(toNumber(a[0]))); });
  def(I, mathObj, "ceil", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::ceil(toNumber(a[0]))); });
  def(I, mathObj, "round", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::round(toNumber(a[0]))); });
  def(I, mathObj, "abs", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::fabs(toNumber(a[0]))); });
  def(I, mathObj, "sqrt", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::sqrt(toNumber(a[0]))); });
  def(I, mathObj, "pow", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::pow(toNumber(a[0]), toNumber(a[1]))); });
  def(I, mathObj, "sin", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::sin(toNumber(a[0]))); });
  def(I, mathObj, "cos", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::cos(toNumber(a[0]))); });
  def(I, mathObj, "tan", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::tan(toNumber(a[0]))); });
  def(I, mathObj, "log", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::log(toNumber(a[0]))); });
  def(I, mathObj, "exp", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::exp(toNumber(a[0]))); });
  def(I, mathObj, "atan2", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value { return Value::of(std::atan2(toNumber(a[0]), toNumber(a[1]))); });
  def(I, mathObj, "min", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value {
    double m = INFINITY;
    for (auto& v : a) m = std::min(m, toNumber(v));
    return Value::of(a.empty() ? INFINITY : m);
  });
  def(I, mathObj, "max", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value {
    double m = -INFINITY;
    for (auto& v : a) m = std::max(m, toNumber(v));
    return Value::of(a.empty() ? -INFINITY : m);
  });
  def(I, mathObj, "random", [](Interpreter*, const Value&, const std::vector<Value>&) -> Value {
    return Value::of((double)(rng() % 1000000) / 1000000.0);
  });
  def(I, mathObj, "sign", [](Interpreter*, const Value&, const std::vector<Value>& a) -> Value {
    double d = toNumber(a[0]);
    return Value::of(d > 0 ? 1.0 : (d < 0 ? -1.0 : 0.0));
  });
  setGlobal("Math", mathVal);

  // ---- JSON ----
  auto jsonObj = makeObject("Object");
  Value jsonVal;
  jsonVal.type = Type::Object;
  jsonVal.obj = jsonObj;
  def(I, jsonObj, "parse", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty()) ip->throwError("JSON.parse: undefined", "SyntaxError");
    JsonParser p(toString(ip, args[0]), ip);
    return p.parse();
  });
  def(I, jsonObj, "stringify", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty()) return Value();
    std::string out;
    int indent = 0;
    if (args.size() > 2 && args[2].isNumber()) indent = (int)toNumber(args[2]);
    jsonStringifyInto(ip, out, args[0], indent, 0, {});
    return Value::of(out);
  });
  setGlobal("JSON", jsonVal);

  // ---- console ----
  auto consoleObj = makeObject("Object");
  Value consoleVal;
  consoleVal.type = Type::Object;
  consoleVal.obj = consoleObj;
  auto logFn = [](const std::string& level) {
    return [level](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
      std::string line;
      for (size_t i = 0; i < args.size(); i++) {
        if (i) line += " ";
        if (args[i].isString()) line += args[i].str;
        else line += toString(ip, args[i]);
      }
      if (ip->hostLog) ip->hostLog(level, line);
      return Value();
    };
  };
  def(I, consoleObj, "log", logFn("log"));
  def(I, consoleObj, "info", logFn("info"));
  def(I, consoleObj, "warn", logFn("warn"));
  def(I, consoleObj, "error", logFn("error"));
  def(I, consoleObj, "debug", logFn("debug"));
  setGlobal("console", consoleVal);

  // ---- Date ----
  auto dateProto = makeObject("Date");   // 前移：构造器实例需要链接它
  registerNative("Date", [dateProto](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    auto o = ip->makeObject("Date");
    o->proto = dateProto;   // ★ 实例链接 Date.prototype —— 此前缺失，new Date().getMonth() 全挂
    double ms;
    if (args.empty()) {
      ms = (double)std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch()).count();
    } else if (args.size() >= 3) {
      std::tm tm{};
      tm.tm_year = (int)toNumber(args[0]) - 1900;
      tm.tm_mon = (int)toNumber(args[1]);
      tm.tm_mday = (int)toNumber(args[2]);
      tm.tm_hour = args.size() > 3 ? (int)toNumber(args[3]) : 0;
      tm.tm_min = args.size() > 4 ? (int)toNumber(args[4]) : 0;
      tm.tm_sec = args.size() > 5 ? (int)toNumber(args[5]) : 0;
      ms = (double)(std::mktime(&tm) * 1000LL);
    } else {
      ms = toNumber(args[0]);
    }
    o->props["__ms"] = Value::of(ms);
    Value v;
    v.type = Type::Object;
    v.obj = o;
    return v;
  });
  def(I, dateProto, "getTime", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    return Value::of(toNumber(self.obj->props["__ms"]));
  });
  def(I, dateProto, "valueOf", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    return Value::of(toNumber(self.obj->props["__ms"]));
  });
  def(I, dateProto, "toString", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{};
    localtime_r(&t, &tm);
    char buf[64];
    std::strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &tm);
    (void)ip;
    return Value::of(std::string(buf));
  });
  def(I, dateProto, "getFullYear", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)(tm.tm_year + 1900));
  });
  def(I, dateProto, "getMonth", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)tm.tm_mon);
  });
  def(I, dateProto, "getDate", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)tm.tm_mday);
  });
  def(I, dateProto, "getHours", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)tm.tm_hour);
  });
  def(I, dateProto, "getMinutes", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)tm.tm_min);
  });
  def(I, dateProto, "getSeconds", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)tm.tm_sec);
  });
  def(I, dateProto, "toJSON", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    // JSON.stringify(date) → ISO 字符串（标准语义）：否则存 storage 变 {__ms} 读不回来
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; gmtime_r(&t, &tm);
    char buf[40];
    std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &tm);
    return Value::of(std::string(buf));
  });
  def(I, dateProto, "getDay", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    return Value::of((double)tm.tm_wday);
  });
  def(I, dateProto, "toISOString", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; gmtime_r(&t, &tm);
    char buf[40];
    std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &tm);
    return Value::of(std::string(buf));
  });
  def(I, dateProto, "toLocaleDateString", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    char buf[24];
    std::strftime(buf, sizeof(buf), "%Y-%m-%d", &tm);
    return Value::of(std::string(buf));
  });
  def(I, dateProto, "toLocaleTimeString", [](Interpreter*, const Value& self, const std::vector<Value>&) -> Value {
    std::time_t t = (std::time_t)(toNumber(self.obj->props["__ms"]) / 1000);
    std::tm tm{}; localtime_r(&t, &tm);
    char buf[16];
    std::strftime(buf, sizeof(buf), "%H:%M:%S", &tm);
    return Value::of(std::string(buf));
  });
  getGlobal("Date").obj->props["prototype"] = Value{Type::Object, 0, false, "", dateProto, nullptr};
  def(I, getGlobal("Date").obj, "now", [](Interpreter*, const Value&, const std::vector<Value>&) -> Value {
    return Value::of((double)std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::system_clock::now().time_since_epoch()).count());
  });
  def(I, getGlobal("Date").obj, "parse", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    // Date.parse("YYYY-MM-DD[ HH:MM[:SS]]") → ms
    std::string s = args.empty() ? "" : toString(ip, args[0]);
    std::tm tm{};
    if (std::sscanf(s.c_str(), "%d-%d-%d %d:%d:%d",
                    &tm.tm_year, &tm.tm_mon, &tm.tm_mday, &tm.tm_hour, &tm.tm_min, &tm.tm_sec) >= 3) {
      tm.tm_year -= 1900; tm.tm_mon -= 1;
      time_t t = std::mktime(&tm);
      return Value::of((double)(t * 1000LL));
    }
    return Value::of(std::numeric_limits<double>::quiet_NaN());
  });

  // ---- RegExp ----
  registerNative("RegExp", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::string src = args.empty() ? "" : toString(ip, args[0]);
    std::string flags = args.size() > 1 ? toString(ip, args[1]) : "";
    auto o = ip->makeObject("RegExp");
    o->proto = ip->regexProto;
    o->props["source"] = Value::of(src);
    o->props["flags"] = Value::of(flags);
    o->props["global"] = Value::of(flags.find('g') != std::string::npos);
    o->props["lastIndex"] = Value::of(0.0);
    Value v; v.type = Type::Object; v.obj = o; return v;
  });
  def(I, regexProto, "test", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::regex re = buildRegex(ip, self);
    return Value::of(std::regex_search(args.empty() ? "" : toString(ip, args[0]), re));
  });
  def(I, regexProto, "exec", [](Interpreter* ip, const Value& self, const std::vector<Value>& args) -> Value {
    std::regex re = buildRegex(ip, self);
    std::string s = args.empty() ? "" : toString(ip, args[0]);
    std::smatch m;
    if (!std::regex_search(s, m, re)) return Value::null();
    auto arr = ip->makeArrayObject();
    for (size_t i = 0; i < m.size(); i++) arr->props[std::to_string(i)] = Value::of(m[i].str());
    arr->props["length"] = Value::of((double)m.size());
    arr->props["index"] = Value::of((double)m.position());
    Value v; v.type = Type::Object; v.obj = arr; return v;
  });
  def(I, regexProto, "toString", [](Interpreter* ip, const Value& self, const std::vector<Value>&) -> Value {
    return Value::of("/" + toString(ip, ip->getMember(self, "source")) + "/" + toString(ip, ip->getMember(self, "flags")));
  });

  // ---- Error ----
  registerNative("Error", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    return ip->makeError(args.empty() ? "" : toString(ip, args[0]), "Error");
  });
  getGlobal("Error").obj->props["prototype"] = Value{Type::Object, 0, false, "", errorProto, nullptr};
  registerNative("TypeError", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    return ip->makeError(args.empty() ? "" : toString(ip, args[0]), "TypeError");
  });
  registerNative("RangeError", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    return ip->makeError(args.empty() ? "" : toString(ip, args[0]), "RangeError");
  });

  // ---- timers & encoding ----
  registerNative("setTimeout", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isFunction()) return Value::of(0.0);
    int delay = args.size() > 1 ? (int)toNumber(args[1]) : 0;
    int fnId = ip->functionTableId(args[0]);
    if (ip->hostTimer) return Value::of((double)ip->hostTimer(delay, false, fnId));
    return Value::of(0.0);
  });
  registerNative("setInterval", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (args.empty() || !args[0].isFunction()) return Value::of(0.0);
    int delay = args.size() > 1 ? (int)toNumber(args[1]) : 0;
    int fnId = ip->functionTableId(args[0]);
    if (ip->hostTimer) return Value::of((double)ip->hostTimer(delay, true, fnId));
    return Value::of(0.0);
  });
  registerNative("clearTimeout", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (ip->hostClearTimer && !args.empty()) ip->hostClearTimer((int)toNumber(args[0]));
    return Value();
  });
  registerNative("clearInterval", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    if (ip->hostClearTimer && !args.empty()) ip->hostClearTimer((int)toNumber(args[0]));
    return Value();
  });
  registerNative("encodeURIComponent", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::string s = args.empty() ? "" : toString(ip, args[0]);
    std::string out;
    for (unsigned char c : s) {
      if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' ||
          c == '\'' || c == '(' || c == ')') {
        out.push_back((char)c);
      } else {
        char buf[8];
        std::snprintf(buf, sizeof(buf), "%%%02X", c);
        out += buf;
      }
    }
    return Value::of(out);
  });
  registerNative("decodeURIComponent", [](Interpreter* ip, const Value&, const std::vector<Value>& args) -> Value {
    std::string s = args.empty() ? "" : toString(ip, args[0]);
    std::string out;
    for (size_t i = 0; i < s.size(); i++) {
      if (s[i] == '%' && i + 2 < s.size()) {
        int v = std::stoi(s.substr(i + 1, 2), nullptr, 16);
        out.push_back((char)v);
        i += 2;
        continue;
      }
      out.push_back(s[i]);
    }
    return Value::of(out);
  });
  auto globalProxy = makeObject("Global");
  Value gpVal;
  gpVal.type = Type::Object;
  gpVal.obj = globalProxy;
  setGlobal("globalThis", gpVal);
  globalThisValue = gpVal;
  setGlobal("global", gpVal);
  setGlobal("undefined", Value());
  setGlobal("NaN", Value::of(NAN));
  setGlobal("Infinity", Value::of(INFINITY));
}

}  // namespace js
}  // namespace mini
