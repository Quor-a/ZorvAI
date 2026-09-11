package com.zorv.genui.runtime

/**
 * GenUI 运行时注册表 —— AI 可用离线技术栈的【唯一事实来源】。
 *
 * 与 GenUI 源码 RuntimeRegistry 对齐：提示词里的"技术形态"段落由这里生成，
 * assets/runtimes/ 下真实存在的文件也由这里声明，两处不会脱节。
 * 全部走 /assets/runtimes/（WebViewAssetLoader 提供，域名 <origin>/assets/），不依赖任何外网 CDN。
 */
object GenUiRuntimes {

    data class Runtime(
        val id: String,
        val name: String,
        val files: List<String>,
        val usage: String,
        val whenToUse: String
    )

    private const val BASE = "/assets/runtimes/"

    val all: List<Runtime> = listOf(
        Runtime(
            id = "html",
            name = "原生 HTML/CSS/JS",
            files = emptyList(),
            usage = "直接用 HTML + CSS + JS。样式写在 <style>，逻辑写在 body 末尾的 <script>。",
            whenToUse = "展示、表单、单页小工具、内容页。简单任务不要过度工程化。"
        ),
        Runtime(
            id = "vue",
            name = "Vue 3",
            files = listOf("vue.global.prod.js"),
            usage = "<head> 里引入 vue，然后写模板 + createApp({setup(){...}})。全局对象是 window.Vue。",
            whenToUse = "响应式状态、列表联动、双向绑定、多视图切换（tab/步骤条）。"
        ),
        Runtime(
            id = "react",
            name = "React 18 + htm",
            files = listOf("react.production.min.js", "react-dom.production.min.js", "htm.umd.js"),
            usage = "const html = htm.bind(React.createElement); 然后 ReactDOM.createRoot(el).render(html`<\${App}/>`)。" +
                "全局对象是 window.React / window.ReactDOM / window.htm。",
            whenToUse = "组件化架构、复杂状态管理。"
        ),
        Runtime(
            id = "mermaid",
            name = "Mermaid",
            files = listOf("mermaid.min.js"),
            usage = "图表写在 <pre class=\"mermaid\">…</pre>，末尾 mermaid.initialize({startOnLoad:true})。" +
                "可与普通 HTML 混用（文档+图表）。",
            whenToUse = "流程图、时序图、架构图、甘特图、状态图。"
        ),
        Runtime(
            id = "echarts",
            name = "ECharts 5（数据可视化）",
            files = listOf("echarts.min.js"),
            usage = "const chart = echarts.init(document.getElementById('x')); chart.setOption({...})。" +
                "容器必须有明确高度（如 style=\"height:220px\"），否则不显示。" +
                "窗口尺寸变化时调 chart.resize()。",
            whenToUse = "折线/柱状/饼图/雷达/热力/桑基/仪表盘等任何数据可视化。比手写 SVG 更快也更专业。"
        ),
        Runtime(
            id = "gsap",
            name = "GSAP 3（动画引擎）",
            files = listOf("gsap.min.js"),
            usage = "gsap.to(sel, {duration:.6, y:0, opacity:1, ease:'power2.out'})；时间线 gsap.timeline()。" +
                "入场动画推荐 gsap.from(sel,{y:20,opacity:0,stagger:.06})。全局对象 window.gsap。",
            whenToUse = "多元素错峰入场、序列动画、数值滚动、SVG 路径动画、精细缓动曲线。"
        ),
        Runtime(
            id = "anime",
            name = "Anime.js（轻量动画）",
            files = listOf("anime.min.js"),
            usage = "anime({targets:'.el', translateY:[20,0], opacity:[0,1], duration:600, delay:anime.stagger(60)})。" +
                "全局对象 window.anime。",
            whenToUse = "比 GSAP 更轻的动画需求；简单补间、SVG 形变。"
        ),
        Runtime(
            id = "three",
            name = "Three.js（3D / WebGL）",
            files = listOf("three.min.js"),
            usage = "renderer = new THREE.WebGLRenderer({antialias:true}); scene = new THREE.Scene(); " +
                "camera = new THREE.PerspectiveCamera(60, w/h, .1, 1000)。务必用 requestAnimationFrame 渲染循环。" +
                "移动端性能有限：控制多边形数量。全局对象 window.THREE。",
            whenToUse = "3D 展示、粒子效果、空间可视化。谨慎使用——耗电且性能敏感。"
        ),
        Runtime(
            id = "countup",
            name = "CountUp.js（数字滚动）",
            files = listOf("countup.umd.js"),
            usage = "new countUp.CountUp(el, 2146, {decimalPlaces:0, duration:1.2}).start()。" +
                "全局对象 window.countUp（注意是 countUp.CountUp）。",
            whenToUse = "KPI 大数字入场滚动、统计值变化过渡。"
        )
    )

    val fonts: List<Pair<String, String>> = listOf(
        "Inter" to "fonts/Inter-400.woff2",
        "Inter" to "fonts/Inter-600.woff2",
        "JetBrains Mono" to "fonts/JetBrainsMono-400.woff2",
        "JetBrains Mono" to "fonts/JetBrainsMono-500.woff2",
        "Space Grotesk" to "fonts/SpaceGrotesk-500.woff2",
        "Space Grotesk" to "fonts/SpaceGrotesk-700.woff2"
    )

    fun promptSection(): String = buildString {
        appendLine("在 `<!DOCTYPE html>` 下一行写形态标记注释（如 `<!--gen:echarts-->`）表明你的选择。多个形态可叠加，写多个标记。")
        appendLine()
        all.forEach { r ->
            appendLine("## <!--gen:${r.id}--> ${r.name}")
            appendLine("   何时用：${r.whenToUse}")
            if (r.files.isNotEmpty()) {
                append("   <head> 内引入：")
                appendLine(r.files.joinToString(" ") { "<script src=\"$BASE$it\"></script>" })
            }
            appendLine("   用法：${r.usage}")
            appendLine()
        }
        appendLine("## 离线字体（已由端上预注入 @font-face，直接用 font-family 即可）")
        appendLine("   'Inter'          无衬线正文/数字，中性现代")
        appendLine("   'JetBrains Mono' 等宽，适合代码/数字/标签/技术感文本")
        appendLine("   'Space Grotesk'  几何标题字，适合大标题与品牌感")
        appendLine("   中文请用系统字体：font-family:'Inter',system-ui,sans-serif（不要引用任何中文 webfont）")
    }

    fun fontFaceCss(): String = buildString {
        append("<style>")
        fonts.groupBy({ it.first }, { it.second }).forEach { (family, files) ->
            files.forEach { f ->
                append("@font-face{font-family:'$family';src:url('$BASE$f') format('woff2');")
                append("font-display:swap;}")
            }
        }
        append("</style>")
    }
}
