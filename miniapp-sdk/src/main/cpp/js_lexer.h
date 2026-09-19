#pragma once
#include <string>
#include <vector>

namespace mini {
namespace js {

enum class Tok {
  End, Number, String, Ident, Keyword, Punct, Template, Regex,
  Null, Bool, Undefined
};

struct Token {
  Tok type = Tok::End;
  std::string text;   // raw text / identifier / punctuation / string content
  std::string flags;  // regex flags
  double num = 0;
  int line = 1;
};

class Lexer {
 public:
  explicit Lexer(const std::string& src) : src_(src) {}
  std::vector<Token> tokenize();

 private:
  std::string src_;
  size_t pos_ = 0;
  int line_ = 1;
  bool prevSignificant_ = false;  // for regex literal disambiguation

  char cur() const { return pos_ < src_.size() ? src_[pos_] : '\0'; }
  char peek(size_t n = 1) const { return pos_ + n < src_.size() ? src_[pos_ + n] : '\0'; }
  void skipSpaceAndComments();
  void readNumber(Token& t);
  void readString(Token& t, char quote);
  void readTemplate(Token& t);
  void readRegex(Token& t);
  void readIdent(Token& t);
  bool matchPunct(Token& t);
};

}  // namespace js
}  // namespace mini
