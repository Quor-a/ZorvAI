# -*- coding: utf-8 -*-
"""#210-B 验收闭环：执行计划与产物分存 + 驳回意见回喂 + 首次不通过回 EXECUTING 修改。"""
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
    print("  written:", os.path.basename(p), len(data), "bytes")


def sub(text, old, new, name):
    if old not in text:
        print("  !! ANCHOR MISS:", name)
        sys.exit(1)
    if text.count(old) != 1:
        print("  !! ANCHOR NOT UNIQUE (%d):" % text.count(old), name)
        sys.exit(1)
    print("  ok:", name)
    return text.replace(old, new)


# ================================================== ClusterModel.kt (CRLF)
MODEL = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterModel.kt"
t, eol, p = read(MODEL)

OLD = r'''    var lastError: String? = null,
    var artifact: String? = null,
    /**
     * #207：产物**未经有效验收**就被放行了。'''
NEW = r'''    var lastError: String? = null,
    var artifact: String? = null,
    /**
     * #210：ARBITRATING 定下的**执行计划**，与 [artifact] 分开存。
     *
     * 为什么必须分开：EXECUTING 一结束 `artifact` 就被产物覆盖了，
     * 而验收不通过时要拿原计划当底稿去**改**产物。计划没了就只能凭印象重写，
     * 改出来的东西跟上一版毫无关系 ——
     * 「给意见 → 修改 → 再验收」这个闭环根本转不起来。
     */
    var plan: String? = null,
    /**
     * #210：上一轮**被验收驳回**的产物。
     *
     * 重做时它跟着驳回意见一起交回执行角色，让它**改这一版**而不是从零重写。
     * 只有意见没有底稿，角色只能凭意见重新想象一份，等于又回到第一次。
     */
    var lastArtifact: String? = null,
    /**
     * #207：产物**未经有效验收**就被放行了。'''
t = sub(t, OLD, NEW, "ClusterNode 新增 plan / lastArtifact 字段")

OLD = r'''        put("lastError", lastError ?: ""); put("artifact", artifact ?: "")
        put("releaseUnverified", releaseUnverified)'''
NEW = r'''        put("lastError", lastError ?: ""); put("artifact", artifact ?: "")
        put("plan", plan ?: ""); put("lastArtifact", lastArtifact ?: "")
        put("releaseUnverified", releaseUnverified)'''
t = sub(t, OLD, NEW, "toJson 序列化新字段")

OLD = r'''            artifact = o.optString("artifact").takeIf { it.isNotBlank() },
            releaseUnverified = o.optBoolean("releaseUnverified", false)'''
NEW = r'''            artifact = o.optString("artifact").takeIf { it.isNotBlank() },
            plan = o.optString("plan").takeIf { it.isNotBlank() },
            lastArtifact = o.optString("lastArtifact").takeIf { it.isNotBlank() },
            releaseUnverified = o.optBoolean("releaseUnverified", false)'''
t = sub(t, OLD, NEW, "fromJson 反序列化新字段")
write(p, t, eol)

# ================================================== ClusterEngine.kt (LF)
ENG = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt"
t, eol, p = read(ENG)

# ---- 1) intake：注入能力词表 + 词表函数
OLD = r'''    private suspend fun intake(cluster: ClusterConfig, t: ClusterTask) {
        val prompt = """
用户目标：${t.goal}'''
NEW = r'''    /**
     * #210：把技能库里**真实存在**的能力词摊给主持看。
     *
     * 为什么必须给：主持不知道库里有什么，只能凭训练知识自由发挥写标签
     * （实测写出「JavaScript 编程」，而技能词是「前端/网页/html/javascript」），
     * 于是标签一个都撞不上 → CAPABILITY 判「无人具备」→ 节点 SKIPPED。
     * 主持措辞的自由度在这里不是优点，是故障源；给词表是治本。
     */
    private fun capabilityVocabulary(): String = runCatching {
        ClusterSkillStore.load(context)
            .filter { it.enabled }
            .map { s ->
                val words = ClusterSkillStore.effectiveAbilityWords(s).take(4)
                if (words.isEmpty()) s.name else s.name + "：" + words.joinToString(" ")
            }
            .joinToString("\n")
    }.getOrDefault("")

    private suspend fun intake(cluster: ClusterConfig, t: ClusterTask) {
        val vocab = capabilityVocabulary()
        val prompt = """
用户目标：${t.goal}'''
t = sub(t, OLD, NEW, "intake 引入 capabilityVocabulary")

OLD = r'''ability 怎么写：写**手艺名词**，不要写句子。
好：「视觉设计」「数据统计」「文案写作」「代码审查」「设备操作」
坏：「帮我把页面做得好看一点」（这是句子不是能力，匹配不到任何技能）

只输出 JSON，不要任何额外文字：'''
NEW = r'''ability 怎么写：写**手艺名词**，不要写句子。
好：「视觉设计」「数据统计」「文案写作」「代码审查」「设备操作」
坏：「帮我把页面做得好看一点」（这是句子不是能力，匹配不到任何技能）

**优先从下面这份真实存在的能力词里挑**（写库里没有的词 = 匹配不到任何技能 = 该子任务会被判「无人具备」而跳过）：
${if (vocab.isNotBlank()) vocab else "(技能库为空)"}

只输出 JSON，不要任何额外文字：'''
t = sub(t, OLD, NEW, "intake prompt 注入能力词表")

# ---- 2) arbitrating：计划存进 plan
OLD = r'''        val steps = o?.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        node.artifact = steps.joinToString("\n")'''
NEW = r'''        val steps = o?.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        // #210：计划另存一份。EXECUTING 会把 artifact 覆盖成产物，
        // 返工时还要拿计划当底稿，不能只留一份。
        val planText = steps.joinToString("\n")
        node.plan = planText
        node.artifact = planText'''
t = sub(t, OLD, NEW, "arbitrating 存 plan")

# ---- 3) fallbackArbitrate：同样存 plan
OLD = r'''        node.artifact = steps.joinToString("\n")
        node.state = NodeState.EXECUTING
        t.state = ClusterTaskState.EXECUTING
    }

    /** EXECUTING'''
NEW = r'''        val planText = steps.joinToString("\n")
        node.plan = planText
        node.artifact = planText
        node.state = NodeState.EXECUTING
        t.state = ClusterTaskState.EXECUTING
    }

    /** EXECUTING'''
t = sub(t, OLD, NEW, "fallbackArbitrate 存 plan")

# ---- 4) executing：带上驳回意见与上一版产物
OLD = r'''        val prompt = """
请按以下执行计划完成子任务「${node.title}」，直接产出成果内容（不要只说"我会怎么做"）。
执行计划：
${node.artifact.orEmpty()}

子任务说明：${node.instruction}
验收标准：
${t.acceptance.joinToString("\n") { "- $it" }}
""".trimIndent()'''
NEW = r'''        // #210：返工时必须带着「上一版产物 + 驳回意见」。
        // 少了其中任何一个，角色的「修改」都会退化成「重新想象一份」：
        // 只有意见没底稿 → 不知道从哪改；只有底稿没意见 → 不知道差在哪。
        // 两种情况的结果都是产出同质产物 → 再次被驳回 → 空转烧 token。
        val revise = buildString {
            val rejected = node.lastArtifact
            val note = node.lastError
            if (rejected.isNullOrBlank() && note.isNullOrBlank()) return@buildString
            appendLine()
            appendLine("⚠ 这是**返工**：上一版已被验收驳回。请针对驳回意见修改，不要从零重写。")
            if (!note.isNullOrBlank()) appendLine("驳回意见：\n$note")
            if (!rejected.isNullOrBlank()) {
                appendLine("上一版产物（在它基础上改，保留仍然合格的部分）：")
                appendLine(rejected.take(6000))
            }
            appendLine("只输出**修改后的完整产物**，不要输出修改说明。")
        }
        val planText = node.plan?.takeIf { it.isNotBlank() } ?: node.artifact.orEmpty()
        val prompt = """
请按以下执行计划完成子任务「${node.title}」，直接产出成果内容（不要只说"我会怎么做"）。
执行计划：
$planText

子任务说明：${node.instruction}
验收标准：
${t.acceptance.joinToString("\n") { "- $it" }}
$revise""".trimIndent()'''
t = sub(t, OLD, NEW, "executing 提示词带回喂")

# ---- 5) verifying：存被驳回产物 + 首次回 EXECUTING 修改
OLD = r'''            node.lastError = "验收未通过：" + effectiveReason
            node.attempt++'''
NEW = r'''            // #210：先把被驳回的这一版存下来当修改底稿（见 [executing] 的 revise 段）。
            node.lastArtifact = node.artifact
            node.lastError = "验收未通过：" + effectiveReason
            node.attempt++'''
t = sub(t, OLD, NEW, "verifying 保存被驳回产物")

OLD = r'''            if (node.attempt >= cluster.budget.maxNodeAttempts) {
                node.releaseUnverified = true'''
NEW = r'''            // 🔴 #210：第一次不通过 → 回 EXECUTING 让角色**按意见改**，
            // 而不是回 REPLANNING 重走「派单→提方案→裁决→执行」四步。
            //
            // 旧路径每驳回一次就整轮重来，而角色拿到的还是最初那句任务说明，
            // 它根本不知道上一版差在哪，于是产出同质的东西再被驳回 ——
            // 这就是用户实测「没有给出修改意见→角色修改→再验收的闭环」的病灶。
            // 改一次的成本远低于重做一轮，且只有"改"才算得上闭环。
            if (node.attempt == 1) {
                emit(ClusterEvent.Error(
                    t.id, node.assignee,
                    "验收未通过，已把驳回意见与上一版产物交回执行角色修改：" + effectiveReason
                ))
                t.state = ClusterTaskState.EXECUTING
                return
            }
            if (node.attempt >= cluster.budget.maxNodeAttempts) {
                node.releaseUnverified = true'''
t = sub(t, OLD, NEW, "verifying 首次不通过回 EXECUTING")
write(p, t, eol)

print("done")
