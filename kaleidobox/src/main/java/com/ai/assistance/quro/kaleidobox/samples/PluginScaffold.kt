package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.util.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * 给 AI「写插件」用的脚手架生成器。
 *
 * AI 用 kaleido 工具 action=write 时，要么直接给完整 Java 源码 + 清单，要么只给源码与少量字段、
 * 由这里生成标准清单。本对象提供：
 *  - [JAVA_TEMPLATE]：一份可直接编译的 Kotlin/Java 工具包骨架（实现 [com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit]），
 *    含 greet + 一个示例 unit，AI 在此基础上填充逻辑；
 *  - [buildManifest]：从 id / 名称 / 入口类 / unit 列表生成合法 kaleido.json。
 *
 * 这样 AI「写插件」不必记忆清单格式，也能产出能在设备内 ecj→d8 编译、DexClassLoader 加载的包。
 */
object PluginScaffold {

    /**
     * 标准 Java 工具包骨架。`<ClassName>` 需替换为 manifest 里 runtime.entry 的末段；
     * 包名随意，但必须和 className（入口类全名）一致。仓库根包建议 `com.ai.assistance.quro.kaleidobox.gen`。
     */
    val JAVA_TEMPLATE = """
    package com.ai.assistance.quro.kaleidobox.gen;

    import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext;
    import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit;
    import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost;
    import com.ai.assistance.quro.kaleidobox.core.model.KValue;

    public class <ClassName> implements KaleidoToolkit {
        private ToolkitHost host;
        public void attach(ToolkitHost host) { this.host = host; }

        public KValue invoke(String fn, KValue args, InvokeContext ctx) {
            if ("greet".equals(fn)) {
                return KValue.Str("你好，我是 KaleidoBox 工具包：<简述你的工具>");
            }
            if ("run".equals(fn)) {
                // 在这里写你的逻辑：args.get("x").asString() / .asLongOr(0) / .asBoolOr(false)
                String input = args.get("input").asString();
                return KValue.Str("收到：" + input);
            }
            return KValue.fail("E_NO_FN", "未知函数: " + fn);
        }
    }
    """.trimIndent()

    /**
     * 由少量字段生成标准 kaleido.json。
     * @param units 每个 unit 的 (名字, 描述)；会自动加上 greet。
     */
    fun buildManifest(
        id: String,
        nameZh: String,
        nameEn: String,
        descZh: String,
        entryClass: String,
        units: List<Pair<String, String>> = emptyList(),
        capabilities: List<String> = emptyList(),
    ): String {
        val manifest = JSONObject().apply {
            put("schema", 1)
            put("id", id.ifBlank { "dev.kaleidobox.gen.plugin" })
            put("version", "1.0.0")
            put("name", JSONObject().put("zh", nameZh.ifBlank { id }).put("en", nameEn.ifBlank { nameZh }))
            put("description", JSONObject().put("zh", descZh).put("en", descZh))
            put("authors", JSONArray().put("ZorvAI"))
            put("keywords", JSONArray().put("ai").put("gen"))
            put(
                "runtime", JSONArray().put(
                    JSONObject()
                        .put("id", "main")
                        .put("lang", "java")
                        .put("engine", "jvm_dex")
                        .put("entry", entryClass)
                )
            )
            val unitArr = JSONArray()
            unitArr.put(
                JSONObject().put("name", "greet").put("runtime", "main").put("target", "main:greet")
                    .put("title", JSONObject().put("zh", "打招呼")).put("description", "返回工具说明")
            )
            units.forEach { (n, d) ->
                unitArr.put(
                    JSONObject().put("name", n).put("runtime", "main").put("target", "main:$n")
                        .put("title", JSONObject().put("zh", n)).put("description", d)
                        .put("params", JSONObject("{\"type\":\"object\",\"properties\":{}}"))
                )
            }
            put("units", unitArr)
            put("capabilities", JSONArray(capabilities))
            put(
                "sandbox", JSONObject()
                    .put("level", "in_process").put("memoryMb", 32).put("netEgress", "deny")
            )
        }
        return manifest.toString()
    }

    /** 把 `<ClassName>` 占位符替换成真实类名（清单 entry 的末段）。 */
    fun fillTemplate(className: String): String = JAVA_TEMPLATE.replace("<ClassName>", className)
}
