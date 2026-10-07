# -*- coding: utf-8 -*-
#重写 CardCatalogTool 的 parametersJson：改用 org.json 在运行时构造。
#
# 原写法用四个引号的 raw string 拼 JSON，字面引号靠特殊写法表示，极易多闭一层花括号；
# 且拼错时编译不报、运行不报，只有工具真正下发、上游按 JSON Schema 解析时才炸
# （QuroToolRegistryTest.everyTool_hasParseableParametersJson 抓到过这个）。
# 改用 JSONObject 逐层 put，结构由库保证，不可能拼错。
import io, os, sys

P = "app/src/main/java/com/ai/assistance/quro/core/tools/CardCatalogTool.kt"
src = io.open(P, encoding="utf-8").read()

start = src.find("    override val parametersJson")
if start < 0:
    print("ABORT: 未找到 parametersJson")
    sys.exit(1)
end = src.find("override val readOnly", start)
if end < 0:
    print("ABORT: 未找到 readOnly")
    sys.exit(1)
end = src.rfind("\n", start, end) + 1

Q = chr(34) * 3  # Kotlin raw string 定界符，脚本里拼出来避免自身被提前闭合

NEW = (
'    /**\n'
'     * 参数 schema 用 [JSONObject] 运行时构造，而不是拼字符串。\n'
'     *\n'
'     * 拼字符串版本（四引号 raw string）在这工具里栽过一次：\n'
'     * properties 多闭一层花括号，**编译不报、运行不报**，只有工具真正下发、\n'
'     * 上游按 JSON Schema 解析时才炸。改成由库逐层构造，结构正确性不再靠人眼数括号。\n'
'     */\n'
'    override val parametersJson: String = JSONObject().apply {\n'
'        put("type", "object")\n'
'        put("properties", JSONObject().apply {\n'
'            put("category", JSONObject().apply {\n'
'                put("type", "string")\n'
'                put("description", "类目名，可空：input/data/layout/action/nav/media/flow/decoration/aiwrite")\n'
'            })\n'
'            put("types", JSONObject().apply {\n'
'                put("type", "array")\n'
'                put("items", JSONObject().apply { put("type", "string") })\n'
'                put("description", "type 名列表，可空表示该类目全部")\n'
'            })\n'
'            put("detail", JSONObject().apply {\n'
'                put("type", "boolean")\n'
'                put("description", "true 回完整样例 JSON（默认），false 只回 type 与说明")\n'
'            })\n'
'            put("normalize", JSONObject().apply {\n'
'                put("type", "boolean")\n'
'                put("description", "true 时同时返回 A2UI 别名归一化结果，默认 false")\n'
'            })\n'
'        })\n'
'        put("required", JSONArray())\n'
'    }.toString()\n'
'\n'
)

src = src[:start] + NEW + src[end:]
tmp = P + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(src)
os.replace(tmp, P)
print("OK: parametersJson 已改为 JSONObject 构造")