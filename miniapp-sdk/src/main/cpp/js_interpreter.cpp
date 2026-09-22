#include "js_runtime.h"
#include "js_lexer.h"
#include "js_parser.h"
#include <cmath>
#include <algorithm>
#include <regex>
#include <sstream>

namespace mini {
namespace js {

static Value toPrimitive(Interpreter* interp, const Value& v) {
  if (v.isObject()) {
    Value vo = interp->getMember(v, "valueOf");
    if (vo.isFunction()) {
      Value r = interp->callValue(vo, v, {});
      if (!r.isObject() && !r.isFunction()) return r;
    }
    Value ts = interp->getMember(v, "toString");
    if (ts.isFunction()) {
      Value r = interp->callValue(ts, v, {});
      if (!r.isObject() && !r.isFunction()) return r;
    }
  }
  if (v.isFunction()) return Value::of(toString(interp, v));
  return v;
}

static Value addValues(Interpreter* interp, const Value& a, const Value& b) {
  Value pa = toPrimitive(interp, a);
  Value pb = toPrimitive(interp, b);
  if (pa.isString() || pb.isString()) return Value::of(toString(interp, pa) + toString(interp, pb));
  return Value::of(toNumber(pa) + toNumber(pb));
}

static double numericBinary(const std::string& op, double a, double b) {
  if (op == "+") return a + b;
  if (op == "-") return a - b;
  if (op == "*") return a * b;
  if (op == "/") return b == 0 ? NAN : a / b;
  if (op == "%") return b == 0 ? NAN : std::fmod(a, b);
  if (op == "&") return (double)((long long)a & (long long)b);
  if (op == "|") return (double)((long long)a | (long long)b);
  if (op == "^") return (double)((long long)a ^ (long long)b);
  if (op == "<<") return (double)((long long)a << ((long long)b & 31));
  if (op == ">>") return (double)((long long)a >> ((long long)b & 31));
  if (op == ">>>") return (double)((unsigned long long)(long long)a >> ((int)(long long)b & 31));
  if (op == "**") return std::pow(a, b);
  return NAN;
}

static Value binaryOp(Interpreter* interp, const std::string& op, const Value& a, const Value& b) {
  if (op == "+") return addValues(interp, a, b);
  if (op == "===") return Value::of(strictEquals(a, b));
  if (op == "!==") return Value::of(!strictEquals(a, b));
  if (op == "==") return Value::of(looseEquals(interp, a, b));
  if (op == "!=") return Value::of(!looseEquals(interp, a, b));
  if (op == "<" || op == ">" || op == "<=" || op == ">=") {
    Value pa = toPrimitive(interp, a), pb = toPrimitive(interp, b);
    if (pa.isString() && pb.isString()) {
      int c = pa.str.compare(pb.str);
      if (op == "<") return Value::of(c < 0);
      if (op == ">") return Value::of(c > 0);
      if (op == "<=") return Value::of(c <= 0);
      return Value::of(c >= 0);
    }
    double x = toNumber(pa), y = toNumber(pb);
    if (op == "<") return Value::of(x < y);
    if (op == ">") return Value::of(x > y);
    if (op == "<=") return Value::of(x <= y);
    return Value::of(x >= y);
  }
  if (op == "in") {
    if (!b.isObject()) interp->throwError("in: right operand must be object", "TypeError");
    return Value::of(b.obj->props.count(toString(interp, a)) > 0);
  }
  if (op == "instanceof") {
    if (!a.isObject() || !b.isFunction() || !b.fn || !b.fn->obj) return Value::of(false);
    Value proto = b.fn->obj->props.count("prototype") ? b.fn->obj->props["prototype"] : Value();
    if (!proto.isObject()) return Value::of(false);
    std::shared_ptr<JsObject> cur = a.obj;
    int guard = 0;
    while (cur && guard++ < 64) {
      if (cur.get() == proto.obj.get()) return Value::of(true);
      cur = cur->proto.lock();
    }
    return Value::of(false);
  }
  return Value::of(numericBinary(op, toNumber(a), toNumber(b)));
}

static Value findThis(EnvPtr env) {
  // this 可能在任一祖先作用域（函数体创建子作用域后，子链上没有 this）
  EnvPtr e = env;
  while (e) {
    auto it = e->vars.find("this");
    if (it != e->vars.end()) return it->second;
    e = e->parent;
  }
  return Value();
}

static Value makeFunctionValue(Interpreter* interp, NodePtr node, EnvPtr env) {
  auto fn = std::make_shared<JsFunction>();
  fn->name = node->str;
  fn->params = node->names;
  // 参数默认值：AST props 里 "default:<name>" → 与 params 对齐的 defaults 槽
  fn->paramDefaults.assign(node->names.size(), nullptr);
  static const std::string kDefaultPrefix = "\x03" "default:";
  for (auto& p : node->props) {
    if (p.first.rfind(kDefaultPrefix, 0) == 0) {
      std::string pname = p.first.substr(kDefaultPrefix.size());
      for (size_t i = 0; i < node->names.size(); i++) {
        if (node->names[i] == pname) fn->paramDefaults[i] = p.second;
      }
    }
  }
  fn->body = node->kids.empty() ? mk(NKind::Block) : node->kids[0];
  fn->closure = env;
  fn->isArrow = (node->kind == NKind::ArrowFunc);
  // 箭头函数 this 词法绑定：捕获定义作用域的 this（setTimeout(() => this.setData(...)) 场景）
  if (fn->isArrow) fn->capturedThis = findThis(env);
  Value v;
  v.type = Type::Function;
  v.fn = fn;
  v.obj = interp->makeFunctionObject(fn);
  fn->obj = v.obj;
  return v;
}

static EnvPtr varScope(EnvPtr env) {
  EnvPtr e = env;
  while (e->parent && !e->isFunctionScope) e = e->parent;
  return e;
}

static void hoist(NodePtr block, EnvPtr env, Interpreter* interp) {
  for (auto& k : block->kids) {
    if (k->kind == NKind::FuncDecl) {
      env->vars[k->str] = makeFunctionValue(interp, k, env);
    } else if (k->kind == NKind::VarDecl) {
      EnvPtr target = k->str == "var" ? varScope(env) : env;
      for (auto& p : k->props) {
        if (!target->hasOwn(p.first)) target->vars[p.first] = Value();
      }
    }
  }
}

static Value makeRegexValue(Interpreter* interp, const std::string& pattern, const std::string& flags) {
  auto o = interp->makeObject("RegExp");
  o->proto = interp->regexProto;
  o->props["source"] = Value::of(pattern);
  o->props["flags"] = Value::of(flags);
  o->props["global"] = Value::of(flags.find('g') != std::string::npos);
  o->props["ignoreCase"] = Value::of(flags.find('i') != std::string::npos);
  o->props["multiline"] = Value::of(flags.find('m') != std::string::npos);
  o->props["lastIndex"] = Value::of(0.0);
  Value v;
  v.type = Type::Object;
  v.obj = o;
  return v;
}

// lvalue resolution ---------------------------------------------------------
static Value readLvalue(Interpreter* interp, NodePtr target, EnvPtr env) {
  if (target->kind == NKind::Ident) return env->get(target->str);
  if (target->kind == NKind::Member) return interp->getMember(interp->exec(target->kids[0], env), target->str);
  if (target->kind == NKind::Index) {
    Value o = interp->exec(target->kids[0], env);
    Value k = interp->exec(target->kids[1], env);
    return interp->getMember(o, toString(interp, k));
  }
  interp->throwError("Invalid assignment target", "SyntaxError");
}

static void writeLvalue(Interpreter* interp, NodePtr target, const Value& v, EnvPtr env) {
  if (target->kind == NKind::Ident) {
    Environment* owner = env->findOwner(target->str);
    if (owner && owner->isConst[target->str]) {
      interp->throwError("Assignment to constant variable '" + target->str + "'", "TypeError");
    }
    if (owner) owner->vars[target->str] = v;
    else interp->globalEnv()->vars[target->str] = v;
    return;
  }
  if (target->kind == NKind::Member) {
    interp->setMember(interp->exec(target->kids[0], env), target->str, v);
    return;
  }
  if (target->kind == NKind::Index) {
    Value o = interp->exec(target->kids[0], env);
    Value k = interp->exec(target->kids[1], env);
    interp->setMember(o, toString(interp, k), v);
    return;
  }
  interp->throwError("Invalid assignment target", "SyntaxError");
}

Value Interpreter::evalNode(NodePtr node, EnvPtr env) { return exec(node, env); }

Value Interpreter::exec(NodePtr n, EnvPtr env) {
  if (!n) return Value();
  switch (n->kind) {
    case NKind::Program: {
      hoist(n, env, this);
      Value last;
      for (auto& k : n->kids) last = exec(k, env);
      return last;
    }
    case NKind::Block: {
      EnvPtr local = newEnv(env);
      hoist(n, local, this);
      Value last;
      for (auto& k : n->kids) last = exec(k, local);
      return last;
    }
    case NKind::Number: return Value::of(n->num);
    case NKind::String: return Value::of(n->str);
    case NKind::Bool: return Value::of(n->boolean);
    case NKind::Null: return Value::null();
    case NKind::Undefined: return Value();
    case NKind::This: return env->get("this");
    case NKind::Ident: {
      Value v = env->get(n->str);
      if (v.isUndefined() && globalThisValue.isObject()) {
        auto it = globalThisValue.obj->props.find(n->str);
        if (it != globalThisValue.obj->props.end()) return it->second;
      }
      return v;
    }
    case NKind::Regex: return makeRegexValue(this, n->str, n->str2);
    case NKind::Empty: return Value();

    case NKind::Array: {
      auto arr = makeArrayObject();
      size_t out = 0;
      auto pushVal = [&](const Value& item) { arr->props[std::to_string(out++)] = item; };
      for (size_t i = 0; i < n->kids.size(); i++) {
        Value item = exec(n->kids[i], env);
        if (n->kids[i]->kind == NKind::Spread && item.isObject() && item.obj->className == "Array") {
          auto lit = item.obj->props.find("length");
          long len = lit == item.obj->props.end() ? 0 : (long)toNumber(lit->second);
          for (long j = 0; j < len; j++) {
            auto it = item.obj->props.find(std::to_string(j));
            pushVal(it != item.obj->props.end() ? it->second : Value());
          }
        } else {
          pushVal(item);
        }
      }
      arr->props["length"] = Value::of((double)out);
      Value v;
      v.type = Type::Object;
      v.obj = arr;
      return v;
    }
    case NKind::Object: {
      auto o = makeObject("Object");
      for (auto& p : n->props) {
        if (p.first == "\x03" "spread") {
          // 展开对象 {...src}：拷贝自有属性（不含 length）
          Value src = exec(p.second, env);
          if (src.isObject()) {
            for (auto& kv : src.obj->props) {
              if (kv.first == "length") continue;
              o->props[kv.first] = kv.second;
            }
          }
          continue;
        }
        o->props[p.first] = exec(p.second, env);
      }
      Value v;
      v.type = Type::Object;
      v.obj = o;
      return v;
    }
    case NKind::Template: {
      std::string out;
      for (auto& k : n->kids) out += toString(this, exec(k, env));
      return Value::of(out);
    }
    case NKind::Seq: {
      Value last;
      for (auto& k : n->kids) last = exec(k, env);
      return last;
    }
    case NKind::Spread: {
      // 展开标记节点：由父层（Array/Object/Call）识别 kind 决定是否展开；
      // 单独求值时就是其内层表达式的值
      return exec(n->kids.empty() ? n : n->kids[0], env);
    }
    case NKind::Member: return getMember(exec(n->kids[0], env), n->str);
    case NKind::Index: {
      Value o = exec(n->kids[0], env);
      Value k = exec(n->kids[1], env);
      return getMember(o, toString(this, k));
    }
    case NKind::Binary: {
      Value a = exec(n->kids[0], env);
      Value b = exec(n->kids[1], env);
      return binaryOp(this, n->str, a, b);
    }
    case NKind::Logical: {
      Value a = exec(n->kids[0], env);
      if (n->str == "&&") return toBool(a) ? exec(n->kids[1], env) : a;
      return toBool(a) ? a : exec(n->kids[1], env);
    }
    case NKind::Unary: {
      if (n->str == "typeof") {
        if (n->kids[0]->kind == NKind::Ident && !env->findOwner(n->kids[0]->str)) {
          return Value::of(std::string("undefined"));
        }
        Value v = exec(n->kids[0], env);
        return Value::of(v.typeName());
      }
      if (n->str == "delete") {
        NodePtr t = n->kids[0];
        if (t->kind == NKind::Index) {
          Value o = exec(t->kids[0], env);
          Value k = exec(t->kids[1], env);
          return Value::of(deleteMember(o, toString(this, k)));
        }
        if (t->kind == NKind::Member) {
          Value o = exec(t->kids[0], env);
          return Value::of(deleteMember(o, t->str));
        }
        return Value::of(true);
      }
      Value v = exec(n->kids[0], env);
      if (n->str == "!") return Value::of(!toBool(v));
      if (n->str == "void") return Value();
      if (n->str == "+") return Value::of(toNumber(v));
      if (n->str == "-") return Value::of(-toNumber(v));
      if (n->str == "~") return Value::of((double)~((long long)toNumber(v)));
      return Value();
    }
    case NKind::Update: {
      NodePtr t = n->kids[0];
      double old = toNumber(readLvalue(this, t, env));
      double nv = n->str == "++" ? old + 1 : old - 1;
      writeLvalue(this, t, Value::of(nv), env);
      return n->str2 == "pre" ? Value::of(nv) : Value::of(old);
    }
    case NKind::Assign: {
      if (n->str == "=") {
        Value v = exec(n->kids[1], env);
        writeLvalue(this, n->kids[0], v, env);
        return v;
      }
      std::string base = n->str.substr(0, n->str.size() - 1);
      Value cur = readLvalue(this, n->kids[0], env);
      Value rhs = exec(n->kids[1], env);
      Value res = binaryOp(this, base, cur, rhs);
      writeLvalue(this, n->kids[0], res, env);
      return res;
    }
    case NKind::Ternary: {
      return toBool(exec(n->kids[0], env)) ? exec(n->kids[1], env) : exec(n->kids[2], env);
    }
    case NKind::Call: {
      Value callee = exec(n->kids[0], env);
      Value self;
      if (n->kids[0]->kind == NKind::Member) self = exec(n->kids[0]->kids[0], env);
      else if (n->kids[0]->kind == NKind::Index) self = exec(n->kids[0]->kids[0], env);
      if (!callee.isFunction()) {
        // 错误带调用路径名：this.setData is not a function / wx.foo is not a function
        std::string path;
        const Node* cur2 = n->kids[0].get();
        while (cur2 && cur2->kind == NKind::Member) {
          path = "." + cur2->str + path;
          cur2 = cur2->kids.empty() ? nullptr : cur2->kids[0].get();
        }
        if (cur2 && cur2->kind == NKind::Ident) path = cur2->str + path;
        throwError(path.empty() ? toString(this, callee) + " is not a function"
                                : path + " is not a function", "TypeError");
      }
      std::vector<Value> args;
      for (size_t i = 1; i < n->kids.size(); i++) {
        Value a = exec(n->kids[i], env);
        if (n->kids[i]->kind == NKind::Spread && a.isObject() && a.obj->className == "Array") {
          auto lit = a.obj->props.find("length");
          long len = lit == a.obj->props.end() ? 0 : (long)toNumber(lit->second);
          for (long j = 0; j < len; j++) {
            auto it = a.obj->props.find(std::to_string(j));
            args.push_back(it != a.obj->props.end() ? it->second : Value());
          }
        } else {
          args.push_back(a);
        }
      }
      return callValue(callee, self, args);
    }
    case NKind::New: {
      Value callee = exec(n->kids[0], env);
      std::vector<Value> args;
      for (size_t i = 1; i < n->kids.size(); i++) args.push_back(exec(n->kids[i], env));
      if (!callee.isFunction()) throwError(toString(this, callee) + " is not a constructor", "TypeError");
      return callFunction(callee.fn, Value(), args, true);
    }
    case NKind::FuncDecl: return Value();  // hoisted
    case NKind::FuncExpr:
    case NKind::ArrowFunc: return makeFunctionValue(this, n, env);

    case NKind::VarDecl: {
      EnvPtr target = n->str == "var" ? varScope(env) : env;
      for (auto& p : n->props) {
        Value v = p.second ? exec(p.second, env) : Value();
        target->vars[p.first] = v;
        if (n->str == "const") target->isConst[p.first] = true;
      }
      return Value();
    }
    case NKind::ExprStmt: return exec(n->kids[0], env);
    case NKind::Return: {
      Value v = n->kids.empty() ? Value() : exec(n->kids[0], env);
      throw ReturnSignal{v};
    }
    case NKind::Throw: throwValue(n->kids.empty() ? Value() : exec(n->kids[0], env));
    case NKind::Break: throw BreakSignal();
    case NKind::Continue: throw ContinueSignal();

    case NKind::If: {
      if (toBool(exec(n->kids[0], env))) return exec(n->kids[1], env);
      if (n->kids.size() > 2) return exec(n->kids[2], env);
      return Value();
    }
    case NKind::While: {
      Value last;
      while (toBool(exec(n->kids[0], env))) {
        try {
          last = exec(n->kids[1], env);
        } catch (BreakSignal&) {
          break;
        } catch (ContinueSignal&) {
          continue;
        }
      }
      return last;
    }
    case NKind::DoWhile: {
      Value last;
      do {
        try {
          last = exec(n->kids[0], env);
        } catch (BreakSignal&) {
          break;
        } catch (ContinueSignal&) {
          if (!toBool(exec(n->kids[1], env))) break;
          continue;
        }
      } while (toBool(exec(n->kids[1], env)));
      return last;
    }
    case NKind::For: {
      EnvPtr local = newEnv(env);
      hoist(n, local, this);
      if (n->kids[0]) exec(n->kids[0], local);
      Value last;
      while (n->kids.size() > 1 && n->kids[1] && (n->kids[1]->kind == NKind::Empty || toBool(exec(n->kids[1], local)))) {
        try {
          last = exec(n->kids[3], local);
        } catch (BreakSignal&) {
          break;
        } catch (ContinueSignal&) {
        }
        if (n->kids.size() > 2 && n->kids[2]) exec(n->kids[2], local);
      }
      return last;
    }
    case NKind::ForIn: {
      Value objVal = exec(n->kids[1], env);
      std::vector<std::string> keys;
      if (objVal.isObject()) {
        for (auto& kv : objVal.obj->props) {
          if (kv.first == "length" && objVal.obj->className == "Array") continue;
          keys.push_back(kv.first);
        }
        std::sort(keys.begin(), keys.end());
      } else if (objVal.isString()) {
        for (size_t i = 0; i < objVal.str.size(); i++) keys.push_back(std::to_string(i));
      }
      EnvPtr local = newEnv(env);
      Value last;
      std::string forName;
      bool forDecl = n->kids[0]->kind == NKind::VarDecl;
      if (forDecl) forName = n->kids[0]->props.empty() ? std::string("") : n->kids[0]->props[0].first;
      else forName = n->kids[0]->str;
      for (auto& k : keys) {
        if (forDecl) local->vars[forName] = Value::of(k);
        else writeLvalue(this, n->kids[0], Value::of(k), local);
        try {
          last = exec(n->kids[2], local);
        } catch (BreakSignal&) {
          break;
        } catch (ContinueSignal&) {
          continue;
        }
      }
      return last;
    }
    case NKind::ForOf: {
      Value iterable = exec(n->kids[1], env);
      EnvPtr local = newEnv(env);
      Value last;
      std::string forName;
      bool forDecl = n->kids[0]->kind == NKind::VarDecl;
      if (forDecl) forName = n->kids[0]->props.empty() ? std::string("") : n->kids[0]->props[0].first;
      else forName = n->kids[0]->str;
      std::vector<Value> items;
      if (iterable.isObject() && iterable.obj->className == "Array") {
        double len = toNumber(iterable.obj->props["length"]);
        for (double i = 0; i < len; i++) items.push_back(iterable.obj->props[std::to_string((size_t)i)]);
      } else if (iterable.isString()) {
        for (char c : iterable.str) items.push_back(Value::of(std::string(1, c)));
      } else if (iterable.isObject()) {
        for (auto& kv : iterable.obj->props) items.push_back(kv.second);
      }
      for (auto& v : items) {
        if (forDecl) local->vars[forName] = v;
        else writeLvalue(this, n->kids[0], v, local);
        try {
          last = exec(n->kids[2], local);
        } catch (BreakSignal&) {
          break;
        } catch (ContinueSignal&) {
          continue;
        }
      }
      return last;
    }
    case NKind::Switch: {
      Value disc = exec(n->kids[0], env);
      size_t start = n->kids.size();
      for (size_t i = 1; i < n->kids.size(); i++) {
        if (n->kids[i]->str == "default" && start == n->kids.size()) start = i;
        if (n->kids[i]->str == "case" && strictEquals(disc, exec(n->kids[i]->kids[0], env))) {
          start = i;
          break;
        }
      }
      Value last;
      if (start < n->kids.size()) {
        for (size_t i = start; i < n->kids.size(); i++) {
          try {
            for (size_t j = 1; j < n->kids[i]->kids.size(); j++) last = exec(n->kids[i]->kids[j], env);
          } catch (BreakSignal&) {
            break;
          }
        }
      }
      return last;
    }
    case NKind::Try: {
      Value last;
      try {
        last = exec(n->kids[0], env);
      } catch (JsException& e) {
        if (n->kids.size() > 1 && n->kids[1] && n->kids[1]->kind != NKind::Empty) {
          EnvPtr local = newEnv(env);
          if (!n->names.empty()) local->vars[n->names[0]] = e.value;
          try {
            last = exec(n->kids[1], local);
          } catch (ReturnSignal&) {
            if (n->kids.size() > 2 && n->kids[2]->kind != NKind::Empty) exec(n->kids[2], env);
            throw;
          }
        }
      }
      if (n->kids.size() > 2 && n->kids[2] && n->kids[2]->kind != NKind::Empty) exec(n->kids[2], env);
      return last;
    }
  }
  return Value();
}

Value Interpreter::eval(const std::string& source, const std::string& file) {
  (void)file;
  NodePtr program = parseSource(source);
  try {
    return exec(program, global_);
  } catch (ReturnSignal& rs) {
    return rs.value;
  }
}

}  // namespace js
}  // namespace mini
