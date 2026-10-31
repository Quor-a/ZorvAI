package com.ai.assistance.quro.core.mika.mcp

import com.ai.assistance.quro.core.mika.remote.RemoteAccess
import kotlinx.serialization.json.Json
import com.ai.assistance.quro.core.mika.remote.RemoteTaskApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 经 USB 的电脑连接:电脑用 adb 打开、只在本机回环上监听,电脑经 `adb forward` 连进来。
 * 这里是和电脑之间的约定:连接信息文件的内容、电脑上跑的脚本。都只用安卓原版 sh 和 toybox 里有的命令。
 */
object McpUsb {
    /** 连接信息文件名,放在 App 的外部文件目录里:安卓 11 起别的 App 读不到,电脑经 USB 调试以 shell 身份读。 */
    const val INFO_FILE = "mcp-usb.json"

    /** 电脑上跑的脚本文件名,和「用电脑启动」的脚本放在一起。 */
    const val SCRIPT = "connect-computer.sh"

    private val pretty = Json { prettyPrint = true }

    /**
     * 连接信息:电脑上的 MCP 客户端要填的地址和请求头,加上要先跑的端口转发。
     * [boot] 是这次开机的编号(和 shell 里 `settings get global boot_count` 读到的一样),脚本据此认出不是上次开机留下的旧文件。
     */
    fun info(port: Int, token: String, boot: String): String = pretty.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("api_version", 1)
            put("api_url", "http://127.0.0.1:$port${RemoteTaskApi.ROOT}")
            put("transport", "streamable-http")
            put("url", "http://127.0.0.1:$port${McpRpc.PATH}")
            putJsonObject("headers") { put("Authorization", "Bearer $token") }
            put("token", token)
            put("port", port)
            put("forward", forward(port))
            put("boot", boot)
        },
    ) + "\n"

    /** 电脑上把本机端口转到手机回环端口的命令;拔插数据线后要再跑一次。 */
    fun forward(port: Int) = "adb forward tcp:$port tcp:$port"

    /**
     * 电脑经 USB 调试跑的脚本(`adb shell sh <scriptPath>`,关掉时末尾加 `off`)。不带任何口令或令牌,可以一直放着。
     * 打开:先跑一次「用电脑启动」的脚本 [helperScript](手机助手不在就起,在就说一句),
     * 再以 shell 身份起 ZorvAI 的前台服务 [component]、动作 [onAction],等到这次开机的连接信息文件 [infoPath] 写好,把它打出来。
     * 关掉:起同一服务、动作 [offAction],等连接信息文件被删掉(令牌随之作废)。
     * 不是 shell 身份时不跑:服务只收持有 shell 级权限的调用方,普通 App 起不了它。
     */
    fun script(scriptPath: String, helperScript: String, infoPath: String, component: String, onAction: String, offAction: String, port: Int): String {
        val again = "adb shell sh $scriptPath"
        return """
            |#!/system/bin/sh
            |# Written by ZorvAI. Lets AI tools on a computer use ZorvAI over USB: $again   (turn off: $again off)
            |if [ "${'$'}(id -u)" != "2000" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |info=${quote(infoPath)}
            |if [ "${'$'}1" = "off" ]; then
            |  out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(offAction)} 2>&1)
            |  case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; exit 1 ;; esac
            |  i=0
            |  while [ -f "${'$'}info" ]; do
            |    i=${'$'}((i + 1))
            |    if [ ${'$'}i -gt 10 ]; then echo "ZorvAI did not confirm. Open ZorvAI > Settings > Computer access (MCP) and turn off USB there."; exit 1; fi
            |    sleep 1
            |  done
            |  echo "Computer access over USB is off. The old token no longer works."
            |  exit 0
            |fi
            |if [ -f ${quote(helperScript)} ]; then
            |  sh ${quote(helperScript)} || echo "Phone control is not ready yet; tasks that operate apps wait until it is."
            |fi
            |boot=${'$'}(settings get global boot_count 2>/dev/null)
            |out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(onAction)} 2>&1)
            |case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; echo "Is ZorvAI installed and up to date?"; exit 1 ;; esac
            |i=0
            |until grep -q "\"boot\": *\"${'$'}boot\"" "${'$'}info" 2>/dev/null; do
            |  i=${'$'}((i + 1))
            |  if [ ${'$'}i -gt 15 ]; then echo "ZorvAI did not open the connection. Open ZorvAI > Settings > Computer access (MCP) to see why, then run this again."; exit 1; fi
            |  sleep 1
            |done
            |echo "Computer access over USB is on. On the computer, run: ${forward(port)}"
            |echo "Then add this MCP server (Streamable HTTP) to your AI tool:"
            |cat "${'$'}info"
            |""".trimMargin()
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
