package com.ai.assistance.quro.core.mika.cap.web

/**
 * 交给页面的 CSS 选择器不许按 value 属性选元素:`input[value^="h"]` 一位一位试,猜得出密码框、隐藏字段的值。
 * 只看字面会漏,所以先照 CSS 读选择器的规矩还原成浏览器看到的样子再找:去掉注释,解开转义(`v\61 lue` 就是 value),
 * 引号里的整段拿掉(属性名不写在引号里,引号里的注释记号、`[value` 也不算数);属性名前可以带命名空间(`*|value`、`|value`)。
 * 还原时宁可多认:转义出来的方括号(类名 `.\5b value`)也算作按 value 选。
 * 带 `url(` 的一律算作按 value 选:url( 后面不带引号时到 `)` 为止是一个记号,里面的注释记号、引号既不开始注释也不开始字符串,
 * 上面的还原对不上;`:is()` 只丢掉写错的那一项,后面的 `[value` 照样生效。选择器里本来也用不到它。
 * web_script 的 quro.query 在 Kotlin 里查([selectsByValue]);只读脚本的查找方法在页面里调,用同一套规矩的 JS 版([JS])。
 */
object SelectorPolicy {
    fun selectsByValue(selector: String): Boolean = plain(selector).let { URL_FUNCTION.containsMatchIn(it) || BY_VALUE.containsMatchIn(it) }

    /** `[`、可有可无的命名空间、value,后面不再接名字里的字。只认 CSS 的空白(空格、制表、换行),和 JS 版一致。 */
    private val BY_VALUE = Regex("""\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\x{80}-\x{10FFFF}-])""", RegexOption.IGNORE_CASE)

    /** 还原后出现 `url(`(名字可以是转义拼出来的):CSS 在这里开始一个 url 记号。 */
    private val URL_FUNCTION = Regex("""url\(""", RegexOption.IGNORE_CASE)

    /** 按 CSS 的读法还原:换行先统一成 \n;注释换成一个空格;引号里的整段换成 `""`;转义换成它代表的字。 */
    internal fun plain(selector: String): String {
        val s = selector.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                    out.append(' ')
                }
                c == '"' || c == '\'' -> {
                    i++
                    // 引号里:反斜杠加换行是续行,别的转义照常吃掉;没转义的换行让这段字到此为止(浏览器也这样)
                    while (i < s.length && s[i] != c && s[i] != '\n') {
                        i = when {
                            s[i] != '\\' -> i + 1
                            i + 1 < s.length && s[i + 1] == '\n' -> i + 2
                            else -> escape(s, i).second
                        }
                    }
                    if (i < s.length && s[i] == c) i++
                    out.append("\"\"")
                }
                c == '\\' && (i + 1 >= s.length || s[i + 1] != '\n') -> {
                    val (ch, next) = escape(s, i)
                    out.append(ch)
                    i = next
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** [i] 处是反斜杠:返回转义出的字和下一个位置。1–6 位十六进制(紧跟的一个空白算在转义里),否则就是下一个字本身。 */
    private fun escape(s: String, i: Int): Pair<String, Int> {
        if (i + 1 >= s.length) return "\uFFFD" to s.length
        var j = i + 1
        while (j < s.length && j - i <= 6 && s[j].isHex()) j++
        if (j == i + 1) {
            val cp = s.codePointAt(j)
            return String(Character.toChars(cp)) to j + Character.charCount(cp)
        }
        val n = s.substring(i + 1, j).toInt(16)
        val ch = if (n == 0 || n in 0xD800..0xDFFF || n > 0x10FFFF) "\uFFFD" else String(Character.toChars(n))
        return ch to if (j < s.length && s[j] in " \t\n") j + 1 else j
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** JS 版:在页面里定义 `selectsByValue(选择器字符串)`,和上面一行一行对应。 */
    val JS: String = """
  const selectsByValue = (() => {
    const HEX = /^[0-9a-fA-F]${'$'}/;
    const escape = (s, i) => {
      if (i + 1 >= s.length) return ['\ufffd', s.length];
      let j = i + 1;
      while (j < s.length && j - i <= 6 && HEX.test(s[j])) j++;
      if (j === i + 1) { const cp = s.codePointAt(j); return [String.fromCodePoint(cp), j + (cp > 0xffff ? 2 : 1)]; }
      const n = parseInt(s.slice(i + 1, j), 16);
      const ch = n === 0 || (n >= 0xd800 && n <= 0xdfff) || n > 0x10ffff ? '\ufffd' : String.fromCodePoint(n);
      return [ch, j < s.length && ' \t\n'.includes(s[j]) ? j + 1 : j];
    };
    const plain = (raw) => {
      const s = raw.replace(/\r\n?|\f/g, '\n');
      let out = '', i = 0;
      while (i < s.length) {
        const c = s[i];
        if (c === '/' && s[i + 1] === '*') { const end = s.indexOf('*/', i + 2); i = end < 0 ? s.length : end + 2; out += ' '; }
        else if (c === '"' || c === "'") {
          i++;
          while (i < s.length && s[i] !== c && s[i] !== '\n') i = s[i] !== '\\' ? i + 1 : s[i + 1] === '\n' ? i + 2 : escape(s, i)[1];
          if (s[i] === c) i++;
          out += '""';
        } else if (c === '\\' && s[i + 1] !== '\n') { const [ch, next] = escape(s, i); out += ch; i = next; }
        else { out += c; i++; }
      }
      return out;
    };
    const BY_VALUE = /\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\u0080-\uffff-])/i;
    const URL_FUNCTION = /url\(/i;
    return (s) => { const p = plain(s); return URL_FUNCTION.test(p) || BY_VALUE.test(p); };
  })();
"""
}
