#pragma once
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>
#include "js_value.h"
#include "js_ast.h"

namespace mini {
namespace js {

struct Environment : std::enable_shared_from_this<Environment> {
  std::shared_ptr<Environment> parent;
  bool isFunctionScope = false;
  std::unordered_map<std::string, Value> vars;
  std::unordered_map<std::string, bool> isConst;

  bool hasOwn(const std::string& n) const { return vars.count(n) > 0; }
  Value getOwn(const std::string& n) const {
    auto it = vars.find(n);
    return it == vars.end() ? Value() : it->second;
  }
  Environment* findOwner(const std::string& n) {
    Environment* e = this;
    while (e) {
      if (e->vars.count(n)) return e;
      e = e->parent.get();
    }
    return nullptr;
  }
  Value get(const std::string& n) const {
    const Environment* e = this;
    while (e) {
      auto it = e->vars.find(n);
      if (it != e->vars.end()) return it->second;
      e = e->parent.get();
    }
    return Value();
  }
};

using EnvPtr = std::shared_ptr<Environment>;

class Interpreter {
 public:
  Interpreter();
  ~Interpreter();

  void initBuiltins();
  EnvPtr globalEnv() { return global_; }
  EnvPtr newEnv(EnvPtr parent, bool fnScope = false) {
    auto e = std::make_shared<Environment>();
    e->parent = parent;
    e->isFunctionScope = fnScope;
    return e;
  }

  // evaluation
  Value eval(const std::string& source, const std::string& file = "<eval>");
  Value evalNode(NodePtr node, EnvPtr env);
  Value exec(NodePtr node, EnvPtr env);

  // calls
  Value callFunction(std::shared_ptr<JsFunction> fn, Value self,
                     const std::vector<Value>& args, bool asConstructor = false);
  Value callValue(const Value& fnVal, Value self, const std::vector<Value>& args);

  // object model
  std::shared_ptr<JsObject> makeObject(const std::string& cls = "Object");
  std::shared_ptr<JsObject> makeArrayObject();
  std::shared_ptr<JsObject> makeFunctionObject(std::shared_ptr<JsFunction> fn);
  Value getMember(const Value& obj, const std::string& key);
  Value getMemberRecursive(std::shared_ptr<JsObject> obj, const std::string& key);
  void setMember(const Value& obj, const std::string& key, const Value& val);
  void defineProperty(std::shared_ptr<JsObject> obj, const std::string& key, const Value& val);
  bool deleteMember(const Value& obj, const std::string& key);
  Value makeStringObject(const std::string& s);
  Value makeError(const std::string& msg, const std::string& cls = "Error");
  [[noreturn]] void throwError(const std::string& msg, const std::string& cls = "Error");
  [[noreturn]] void throwValue(const Value& v);

  // host bridge
  void registerNative(const std::string& name, NativeFn fn);
  void setGlobal(const std::string& name, const Value& v);
  Value getGlobal(const std::string& name);

  // prototypes
  std::shared_ptr<JsObject> objectProto;
  std::shared_ptr<JsObject> arrayProto;
  std::shared_ptr<JsObject> functionProto;
  std::shared_ptr<JsObject> stringProto;
  std::shared_ptr<JsObject> numberProto;
  std::shared_ptr<JsObject> boolProto;
  std::shared_ptr<JsObject> errorProto;
  std::shared_ptr<JsObject> regexProto;

  // timers scheduled to host
  std::function<int(int, bool, int)> hostTimer;   // (delayMs, repeat, fnId) -> timerId
  std::function<void(int)> hostClearTimer;
  std::function<void(const std::string&, const std::string&)> hostLog;

  std::string lastError;
  Value globalThisValue;

  // function table shared with the host (callbacks registered from JS)
  std::vector<Value>* functionTableRef = nullptr;
  int functionTableId(const Value& fn);

 private:
  EnvPtr global_;
  friend struct InterpAccess;
};

void jsonStringifyInto(Interpreter* I, std::string& out, const Value& v, int indent, int depth,
                       const std::vector<const JsObject*>& stack);

}  // namespace js
}  // namespace mini
