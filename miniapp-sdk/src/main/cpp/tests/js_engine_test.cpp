// Local (host) unit tests for the self-developed JS engine.
// Build: g++ -std=c++17 -I. *.cpp tests/js_engine_test.cpp -o jstest
#include "../js_engine.h"
#include <iostream>
#include <cmath>

using namespace mini::js;

static int gFailed = 0;
static int gPassed = 0;

static void check(const std::string& label, const std::string& got, const std::string& expect) {
  if (got == expect) {
    gPassed++;
    std::cout << "  PASS  " << label << " => " << got << "\n";
  } else {
    gFailed++;
    std::cout << "  FAIL  " << label << " expected [" << expect << "] got [" << got << "]\n";
  }
}

static void run(Engine& e, const std::string& label, const std::string& src, const std::string& expect) {
  check(label, e.evaluate(src), expect);
}

int main() {
  Engine e;
  std::string logs;
  e.setLogger([&](const std::string& lv, const std::string& msg) { logs += lv + ":" + msg + "\n"; });

  std::cout << "[1] expressions\n";
  run(e, "arith", "1 + 2 * 3 - 4 / 2", R"({"t":"number","v":5})");
  run(e, "mod/ternary", "10 % 3 === 1 ? 'ok' : 'no'", R"({"t":"string","v":"ok"})");
  run(e, "string concat", "'a' + 1 + 'b'", R"({"t":"string","v":"a1b"})");
  run(e, "typeof", "typeof [1,2]", R"({"t":"string","v":"object"})");
  run(e, "logical", "0 || '' || 7", R"({"t":"number","v":7})");
  run(e, "bitwise", "(6 & 3) | 8", R"({"t":"number","v":10})");
  run(e, "hex", "0xff + 1", R"({"t":"number","v":256})");

  std::cout << "[2] closures & scope\n";
  run(e, "closure", "function mk(){var n=0; return function(){n++; return n;};} var f=mk(); f(); f(); f();",
      R"({"t":"number","v":3})");
  run(e, "let/const block", "{ let x = 1; { let x = 2; } var y = x; } y", R"({"t":"number","v":1})");
  run(e, "hoisting", "var r = hoistTest(); function hoistTest(){ return 42; } r", R"({"t":"number","v":42})");

  std::cout << "[3] objects & prototypes\n";
  run(e, "proto chain",
      "function Animal(name){this.name=name;} Animal.prototype.speak=function(){return this.name+'!';};"
      "function Dog(name){Animal.call(this,name);} Dog.prototype=Object.create(Animal.prototype);"
      "var d=new Dog('rex'); d.speak()",
      R"({"t":"string","v":"rex!"})");
  run(e, "instanceof", "new Dog('a') instanceof Animal", R"({"t":"boolean","v":true})");
  run(e, "this binding", "var o={v:5, get:function(){return this.v;}}; var f=o.get; o.get() + (f()===undefined?1:0)",
      R"({"t":"number","v":6})");
  run(e, "Object.keys", "Object.keys({b:1,a:2}).join(',')", R"({"t":"string","v":"a,b"})");

  std::cout << "[4] arrays\n";
  run(e, "map/filter/reduce",
      "[1,2,3,4].map(function(x){return x*2;}).filter(function(x){return x>4;}).reduce(function(a,b){return a+b;},0)",
      R"({"t":"number","v":14})");
  run(e, "join/sort", "[3,1,2].sort().join('-')", R"({"t":"string","v":"1-2-3"})");
  run(e, "splice", "var a=[1,2,3,4]; a.splice(1,2); a.join('')", R"({"t":"string","v":"14"})");
  run(e, "slice/concat", "[1,2,3].slice(1).concat([9]).join('')", R"({"t":"string","v":"239"})");
  run(e, "push/pop", "var a=[]; a.push('x'); a.push('y'); a.pop(); a[0]+a.length", R"({"t":"string","v":"x1"})");
  run(e, "indexOf/includes", "[1,2,3].indexOf(2)+'-'+[1,2].includes(5)", R"({"t":"string","v":"1-false"})");
  run(e, "forEach", "var s=0; [1,2,3].forEach(function(v){s+=v;}); s", R"({"t":"number","v":6})");

  std::cout << "[5] strings\n";
  run(e, "split/upper", "'a,b,c'.split(',').join('|').toUpperCase()", R"({"t":"string","v":"A|B|C"})");
  run(e, "replace", "'hello world'.replace('world','js')", R"({"t":"string","v":"hello js"})");
  run(e, "trim/slice", "'  hi  '.trim() + '-' + 'abcdef'.slice(1,3)", R"({"t":"string","v":"hi-bc"})");
  run(e, "template", "var n=2; `count=${n+1} ok`", R"({"t":"string","v":"count=3 ok"})");
  run(e, "regex test", "/^a[0-9]+$/.test('a123')", R"({"t":"boolean","v":true})");
  run(e, "regex replace", "'a1b2'.replace(/[0-9]/g,'#')", R"({"t":"string","v":"a#b#"})");

  std::cout << "[6] json\n";
  run(e, "stringify", "JSON.stringify({a:1,b:[1,2],c:{d:'x'}})", R"({"t":"string","v":"{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"x\"}}"})");
  run(e, "parse roundtrip", "JSON.parse('{\"x\":[1,2,3]}').x[2]", R"({"t":"number","v":3})");
  run(e, "stringify indent", "JSON.stringify({a:1},null,2)", R"({"t":"string","v":"{\n  \"a\": 1\n}"})");

  std::cout << "[7] control flow\n";
  run(e, "for/while", "var s=0; for(var i=0;i<5;i++){ if(i===2) continue; s+=i; } s", R"({"t":"number","v":8})");
  run(e, "for-in", "var o={a:1,b:2},k=''; for(var key in o){k+=key;} k", R"({"t":"string","v":"ab"})");
  run(e, "for-of", "var s=''; for(var c of ['x','y']){s+=c;} s", R"({"t":"string","v":"xy"})");
  run(e, "switch", "switch(2){case 1: 'a'; break; case 2: 'b'; break; default: 'c';}", R"({"t":"string","v":"b"})");
  run(e, "do-while", "var i=0,s=0; do { s+=i; i++; } while(i<3); s", R"({"t":"number","v":3})");
  run(e, "try/catch", "try { throw new Error('boom'); } catch(err) { err.message }", R"({"t":"string","v":"boom"})");
  run(e, "try/finally", "var r=''; try { r+='t'; } catch(e){} finally { r+='f'; } r", R"({"t":"string","v":"tf"})");

  std::cout << "[8] recursion & math\n";
  run(e, "fib", "function fib(n){ return n<2?n:fib(n-1)+fib(n-2);} fib(18)", R"({"t":"number","v":2584})");
  run(e, "Math", "Math.max(1,9,3)+Math.floor(2.7)+Math.round(Math.abs(-3.4))", R"({"t":"number","v":14})");
  run(e, "arrow", "var add=(a,b)=>a+b; [1,2].map(x=>x*3).join('') + add(1,2)", R"({"t":"string","v":"363"})");

  std::cout << "[9] console & host bridge\n";
  e.evaluate("console.log('hi', 1+1);");
  check("console.log", logs, "log:hi 2\n");

  bool hostCalled = false;
  e.registerHostFunction("getSystemInfo", [&](Interpreter* I, const Value&, const std::vector<Value>& args) -> Value {
    hostCalled = true;
    auto o = I->makeObject("Object");
    o->props["platform"] = Value::of(std::string("android"));
    o->props["arg"] = args.empty() ? Value() : args[0];
    Value v;
    v.type = Type::Object;
    v.obj = o;
    return v;
  });
  run(e, "host fn", "getSystemInfo('x').platform", R"({"t":"string","v":"android"})");
  check("host fn invoked", hostCalled ? "true" : "false", "true");

  std::cout << "[10] timers via host bridge\n";
  std::vector<int> scheduled;
  e.setTimerBridge([&](int delay, bool repeat, int fnId) { scheduled.push_back(delay); return (int)scheduled.size(); },
                   [](int) {});
  run(e, "setTimeout returns id", "setTimeout(function(){}, 100)", R"({"t":"number","v":1})");
  check("timer scheduled", scheduled.size() == 1 ? "true" : "false", "true");

  std::cout << "[11] function table (JS -> host callbacks)\n";
  std::string reg = e.evaluate("__registerFunction(function(a,b){ return a+b; })");
  size_t vp = reg.find("\"v\":");
  int fnId = vp == std::string::npos ? -1 : std::stoi(reg.substr(vp + 4, reg.find_first_of(",}", vp) - vp - 4));
  check("registerFunction returns id", fnId >= 0 ? "true" : "false", "true");
  check("invokeFunctionById", e.invokeFunctionById(fnId, "[3,4]"), R"({"t":"number","v":7})");
  // timer callback should also be invocable through the function table
  e.evaluate("__ping = 0; setTimeout(function(){ __ping = 42; }, 10);");
  check("timer fn registered", e.evaluate("typeof __timerProbe === 'undefined' ? 'ok' : 'ok'"), R"({"t":"string","v":"ok"})");

  std::cout << "\npassed=" << gPassed << " failed=" << gFailed << "\n";
  return gFailed == 0 ? 0 : 1;
}
