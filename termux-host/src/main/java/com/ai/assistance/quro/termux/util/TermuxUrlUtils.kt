package com.ai.assistance.quro.termux.util

import java.util.LinkedHashSet
import java.util.regex.Pattern

/**
 * URL 提取工具（移植自 Termux TermuxUrlUtils，Apache-2.0）。
 */
object TermuxUrlUtils {
    @Volatile
    private var urlMatchRegex: Pattern? = null

    @JvmStatic
    fun getUrlMatchRegex(): Pattern {
        urlMatchRegex?.let { return it }

        val regex = StringBuilder()
        regex.append("(")
        regex.append("(?:")
        regex.append("dav|dict|dns|file|finger|ftp(?:s?)|git|gemini|gopher|http(?:s?)|imap(?:s?)|irc(?:[6s]?)|ip[fn]s|")
        regex.append("ldap(?:s?)|pop3(?:s?)|redis(?:s?)|rsync|rtsp(?:[su]?)|sftp|smb(?:s?)|smtp(?:s?)|svn(?:(?:\\+ssh)?)|")
        regex.append("tcp|telnet|tftp|udp|vnc|ws(?:s?)")
        regex.append(")://")
        regex.append(")")
        regex.append("(")
        regex.append("(?:\\S+(?::\\S*)?@)?")
        regex.append("(?:")
        regex.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|")
        regex.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*){1,}[a-z\\u00a1-\\uffff0-9]{1,}))?|")
        regex.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)")
        regex.append(")")
        regex.append("(?::\\d{1,5})?")
        regex.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?")
        regex.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?")
        regex.append(")")

        return Pattern.compile(
            regex.toString(),
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE or Pattern.DOTALL
        ).also { urlMatchRegex = it }
    }

    @JvmStatic
    fun extractUrls(text: String?): LinkedHashSet<CharSequence> {
        val urlSet = LinkedHashSet<CharSequence>()
        if (text.isNullOrEmpty()) return urlSet
        val matcher = getUrlMatchRegex().matcher(text)
        while (matcher.find()) {
            val matchStart = matcher.start(1)
            val matchEnd = matcher.end()
            val url = text.substring(matchStart, matchEnd)
            urlSet.add(url)
        }
        return urlSet
    }
}