#include "js_parser.h"
#include <stdexcept>
#include <set>

namespace mini {
namespace js {

NodePtr parseSource(const std::string& source) {
  Lexer lx(source);
  std::vector<Token> toks = lx.tokenize();
  Parser p(toks);
  return p.parseProgram();
}

[[noreturn]] void Parser::error(const std::string& msg) const {
  throw std::runtime_error("SyntaxError: " + msg + " at line " + std::to_string(cur().line));
}

int Parser::precedence(const std::string& op) const {
  if (op == "||") return 1;
  if (op == "&&") return 2;
  if (op == "|") return 3;
  if (op == "^") return 4;
  if (op == "&") return 5;
  if (op == "==" || op == "!=" || op == "===" || op == "!==") return 6;
  if (op == "<" || op == ">" || op == "<=" || op == ">=" || op == "in" || op == "instanceof") return 7;
  if (op == "<<" || op == ">>" || op == ">>>") return 8;
  if (op == "+" || op == "-") return 9;
  if (op == "*" || op == "/" || op == "%") return 10;
  if (op == "**") return 11;
  return 0;
}

NodePtr Parser::parseProgram() {
  NodePtr prog = mk(NKind::Program);
  while (!isEnd()) {
    if (isPunct("}")) break;
    prog->kids.push_back(parseStatement());
  }
  return prog;
}

NodePtr Parser::parseExpressionOnly() {
  return parseExpression();
}

NodePtr Parser::parseBlock() {
  expectPunct("{");
  NodePtr block = mk(NKind::Block);
  while (!isEnd() && !isPunct("}")) block->kids.push_back(parseStatement());
  expectPunct("}");
  return block;
}

NodePtr Parser::parseStatement() {
  int line = cur().line;
  if (isPunct("{")) return parseBlock();
  if (eatPunct(";")) { NodePtr e = mk(NKind::Empty); e->line = line; return e; }
  if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
    NodePtr d = parseVarDecl();
    eatPunct(";");
    return d;
  }
  if (isKeyword("function")) return parseFunction(true);
  if (isKeyword("if")) return parseIf();
  if (isKeyword("for")) return parseFor();
  if (isKeyword("while")) return parseWhile();
  if (isKeyword("do")) return parseDoWhile();
  if (isKeyword("switch")) return parseSwitch();
  if (isKeyword("try")) return parseTry();
  if (isKeyword("return") || isKeyword("throw")) {
    NodePtr r = parseReturnThrow();
    eatPunct(";");
    return r;
  }
  if (isKeyword("break") || isKeyword("continue")) {
    NodePtr n = mk(isKeyword("break") ? NKind::Break : NKind::Continue);
    idx_++;
    eatPunct(";");
    n->line = line;
    return n;
  }
  NodePtr e = parseExpression();
  eatPunct(";");
  NodePtr s = mk(NKind::ExprStmt);
  s->kids.push_back(e);
  s->line = line;
  return s;
}

long Parser::destructCounter_ = 0;
long Parser::arraySlotIdx_ = 0;

NodePtr Parser::parseVarDecl() {
  NodePtr n = mk(NKind::VarDecl);
  n->str = cur().text;
  n->line = cur().line;
  idx_++;
  while (true) {
    // ---- 解构声明：const {a, b: c, d = 1} = obj / let [x, , z, ...rest] = arr ----
    if (isPunct("{") || isPunct("[")) {
      bool isObj = isPunct("{");
      idx_++;
      NodePtr block = mk(NKind::Block);   // 脱糖语句块
      std::string tmp = "\x03" "dt" + std::to_string(destructCounter_++);
      // tmp = init（init 在 pattern 之后出现，先占位后回填）
      NodePtr tmpDecl = mk(NKind::VarDecl);
      tmpDecl->str = n->str;
      tmpDecl->props.emplace_back(tmp, mk(NKind::Undefined));
      block->kids.push_back(tmpDecl);
      // 逐目标：target = tmp.key 或 tmp[i]
      while (!isEnd() && !isPunct(isObj ? "}" : "]")) {
        NodePtr srcRef = mk(NKind::Ident); srcRef->str = tmp;
        NodePtr member;
        std::string targetName;
        NodePtr defaultValue;
        if (isObj) {
          if (cur().type != Tok::Ident && cur().type != Tok::Keyword && cur().type != Tok::String) {
            error("expected destructuring key");
          }
          std::string key = cur().text;
          idx_++;
          if (eatPunct(":")) {
            // 别名 / 嵌套（嵌套模式简化为仅一层 Ident）
            if (cur().type != Tok::Ident) error("expected alias identifier");
            targetName = cur().text;
            idx_++;
          } else {
            targetName = key;
          }
          member = mk(NKind::Member);
          member->str = key;
          member->kids.push_back(srcRef);
        } else {
          if (isPunct(",") || isPunct("]")) {
            // 空位跳过（占一个槽位）
            arraySlotIdx_++;
            if (!eatPunct(",")) break;
            continue;
          }
          bool isRest = false;
          if (isPunct("...")) { idx_++; isRest = true; }
          else if (isPunct(".") && peek().text == ".") { idx_ += 2; isRest = true; }
          if (isRest) {
            // rest：[a, ...rest] → rest = arr.slice(当前槽位)
            if (cur().type != Tok::Ident) error("expected rest name");
            targetName = cur().text;
            idx_++;
            NodePtr restCall = mk(NKind::Call);
            NodePtr slice = mk(NKind::Member); slice->str = "slice"; slice->kids.push_back(srcRef);
            restCall->kids.push_back(slice);
            NodePtr from = mk(NKind::Number);
            from->num = (double)(arraySlotIdx_);
            restCall->kids.push_back(from);
            member = restCall;
          } else {
            if (cur().type != Tok::Ident) error("expected destructuring target");
            targetName = cur().text;
            idx_++;
            member = mk(NKind::Index);
            member->kids.push_back(srcRef);
            NodePtr k = mk(NKind::Number);
            k->num = (double)(arraySlotIdx_++);
            member->kids.push_back(k);
          }
        }
        if (eatPunct("=")) defaultValue = parseAssignment();   // { a = 1 } / [x = 5]
        NodePtr targetDecl = mk(NKind::VarDecl);
        targetDecl->str = n->str;
        NodePtr ref = member;
        if (defaultValue) {
          // target = (tmp.key !== undefined ? tmp.key : default) —— 用 ternary
          NodePtr undef = mk(NKind::Undefined);
          NodePtr cmp = mk(NKind::Binary); cmp->str = "!==";
          cmp->kids.push_back(ref); cmp->kids.push_back(undef);
          NodePtr tern = mk(NKind::Ternary);
          tern->kids.push_back(cmp);
          tern->kids.push_back(ref);
          tern->kids.push_back(defaultValue);
          ref = tern;
        }
        targetDecl->props.emplace_back(targetName, ref);
        block->kids.push_back(targetDecl);
        if (!eatPunct(",")) break;
      }
      expectPunct(isObj ? "}" : "]");
      arraySlotIdx_ = 0;
      if (eatPunct("=")) tmpDecl->props[0].second = parseAssignment();   // 回填真实 init
      n->line = tmpDecl->line;
      // 整个解构作为一条"多语句"返回：外层 parseStatement 会补分号。
      // 复用 Block 不可执行（exec(Block) 需要 scope）——把 block 展开为 Seq
      NodePtr seq = mk(NKind::Seq);
      seq->kids = block->kids;
      NodePtr stmt = mk(NKind::ExprStmt);
      stmt->kids.push_back(seq);
      return stmt;
    }
    if (cur().type != Tok::Ident) error("expected identifier");
    std::string name = cur().text;
    idx_++;
    NodePtr init = nullptr;
    if (eatPunct("=")) init = parseAssignment();
    n->props.emplace_back(name, init);
    if (!eatPunct(",")) break;
  }
  return n;
}

NodePtr Parser::parseReturnThrow() {
  bool isReturn = isKeyword("return");
  idx_++;
  NodePtr n = mk(isReturn ? NKind::Return : NKind::Throw);
  if (!isPunct(";") && !isPunct("}") && !isEnd()) n->kids.push_back(parseExpression());
  return n;
}

NodePtr Parser::parseIf() {
  idx_++;
  NodePtr n = mk(NKind::If);
  expectPunct("(");
  n->kids.push_back(parseExpression());
  expectPunct(")");
  n->kids.push_back(parseStatement());
  if (eatKeyword("else")) n->kids.push_back(parseStatement());
  return n;
}

NodePtr Parser::parseFor() {
  idx_++;
  expectPunct("(");
  // for (x in y) / for (x of y)
  bool declInit = (isKeyword("var") || isKeyword("let") || isKeyword("const")) &&
                  peek().type == Tok::Ident && peek(2).type == Tok::Keyword &&
                  (peek(2).text == "in" || peek(2).text == "of");
  if (cur().type == Tok::Ident && (peek().type == Tok::Keyword) &&
      (peek().text == "in" || peek().text == "of")) {
    NodePtr n = mk(peek().text == "in" ? NKind::ForIn : NKind::ForOf);
    NodePtr lhs = mk(NKind::Ident);
    lhs->str = cur().text;
    idx_ += 2;
    n->kids.push_back(lhs);
    n->kids.push_back(parseExpression());
    expectPunct(")");
    n->kids.push_back(parseStatement());
    return n;
  }
  if (declInit) {
    NodePtr n = mk(peek(2).text == "in" ? NKind::ForIn : NKind::ForOf);
    NodePtr decl = mk(NKind::VarDecl);
    decl->str = cur().text;
    decl->props.emplace_back(peek().text, nullptr);
    idx_ += 3;
    n->kids.push_back(decl);
    n->kids.push_back(parseExpression());
    expectPunct(")");
    n->kids.push_back(parseStatement());
    return n;
  }
  NodePtr n = mk(NKind::For);
  if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
    n->kids.push_back(parseVarDecl());
  } else if (!isPunct(";")) {
    n->kids.push_back(parseExpression());
  } else {
    n->kids.push_back(mk(NKind::Empty));
  }
  expectPunct(";");
  if (!isPunct(";")) n->kids.push_back(parseExpression());
  else n->kids.push_back(mk(NKind::Empty));
  expectPunct(";");
  if (!isPunct(")")) n->kids.push_back(parseExpression());
  else n->kids.push_back(mk(NKind::Empty));
  expectPunct(")");
  n->kids.push_back(parseStatement());
  return n;
}

NodePtr Parser::parseWhile() {
  idx_++;
  NodePtr n = mk(NKind::While);
  expectPunct("(");
  n->kids.push_back(parseExpression());
  expectPunct(")");
  n->kids.push_back(parseStatement());
  return n;
}

NodePtr Parser::parseDoWhile() {
  idx_++;
  NodePtr n = mk(NKind::DoWhile);
  n->kids.push_back(parseStatement());
  if (!eatKeyword("while")) error("expected while");
  expectPunct("(");
  n->kids.push_back(parseExpression());
  expectPunct(")");
  eatPunct(";");
  return n;
}

NodePtr Parser::parseSwitch() {
  idx_++;
  NodePtr n = mk(NKind::Switch);
  expectPunct("(");
  n->kids.push_back(parseExpression());
  expectPunct(")");
  expectPunct("{");
  while (!isEnd() && !isPunct("}")) {
    NodePtr c = mk(NKind::Block);
    if (eatKeyword("case")) {
      c->str = "case";
      c->kids.push_back(parseExpression());
    } else if (eatKeyword("default")) {
      c->str = "default";
      c->kids.push_back(mk(NKind::Empty));
    } else {
      error("expected case/default");
    }
    expectPunct(":");
    while (!isEnd() && !isPunct("}") && !isKeyword("case") && !isKeyword("default")) {
      c->kids.push_back(parseStatement());
    }
    n->kids.push_back(c);
  }
  expectPunct("}");
  return n;
}

NodePtr Parser::parseTry() {
  idx_++;
  NodePtr n = mk(NKind::Try);
  n->kids.push_back(parseBlock());
  if (eatKeyword("catch")) {
    if (eatPunct("(")) {
      if (cur().type != Tok::Ident) error("expected catch param");
      n->names.push_back(cur().text);
      idx_++;
      expectPunct(")");
    }
    n->kids.push_back(parseBlock());
  } else {
    n->kids.push_back(mk(NKind::Empty));
  }
  if (eatKeyword("finally")) n->kids.push_back(parseBlock());
  else n->kids.push_back(mk(NKind::Empty));
  return n;
}

NodePtr Parser::parseFunction(bool declaration) {
  idx_++;
  NodePtr fn = mk(declaration ? NKind::FuncDecl : NKind::FuncExpr);
  fn->line = cur().line;
  bool isGenerator = eatPunct("*");
  (void)isGenerator;
  if (cur().type == Tok::Ident) {
    fn->str = cur().text;
    idx_++;
  } else if (declaration) {
    error("expected function name");
  }
  return parseFunctionBodyAndParams(fn, !declaration);
}

NodePtr Parser::parseFunctionBodyAndParams(NodePtr fn, bool isExpr) {
  (void)isExpr;
  expectPunct("(");
  if (!isPunct(")")) {
    while (true) {
      if (cur().type != Tok::Ident) error("expected parameter name");
      std::string pname = cur().text;
      idx_++;
      if (isPunct("=")) {
        idx_++;
        fn->props.emplace_back("\x03" "default:" + pname, parseAssignment());
      }
      fn->names.push_back(pname);
      if (eatPunct(",")) continue;
      break;
    }
  }
  expectPunct(")");
  fn->kids.push_back(parseBlock());
  return fn;
}

NodePtr Parser::parseArrowOrParenExpr() {
  size_t save = idx_;
  if (cur().type == Tok::Ident) {
    if (peek().type == Tok::Punct && peek().text == "=>") {
      NodePtr afn = mk(NKind::ArrowFunc);
      afn->names.push_back(cur().text);
      idx_ += 2;
      if (isPunct("{")) {
        afn->kids.push_back(parseBlock());
      } else {
        NodePtr blk = mk(NKind::Block);
        NodePtr ret = mk(NKind::Return);
        ret->kids.push_back(parseAssignment());
        blk->kids.push_back(ret);
        afn->kids.push_back(blk);
      }
      return afn;
    }
    NodePtr id = mk(NKind::Ident);
    id->str = cur().text;
    id->line = cur().line;
    idx_++;
    return id;
  }
  if (false && cur().type == Tok::Ident && peek().type == Tok::Punct && peek().text == "=>") {
    NodePtr fn = mk(NKind::ArrowFunc);
    fn->names.push_back(cur().text);
    idx_ += 2;
    if (isPunct("{")) {
      fn->kids.push_back(parseBlock());
    } else {
      NodePtr blk = mk(NKind::Block);
      NodePtr ret = mk(NKind::Return);
      ret->kids.push_back(parseAssignment());
      blk->kids.push_back(ret);
      fn->kids.push_back(blk);
    }
    return fn;
  }
  if (isPunct("(")) {
    size_t save2 = idx_;
    idx_++;
    std::vector<std::string> params;
    std::vector<NodePtr> pdefaults;
    bool ok = true;
    if (eatPunct(")")) {
      // no params
    } else {
      while (true) {
        if (cur().type != Tok::Ident) { ok = false; break; }
        params.push_back(cur().text);
        pdefaults.push_back(nullptr);
        idx_++;
        if (isPunct("=")) {
          idx_++;
          pdefaults.back() = parseAssignment();
        }
        if (eatPunct(",")) continue;
        if (eatPunct(")")) break;
        ok = false;
        break;
      }
    }
    if (ok && isPunct("=>")) {
      idx_++;
      NodePtr fn = mk(NKind::ArrowFunc);
      fn->names = params;
      for (size_t i = 0; i < params.size(); i++) {
        if (pdefaults[i]) fn->props.emplace_back("\x03" "default:" + params[i], pdefaults[i]);
      }
      if (isPunct("{")) {
        fn->kids.push_back(parseBlock());
      } else {
        NodePtr blk = mk(NKind::Block);
        NodePtr ret = mk(NKind::Return);
        ret->kids.push_back(parseAssignment());
        blk->kids.push_back(ret);
        fn->kids.push_back(blk);
      }
      return fn;
    }
    idx_ = save2;
  }
  (void)save;
  expectPunct("(");
  NodePtr e = parseExpression();
  expectPunct(")");
  return e;
}

NodePtr Parser::parseExpression() {
  NodePtr n = parseAssignment();
  if (isPunct(",")) {
    NodePtr seq = mk(NKind::Seq);
    seq->kids.push_back(n);
    while (eatPunct(",")) seq->kids.push_back(parseAssignment());
    return seq;
  }
  return n;
}

NodePtr Parser::parseAssignment() {
  NodePtr left = parseTernary();
  static const std::set<std::string> assignOps = {"=", "+=", "-=", "*=", "/=", "%=",
                                                  "&=", "|=", "^=", "<<=", ">>=", ">>>="};
  if (cur().type == Tok::Punct && assignOps.count(cur().text)) {
    std::string op = cur().text;
    idx_++;
    NodePtr right = parseAssignment();
    NodePtr n = mk(NKind::Assign);
    n->str = op;
    n->kids.push_back(left);
    n->kids.push_back(right);
    return n;
  }
  return left;
}

NodePtr Parser::parseTernary() {
  NodePtr cond = parseBinary(1);
  if (isPunct("?")) {
    idx_++;
    NodePtr a = parseAssignment();
    expectPunct(":");
    NodePtr b = parseAssignment();
    NodePtr n = mk(NKind::Ternary);
    n->kids.push_back(cond);
    n->kids.push_back(a);
    n->kids.push_back(b);
    return n;
  }
  return cond;
}

NodePtr Parser::parseBinary(int minPrec) {
  NodePtr left = parseUnary();
  while (true) {
    std::string op;
    if (cur().type == Tok::Punct) op = cur().text;
    else if (cur().type == Tok::Keyword && (cur().text == "in" || cur().text == "instanceof")) op = cur().text;
    else break;
    int prec = precedence(op);
    if (prec == 0 || prec < minPrec) break;
    idx_++;
    NodePtr right = parseBinary(prec + 1);
    NodePtr n = mk(op == "&&" || op == "||" ? NKind::Logical : NKind::Binary);
    n->str = op;
    n->kids.push_back(left);
    n->kids.push_back(right);
    left = n;
  }
  return left;
}

NodePtr Parser::parseUnary() {
  if (cur().type == Tok::Punct) {
    std::string op = cur().text;
    if (op == "!" || op == "+" || op == "-" || op == "~") {
      idx_++;
      NodePtr n = mk(NKind::Unary);
      n->str = op;
      n->kids.push_back(parseUnary());
      return n;
    }
    if (op == "++" || op == "--") {
      idx_++;
      NodePtr n = mk(NKind::Update);
      n->str = op;
      n->str2 = "pre";
      n->kids.push_back(parseUnary());
      return n;
    }
  }
  if (cur().type == Tok::Keyword &&
      (cur().text == "typeof" || cur().text == "delete" || cur().text == "void")) {
    NodePtr n = mk(NKind::Unary);
    n->str = cur().text;
    idx_++;
    n->kids.push_back(parseUnary());
    return n;
  }
  return parsePostfix();
}

NodePtr Parser::parsePostfix() {
  NodePtr e = parseCallMember(parsePrimary());
  if (cur().type == Tok::Punct && (cur().text == "++" || cur().text == "--")) {
    NodePtr n = mk(NKind::Update);
    n->str = cur().text;
    n->str2 = "post";
    idx_++;
    n->kids.push_back(e);
    return n;
  }
  return e;
}

NodePtr Parser::parseCallMember(NodePtr obj) {
  while (true) {
    if (isPunct(".")) {
      idx_++;
      if (cur().type != Tok::Ident && cur().type != Tok::Keyword && cur().type != Tok::Number) {
        error("expected property name");
      }
      NodePtr n = mk(NKind::Member);
      n->str = cur().text;
      idx_++;
      n->kids.push_back(obj);
      obj = n;
      continue;
    }
    if (isPunct("[")) {
      idx_++;
      NodePtr key = parseExpression();
      expectPunct("]");
      NodePtr n = mk(NKind::Index);
      n->kids.push_back(obj);
      n->kids.push_back(key);
      obj = n;
      continue;
    }
    if (isPunct("(")) {
      idx_++;
      NodePtr call = mk(NKind::Call);
      call->kids.push_back(obj);
      if (!isPunct(")")) {
        while (true) {
          bool cspread = false;
          if (isPunct("...")) { idx_++; cspread = true; }
          else if (isPunct(".") && peek().text == ".") { idx_ += 2; cspread = true; }
          if (cspread) {
            NodePtr sp = mk(NKind::Spread);
            sp->kids.push_back(parseAssignment());
            call->kids.push_back(sp);
          } else {
            call->kids.push_back(parseAssignment());
          }
          if (eatPunct(",")) continue;
          break;
        }
      }
      expectPunct(")");
      obj = call;
      continue;
    }
    break;
  }
  return obj;
}

NodePtr Parser::parsePrimary() {
  const Token& t = cur();
  if (t.type == Tok::Number) {
    idx_++;
    NodePtr n = mk(NKind::Number);
    n->num = t.num;
    return n;
  }
  if (t.type == Tok::String) {
    idx_++;
    NodePtr n = mk(NKind::String);
    n->str = t.text;
    return n;
  }
  if (t.type == Tok::Template) return parseTemplateLiteral();
  if (t.type == Tok::Regex) {
    idx_++;
    NodePtr n = mk(NKind::Regex);
    n->str = t.text;
    n->str2 = t.flags;
    return n;
  }
  if (t.type == Tok::Bool) {
    idx_++;
    NodePtr n = mk(NKind::Bool);
    n->boolean = t.num != 0;
    return n;
  }
  if (t.type == Tok::Null) { idx_++; return mk(NKind::Null); }
  if (t.type == Tok::Undefined) { idx_++; return mk(NKind::Undefined); }
  if (isKeyword("function")) return parseFunction(false);
  if (isKeyword("new")) {
    idx_++;
    NodePtr callee = parsePrimary();
    while (isPunct(".")) {
      idx_++;
      if (cur().type != Tok::Ident && cur().type != Tok::Keyword && cur().type != Tok::Number) {
        error("expected property name");
      }
      NodePtr m = mk(NKind::Member);
      m->str = cur().text;
      idx_++;
      m->kids.push_back(callee);
      callee = m;
    }
    NodePtr n = mk(NKind::New);
    n->kids.push_back(callee);
    if (isPunct("(")) {
      idx_++;
      if (!isPunct(")")) {
        while (true) {
          n->kids.push_back(parseAssignment());
          if (eatPunct(",")) continue;
          break;
        }
      }
      expectPunct(")");
    }
    return n;
  }
  if (isKeyword("this")) {
    idx_++;
    return mk(NKind::This);
  }
  if (t.type == Tok::Ident) return parseArrowOrParenExpr();
  if (isPunct("(")) return parseArrowOrParenExpr();
  if (isPunct("[")) return parseArrayLiteral();
  if (isPunct("{")) return parseObjectLiteral();
  error(std::string("unexpected token '") + t.text + "'");
}

NodePtr Parser::parseArrayLiteral() {
  expectPunct("[");
  NodePtr arr = mk(NKind::Array);
  while (!isEnd() && !isPunct("]")) {
    if (eatPunct(",")) { arr->kids.push_back(mk(NKind::Undefined)); continue; }
    bool spread = false;
    if (isPunct("...")) { idx_++; spread = true; }
    else if (isPunct(".") && peek().text == ".") { idx_ += 2; spread = true; }
    if (spread) {
      NodePtr sp = mk(NKind::Spread);
      sp->kids.push_back(parseAssignment());
      arr->kids.push_back(sp);
    } else {
      arr->kids.push_back(parseAssignment());
    }
    if (!eatPunct(",")) break;
  }
  expectPunct("]");
  return arr;
}

NodePtr Parser::parseObjectLiteral() {
  expectPunct("{");
  NodePtr obj = mk(NKind::Object);
  while (!isEnd() && !isPunct("}")) {
    std::string key;
    if (cur().type == Tok::Ident) key = cur().text;
    else if (cur().type == Tok::Keyword) key = cur().text;
    else if (cur().type == Tok::String) key = cur().text;
    else if (cur().type == Tok::Number) key = std::to_string(static_cast<long long>(cur().num));
    else if (isPunct("...") || (isPunct(".") && peek().text == ".")) {
      // 展开对象 {...src, k: v}
      if (isPunct("...")) idx_++; else idx_ += 2;
      NodePtr sp = mk(NKind::Spread);
      sp->kids.push_back(parseAssignment());
      obj->props.emplace_back("\x03" "spread", sp);
      if (!eatPunct(",")) break;
      continue;
    }
    else error("expected object key");
    idx_++;
    if (isPunct("(")) {
      // 方法简写：{ onLoad(e) { ... } } —— 等价 key: function(e){...}
      NodePtr fn = mk(NKind::FuncExpr);
      fn->str = key;
      fn->line = cur().line;
      parseFunctionBodyAndParams(fn, false);
      obj->props.emplace_back(key, fn);
    } else if (isPunct(":")) {
      idx_++;
      obj->props.emplace_back(key, parseAssignment());
    } else {
      // 属性简写：{ name, age } —— 值取同名变量
      NodePtr id = mk(NKind::Ident);
      id->str = key;
      id->line = cur().line;
      obj->props.emplace_back(key, id);
    }
    if (!eatPunct(",")) break;
  }
  expectPunct("}");
  return obj;
}

NodePtr Parser::parseTemplateLiteral() {
  std::string raw = cur().text;
  idx_++;
  NodePtr n = mk(NKind::Template);
  std::string lit;
  size_t i = 0;
  auto flushLit = [&]() {
    if (!lit.empty()) {
      NodePtr s = mk(NKind::String);
      s->str = lit;
      n->kids.push_back(s);
      lit.clear();
    }
  };
  while (i < raw.size()) {
    char c = raw[i];
    if (c == '\\' && i + 1 < raw.size()) {
      char e = raw[i + 1];
      lit.push_back(e == 'n' ? '\n' : (e == 't' ? '\t' : e));
      i += 2;
      continue;
    }
    if (c == '$' && i + 1 < raw.size() && raw[i + 1] == '{') {
      flushLit();
      i += 2;
      int depth = 1;
      std::string code;
      while (i < raw.size() && depth > 0) {
        char d = raw[i];
        if (d == '"' || d == '\'' || d == '`') {
          char q = d;
          code.push_back(d);
          i++;
          while (i < raw.size() && raw[i] != q) {
            if (raw[i] == '\\') { code.push_back(raw[i]); i++; }
            if (i < raw.size()) code.push_back(raw[i]);
            i++;
          }
          if (i < raw.size()) { code.push_back(raw[i]); i++; }
          continue;
        }
        if (d == '{') depth++;
        else if (d == '}') {
          depth--;
          if (depth == 0) { i++; break; }
        }
        code.push_back(d);
        i++;
      }
      NodePtr prog = parseSource(code);
      if (prog->kids.size() == 1 && prog->kids[0]->kind == NKind::ExprStmt) {
        n->kids.push_back(prog->kids[0]->kids[0]);
      } else {
        n->kids.push_back(prog);
      }
      continue;
    }
    lit.push_back(c);
    i++;
  }
  flushLit();
  return n;
}

}  // namespace js
}  // namespace mini
