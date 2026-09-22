#pragma once
#include <memory>
#include <string>
#include <vector>
#include <utility>
#include "js_value.h"

namespace mini {
namespace js {

enum class NKind {
  Program, Number, String, Bool, Null, Undefined, Ident, This, Regex,
  Array, Object, Member, Index, Call, New, Binary, Logical, Unary, Update,
  Assign, Ternary, Template, Seq, Spread,
  VarDecl, FuncDecl, FuncExpr, ArrowFunc,
  Return, If, For, ForIn, ForOf, While, DoWhile, Block, ExprStmt,
  Try, Throw, Switch, Break, Continue, Empty
};

struct Node {
  NKind kind;
  double num = 0;
  bool boolean = false;
  std::string str;                 // literal text / op / name / regex pattern
  std::string str2;                // regex flags / etc.
  std::vector<std::shared_ptr<Node>> kids;
  std::vector<std::string> names;
  std::vector<std::pair<std::string, std::shared_ptr<Node>>> props;  // object literal / var decls
  int line = 0;

  explicit Node(NKind k) : kind(k) {}
  std::shared_ptr<Node> at(size_t i) const { return i < kids.size() ? kids[i] : nullptr; }
};

using NodePtr = std::shared_ptr<Node>;

inline NodePtr mk(NKind k) { return std::make_shared<Node>(k); }

}  // namespace js
}  // namespace mini
