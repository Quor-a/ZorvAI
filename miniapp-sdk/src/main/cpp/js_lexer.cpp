#include "js_lexer.h"
#include <cctype>
#include <set>

namespace mini {
namespace js {

static const std::set<std::string> kKeywords = {
    "var", "let", "const", "function", "return", "if", "else", "for", "in", "of",
    "while", "do", "break", "continue", "new", "delete", "typeof", "instanceof",
    "void", "try", "catch", "finally", "throw", "switch", "case", "default",
    "true", "false", "null", "undefined", "this", "class", "extends", "super", "debugger"};

static bool isIdentStart(char c) {
  return std::isalpha(static_cast<unsigned char>(c)) || c == '_' || c == '$';
}
static bool isIdentPart(char c) {
  return std::isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '$';
}

void Lexer::skipSpaceAndComments() {
  while (pos_ < src_.size()) {
    char c = src_[pos_];
    if (c == '\n') { line_++; pos_++; continue; }
    if (c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == '\v') { pos_++; continue; }
    if (c == '/' && peek() == '/') {
      while (pos_ < src_.size() && src_[pos_] != '\n') pos_++;
      continue;
    }
    if (c == '/' && peek() == '*') {
      pos_ += 2;
      while (pos_ + 1 < src_.size() && !(src_[pos_] == '*' && src_[pos_ + 1] == '/')) {
        if (src_[pos_] == '\n') line_++;
        pos_++;
      }
      pos_ = std::min(src_.size(), pos_ + 2);
      continue;
    }
    break;
  }
}

void Lexer::readNumber(Token& t) {
  size_t start = pos_;
  bool isHex = false;
  if (cur() == '0' && (peek() == 'x' || peek() == 'X')) { isHex = true; pos_ += 2; }
  bool dotSeen = false, expSeen = false;
  while (pos_ < src_.size()) {
    char c = src_[pos_];
    if (std::isdigit(static_cast<unsigned char>(c))) { pos_++; continue; }
    if (isHex && std::isxdigit(static_cast<unsigned char>(c))) { pos_++; continue; }
    if (c == '.' && !dotSeen && !isHex) { dotSeen = true; pos_++; continue; }
    if ((c == 'e' || c == 'E') && !isHex && !expSeen) {
      expSeen = true; pos_++;
      if (cur() == '+' || cur() == '-') pos_++;
      continue;
    }
    break;
  }
  std::string raw = src_.substr(start, pos_ - start);
  t.type = Tok::Number;
  if (isHex) {
    t.num = static_cast<double>(std::stoll(raw, nullptr, 16));
  } else {
    t.num = std::stod(raw);
  }
  t.text = raw;
}

static void unescapeInto(std::string& out, const std::string& raw) {
  for (size_t i = 0; i < raw.size(); i++) {
    if (raw[i] != '\\' || i + 1 >= raw.size()) { out.push_back(raw[i]); continue; }
    char e = raw[++i];
    switch (e) {
      case 'n': out.push_back('\n'); break;
      case 't': out.push_back('\t'); break;
      case 'r': out.push_back('\r'); break;
      case 'b': out.push_back('\b'); break;
      case 'f': out.push_back('\f'); break;
      case 'v': out.push_back('\v'); break;
      case '0': out.push_back('\0'); break;
      case 'x': {
        if (i + 2 < raw.size()) {
          int v = std::stoi(raw.substr(i + 1, 2), nullptr, 16);
          out.push_back(static_cast<char>(v));
          i += 2;
        }
        break;
      }
      case 'u': {
        if (i + 4 < raw.size()) {
          unsigned short v = static_cast<unsigned short>(std::stoi(raw.substr(i + 1, 4), nullptr, 16));
          if (v < 0x80) {
            out.push_back(static_cast<char>(v));
          } else if (v < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (v >> 6)));
            out.push_back(static_cast<char>(0x80 | (v & 0x3F)));
          } else {
            out.push_back(static_cast<char>(0xE0 | (v >> 12)));
            out.push_back(static_cast<char>(0x80 | ((v >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (v & 0x3F)));
          }
          i += 4;
        }
        break;
      }
      default: out.push_back(e); break;
    }
  }
}

void Lexer::readString(Token& t, char quote) {
  pos_++;  // opening quote
  std::string raw;
  while (pos_ < src_.size() && cur() != quote) {
    if (cur() == '\\') { raw.push_back(cur()); pos_++; if (pos_ < src_.size()) raw.push_back(src_[pos_++]); continue; }
    if (cur() == '\n') break;
    raw.push_back(src_[pos_++]);
  }
  if (pos_ < src_.size() && cur() == quote) pos_++;
  t.type = Tok::String;
  unescapeInto(t.text, raw);
}

void Lexer::readTemplate(Token& t) {
  pos_++;  // skip `
  std::string raw;
  while (pos_ < src_.size() && cur() != '`') {
    if (cur() == '\\' && peek() == '`') { raw.push_back('\\'); raw.push_back('`'); pos_ += 2; continue; }
    raw.push_back(src_[pos_++]);
  }
  if (pos_ < src_.size()) pos_++;
  t.type = Tok::Template;
  t.text = raw;  // kept raw; parser splits ${...}
}

void Lexer::readRegex(Token& t) {
  pos_++;  // skip '/'
  std::string body;
  bool inClass = false;
  while (pos_ < src_.size()) {
    char c = src_[pos_];
    if (c == '\\') { body.push_back(c); pos_++; if (pos_ < src_.size()) body.push_back(src_[pos_++]); continue; }
    if (c == '[') inClass = true;
    if (c == ']') inClass = false;
    if (c == '/' && !inClass) { pos_++; break; }
    if (c == '\n') break;
    body.push_back(c);
    pos_++;
  }
  std::string flags;
  while (pos_ < src_.size() && isIdentPart(src_[pos_])) flags.push_back(src_[pos_++]);
  t.type = Tok::Regex;
  t.text = body;
  t.flags = flags;
}

void Lexer::readIdent(Token& t) {
  size_t start = pos_;
  while (pos_ < src_.size() && isIdentPart(src_[pos_])) pos_++;
  std::string word = src_.substr(start, pos_ - start);
  t.text = word;
  if (kKeywords.count(word)) {
    t.type = Tok::Keyword;
    if (word == "true" || word == "false") { t.type = Tok::Bool; t.num = word == "true" ? 1 : 0; }
    else if (word == "null") t.type = Tok::Null;
    else if (word == "undefined") t.type = Tok::Undefined;
  } else {
    t.type = Tok::Ident;
  }
}

bool Lexer::matchPunct(Token& t) {
  static const char* ops[] = {
      ">>>=", "...", "===", "!==", "<<=", ">>=", ">>>", "**=", "=>",
      "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=",
      "%=", "&=", "|=", "^=", "<<", ">>", "**", "->",
      "{", "}", "(", ")", "[", "]", ";", ",", ".", "?", ":", "+", "-", "*", "/",
      "%", "!", "~", "<", ">", "=", "&", "|", "^"};
  for (const char* op : ops) {
    size_t len = std::string(op).size();
    if (src_.compare(pos_, len, op) == 0) {
      t.type = Tok::Punct;
      t.text = op;
      pos_ += len;
      return true;
    }
  }
  return false;
}

std::vector<Token> Lexer::tokenize() {
  std::vector<Token> out;
  while (true) {
    skipSpaceAndComments();
    if (pos_ >= src_.size()) break;
    Token t;
    t.line = line_;
    char c = cur();
    if (std::isdigit(static_cast<unsigned char>(c)) ||
        (c == '.' && std::isdigit(static_cast<unsigned char>(peek())))) {
      readNumber(t);
    } else if (c == '"' || c == '\'') {
      readString(t, c);
    } else if (c == '`') {
      readTemplate(t);
    } else if (isIdentStart(c)) {
      readIdent(t);
    } else if (c == '/' && !prevSignificant_) {
      readRegex(t);
    } else if (matchPunct(t)) {
      // ok
    } else {
      pos_++;  // skip unknown
      continue;
    }
    t.line = line_;
    prevSignificant_ = !(t.type == Tok::Punct || t.type == Tok::Keyword) ||
                       (t.type == Tok::Punct && (t.text == ")" || t.text == "]" || t.text == "}")) ||
                       (t.type == Tok::Keyword && (t.text == "this" || t.text == "super")) ||
                       (t.type == Tok::Ident) || (t.type == Tok::Number) || (t.type == Tok::String);
    out.push_back(t);
  }
  Token end;
  end.type = Tok::End;
  end.line = line_;
  out.push_back(end);
  return out;
}

}  // namespace js
}  // namespace mini
