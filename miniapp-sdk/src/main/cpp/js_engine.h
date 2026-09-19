#pragma once
#include <string>
#include <vector>
#include <functional>
#include "js_value.h"
#include "js_runtime.h"

namespace mini {
namespace js {

// Facade used by the JNI layer: everything is exchanged as JSON strings so the
// Java side never needs to know the C++ value layout.
class Engine {
 public:
  Engine();
  ~Engine();

  // returns JSON: {"t":"number"|"string"|"boolean"|"object"|"null"|"undefined"|"function","v":...}
  std::string evaluate(const std::string& source);
  std::string callFunction(const std::string& name, const std::string& argsJson);
  std::string invokeFunctionById(int fnId, const std::string& argsJson);
  int registerFunction(const Value& fn);

  void registerHostFunction(const std::string& name, NativeFn fn);
  void setLogger(std::function<void(const std::string&, const std::string&)> log);
  void setTimerBridge(std::function<int(int, bool, int)> setTimer, std::function<void(int)> clear);

  std::string lastError() const { return interp_->lastError; }
  Interpreter* interp() { return interp_.get(); }

 private:
  std::unique_ptr<Interpreter> interp_;
  std::vector<Value> functionTable_;
};

}  // namespace js
}  // namespace mini
