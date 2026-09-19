#pragma once
#include <vector>
#include <string>
#include "js_ast.h"
#include "js_lexer.h"

namespace mini {
namespace js {

class Parser {
 public:
  explicit Parser(const std::vector<Token>& toks) : toks_(toks) {}
  NodePtr parseProgram();
  NodePtr parseExpressionOnly();

 private:
  const std::vector<Token>& toks_;
  size_t idx_ = 0;
  static long destructCounter_;
  static long arraySlotIdx_;

  const Token& cur() const { return toks_[idx_]; }
  const Token& peek(size_t n = 1) const {
    size_t i = idx_ + n;
    return i < toks_.size() ? toks_[i] : toks_.back();
  }
  bool isEnd() const { return cur().type == Tok::End; }
  bool isPunct(const char* p) const { return cur().type == Tok::Punct && cur().text == p; }
  bool isKeyword(const char* k) const { return cur().type == Tok::Keyword && cur().text == k; }
  bool eatPunct(const char* p) {
    if (isPunct(p)) { idx_++; return true; }
    return false;
  }
  bool eatKeyword(const char* k) {
    if (isKeyword(k)) { idx_++; return true; }
    return false;
  }
  void expectPunct(const char* p) {
    if (!eatPunct(p)) error(std::string("expected '") + p + "'");
  }
  [[noreturn]] void error(const std::string& msg) const;

  NodePtr parseStatement();
  NodePtr parseBlock();
  NodePtr parseVarDecl();
  NodePtr parseFunction(bool declaration);
  NodePtr parseIf();
  NodePtr parseFor();
  NodePtr parseWhile();
  NodePtr parseDoWhile();
  NodePtr parseSwitch();
  NodePtr parseTry();
  NodePtr parseReturnThrow();

  NodePtr parseExpression();
  NodePtr parseAssignment();
  NodePtr parseTernary();
  NodePtr parseBinary(int minPrec);
  NodePtr parseUnary();
  NodePtr parsePostfix();
  NodePtr parseCallMember(NodePtr obj);
  NodePtr parsePrimary();
  NodePtr parseObjectLiteral();
  NodePtr parseArrayLiteral();
  NodePtr parseTemplateLiteral();
  NodePtr parseArrowOrParenExpr();
  NodePtr parseFunctionBodyAndParams(NodePtr fn, bool isExpr);
  int precedence(const std::string& op) const;
};

// helper: parse source into AST (throws on syntax error)
NodePtr parseSource(const std::string& source);

}  // namespace js
}  // namespace mini
