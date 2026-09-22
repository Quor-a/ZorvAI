#pragma once
#include <string>
#include <memory>
#include <vector>
#include <unordered_map>
#include <functional>
#include <cmath>

namespace mini {
namespace js {

struct Node;
class Interpreter;
struct JsObject;
struct JsFunction;
struct Environment;

enum class Type { Undefined, Null, Bool, Number, String, Object, Function };

struct Value;

using NativeFn = std::function<Value(class Interpreter* interp, const Value& self,
                                     const std::vector<Value>& args)>;

struct Value {
  Type type = Type::Undefined;
  double num = 0;
  bool boolean = false;
  std::string str;
  std::shared_ptr<JsObject> obj;
  std::shared_ptr<JsFunction> fn;

  static Value undefined() { return Value(); }
  static Value null() { Value v; v.type = Type::Null; return v; }
  static Value of(bool b) { Value v; v.type = Type::Bool; v.boolean = b; return v; }
  static Value of(double d) { Value v; v.type = Type::Number; v.num = d; return v; }
  static Value of(const std::string& s) { Value v; v.type = Type::String; v.str = s; return v; }
  static Value of(const char* s) { return of(std::string(s)); }

  bool isUndefined() const { return type == Type::Undefined; }
  bool isNull() const { return type == Type::Null; }
  bool isObject() const { return type == Type::Object; }
  bool isFunction() const { return type == Type::Function; }
  bool isString() const { return type == Type::String; }
  bool isNumber() const { return type == Type::Number; }
  bool isBool() const { return type == Type::Bool; }

  std::string typeName() const {
    switch (type) {
      case Type::Undefined: return "undefined";
      case Type::Null: return "object";
      case Type::Bool: return "boolean";
      case Type::Number: return "number";
      case Type::String: return "string";
      case Type::Object: return "object";
      case Type::Function: return "function";
    }
    return "undefined";
  }
};

struct JsObject {
  std::unordered_map<std::string, Value> props;
  std::weak_ptr<JsObject> proto;
  std::string className = "Object";
  bool extensible = true;
  void* host = nullptr;          // host handle (unused by engine itself)
};

struct JsFunction {
  std::string name;
  std::vector<std::string> params;
  std::vector<std::shared_ptr<Node>> paramDefaults;  // 与 params 对齐；nullptr = 无默认
  std::shared_ptr<Node> body;              // block node
  std::shared_ptr<Environment> closure;    // defining environment
  NativeFn native;                         // host function
  bool isNative = false;
  bool isArrow = false;
  Value capturedThis;                // 箭头函数词法 this（定义处）
  std::shared_ptr<JsObject> obj;           // function-object properties (prototype...)
};


// ---- conversions (ECMA-ish, self implemented) ----
bool toBool(const Value& v);
double toNumber(const Value& v);
std::string toString(Interpreter* interp, const Value& v);
std::string numberToString(double d);
bool looseEquals(Interpreter* interp, const Value& a, const Value& b);
bool strictEquals(const Value& a, const Value& b);

struct BreakSignal {};
struct ContinueSignal {};

struct JsException {
  Value value;
  std::string message;
  explicit JsException(const Value& v, const std::string& m) : value(v), message(m) {}
};

struct ReturnSignal {
  Value value;
};

}  // namespace js
}  // namespace mini
