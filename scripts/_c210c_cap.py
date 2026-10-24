# -*- coding: utf-8 -*-
"""#210-A 能力匹配：中英桥梁 + 黑户技能自动兜底 + frontend-dev frontmatter 补全。"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join


def read(rel):
    p = J(ROOT, rel.replace("/", os.sep))
    raw = io.open(p, "rb").read()
    crlf = raw.count(b"\r\n")
    lf = raw.count(b"\n") - crlf
    eol = "\r\n" if crlf and crlf >= lf else "\n"
    return io.open(p, "r", encoding="utf-8", newline="").read().replace("\r\n", "\n"), eol, p


def write(p, text, eol):
    data = text.replace("\n", eol).encode("utf-8")
    tmp = p + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, p)
    print("  written:", os.path.basename(p), len(data), "bytes, eol=" + repr(eol))


def sub(text, old, new, name):
    if old not in text:
        print("  !! ANCHOR MISS:", name)
        sys.exit(1)
    if text.count(old) != 1:
        print("  !! ANCHOR NOT UNIQUE (%d):" % text.count(old), name)
        sys.exit(1)
    print("  ok:", name)
    return text.replace(old, new)


# ---------------------------------------------------------------- 1. 中英桥梁
CAP = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterCapability.kt"
t, eol, p = read(CAP)

OLD = '        setOf("前端", "网页", "页面", "界面"),'
NEW = (
    '        // #210 中英混排：技能名/描述多是英文（frontend-dev / html / css），\n'
    '        // 主持拆的能力标签是中文或中英混写（「JavaScript 编程」）。\n'
    '        // 同一组里必须**同时放中英两套说法**，否则 contains 跨语言恒 false ——\n'
    '        // 这就是 #197「中英断层」的真身：不是匹配算法错，是根本没有桥。\n'
    '        setOf("前端", "网页", "页面", "界面", "html", "css", "javascript",\n'
    '            "js", "frontend", "组件", "布局", "落地页"),\n'
    '        setOf("编程", "编码", "开发", "写代码", "脚本", "程序",\n'
    '            "code", "coding", "development", "dev", "program", "script"),'
)
t = sub(t, OLD, NEW, "SYNONYM_GROUPS 前端组 -> 中英混排 + 新增编程组")

# 其余组补英文说法（让中文能力词能撞上纯英文技能）
PAIRS = [
    ('setOf("研究", "调研", "调查", "查证"),', 'setOf("研究", "调研", "调查", "查证", "research"),'),
    ('setOf("搜索", "检索", "查询", "查找"),', 'setOf("搜索", "检索", "查询", "查找", "search", "web"),'),
    ('setOf("写作", "撰写", "撰稿", "起草"),', 'setOf("写作", "撰写", "撰稿", "起草", "writing"),'),
    ('setOf("设计", "策划", "构思"),', 'setOf("设计", "策划", "构思", "design"),'),
    ('setOf("审查", "评审", "验收", "核对"),', 'setOf("审查", "评审", "验收", "核对", "review", "critic"),'),
    ('setOf("测试", "验证", "自查", "校验"),', 'setOf("测试", "验证", "自查", "校验", "test", "verify"),'),
    ('setOf("优化", "改进", "提速"),', 'setOf("优化", "改进", "提速", "optimize", "performance"),'),
    ('setOf("输出", "产出", "交付"),', 'setOf("输出", "产出", "交付", "output", "deliver"),'),
]
for old, new in PAIRS:
    t = sub(t, old, new, "SYNONYM " + old[:22])

# 新增几个高频域
OLD_TAIL = '        setOf("目标", "指标", "目的"),\n    )'
NEW_TAIL = (
    '        setOf("目标", "指标", "目的"),\n'
    '        setOf("文档", "说明书", "markdown", "doc", "docs"),\n'
    '        setOf("部署", "上线", "发布", "deploy", "publish", "ship"),\n'
    '        setOf("数据", "统计", "报表", "分析", "data", "analysis"),\n'
    '        setOf("新闻", "资讯", "热点", "news"),\n'
    '    )'
)
t = sub(t, OLD_TAIL, NEW_TAIL, "SYNONYM_GROUPS 追加文档/部署/数据/新闻组")
write(p, t, eol)

# ------------------------------------------------- 2. 黑户技能自动切词兜底
SS = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterSkillStore.kt"
t, eol, p = read(SS)

OLD = (
    '    fun effectiveAbilityWords(skill: ClusterSkill): List<String> {\n'
    '        val fromField = skill.abilityWords.split(" ").map { it.trim() }.filter { it.isNotBlank() }\n'
    '        if (fromField.isNotEmpty()) return fromField.map { it.lowercase() }\n'
    '        return NAME_FALLBACK_WORDS[skill.name.lowercase()]\n'
    '            ?.split(" ")?.filter { it.isNotBlank() }?.map { it.lowercase() }\n'
    '            ?: emptyList()\n'
    '    }\n'
)
NEW = (
    '    fun effectiveAbilityWords(skill: ClusterSkill): List<String> {\n'
    '        val fromField = skill.abilityWords.split(" ").map { it.trim() }.filter { it.isNotBlank() }\n'
    '        if (fromField.isNotEmpty()) return fromField.map { it.lowercase() }\n'
    '        NAME_FALLBACK_WORDS[skill.name.lowercase()]\n'
    '            ?.split(" ")?.filter { it.isNotBlank() }?.map { it.lowercase() }\n'
    '            ?.takeIf { it.isNotEmpty() }\n'
    '            ?.let { return it }\n'
    '        // #210：手写表也没覆盖 → 自动切词，绝不返回空（见 [heuristicWords]）。\n'
    '        return heuristicWords(skill)\n'
    '    }\n'
    '\n'
    '    /** 切词时丢掉的英文虚词与噪音（中英混排的 description 里很多）。 */\n'
    '    private val HEURISTIC_STOP = setOf(\n'
    '        "the", "and", "for", "with", "use", "when", "your", "you", "are", "this",\n'
    '        "that", "from", "com", "www", "http", "https", "skill", "skills", "based",\n'
    '        "into", "how", "what", "all", "can", "via", "not", "any", "its", "them",\n'
    '    )\n'
    '\n'
    '    /**\n'
    '     * #210：从技能名/描述**自动切词**的最后一道兜底。\n'
    '     *\n'
    '     * 为什么必须有它：随包 81 个技能里只有 [NAME_FALLBACK_WORDS] 那 20 个手写了\n'
    '     * 中文能力词。其余（frontend-dev / Docker / tavily / …）一旦没填 abilityWords，\n'
    '     * [effectiveAbilityWords] 就返回**空列表** —— 于是这个技能对任何能力都得 0 分，\n'
    '     * 绑着它的角色在 CAPABILITY 阶段一律被判「不具备能力」→ 节点 SKIPPED。\n'
    '     *\n'
    '     * 用户实测「JavaScript 编程匹配不上已绑定的 frontend-dev」就是这么来的：\n'
    '     * 不是打分公式错，是**根本没有可供匹配的词**。\n'
    '     * 空列表等于「这个技能等于不存在」，比给个大概坏得多。\n'
    '     */\n'
    '    private fun heuristicWords(skill: ClusterSkill): List<String> {\n'
    '        fun cut(s: String): List<String> =\n'
    '            Regex("[^a-z0-9\\u4e00-\\u9fff]+")\n'
    '                .split(s.lowercase())\n'
    '                .filter { w ->\n'
    '                    w.isNotBlank() && w !in HEURISTIC_STOP &&\n'
    '                        // 中文片段全收；英文要 >=3 字符（2 字符的 ui/if 太容易误命中）\n'
    '                        (w.all { it.code in 0x4E00..0x9FFF } || w.length >= 3)\n'
    '                }\n'
    '        // 技能名优先（它最能代表这个技能是什么），描述只作补充。\n'
    '        return (cut(skill.name) + cut(skill.description + " " + skill.trigger))\n'
    '            .distinct()\n'
    '            .take(24)\n'
    '    }\n'
)
t = sub(t, OLD, NEW, "effectiveAbilityWords 自动兜底 + heuristicWords")
write(p, t, eol)

# ------------------------------------------- 3. frontend-dev frontmatter 补全
MD = "app/src/main/assets/skills/zorv/frontend-dev.md"
t, eol, p = read(MD)
OLD = '---\nname: frontend-dev\ndescription: |\n---\n'
NEW = (
    '---\n'
    'name: frontend-dev\n'
    'description: Full-stack frontend development combining premium UI design, cinematic animations, and production-ready code. Use when building or improving web/mobile frontends, components, pages, or apps with high design quality.\n'
    'trigger: /frontend-dev\n'
    '# #210：这里原本是空 description（连 manifest 里的描述都没带进来），\n'
    '# 加上技能名是英文、正文也是英文 —— 中文能力标签（「JavaScript 编程」）\n'
    '# 一个都撞不上，角色被判「不具备能力」→ 节点直接 SKIPPED。\n'
    '# abilityWords 就是给能力核对用的显式词表，中英文都要写。\n'
    'abilityWords: 前端 网页 页面 界面 html css javascript js 编程 编码 实现界面 前端开发 组件 交互 动画 落地页\n'
    '---\n'
)
t = sub(t, OLD, NEW, "frontend-dev.md frontmatter")
write(p, t, eol)

print("done")
