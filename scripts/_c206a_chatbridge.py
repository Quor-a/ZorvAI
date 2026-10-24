import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterChatBridge.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ═══ 病灶1：产物只投影标题，正文整段丢弃 ═══
sub(
"""        is ClusterEvent.ArtifactProduced -> listOf(
            msg(
                sender = nameOf(e.personaId),
                content = "📎 产出：${e.title}",
                host = RoleRegistry.isHost(e.personaId),
            )
        )""",
"""        // 🔴 2026-10-08 修「集群在对话框输出的内容不是正常的文本内容和产物」。
        //
        // 旧实现只投影 `"📎 产出：${e.title}"` —— 标题有、**产物正文一个字都没有**。
        // 集群跑了 14 轮真在干活，用户在对话框看到的却是十几条只有标题的空气泡，
        // 于是「跑没跑」完全无法判断。这是「输出不完整」最直接的证据。
        //
        // 现在带上正文：超长则截断（对话框不是产物仓库），并明确告诉用户内容在哪。
        is ClusterEvent.ArtifactProduced -> listOf(
            msg(
                sender = nameOf(e.personaId),
                content = buildString {
                    append("📎 产出：").append(e.title)
                    val body = e.body.trim()
                    if (body.isNotEmpty()) {
                        append("\\n\\n")
                        if (body.length > BODY_PREVIEW) {
                            append(body.take(BODY_PREVIEW))
                            append("\\n… （全文 ").append(body.length).append(" 字，已截断）")
                        } else {
                            append(body)
                        }
                    }
                },
                host = RoleRegistry.isHost(e.personaId),
            )
        )""",
'artifact')

# ═══ 病灶2：裁决气泡不显示「谁验收的」 ═══
sub(
"""        is ClusterEvent.Verdict -> listOf(
            msg(
                sender = "主持",
                content = buildString {
                    append(if (e.pass) "✅ 裁决通过" else "❌ 裁决不通过")
                    if (e.reason.isNotBlank()) append("：").append(e.reason)
                },
                host = true,
            )
        )""",
"""        // 🔴 2026-10-08 补验收方身份。用户直接问过「关键是谁验收？」——
        // 旧气泡只有 ✅/❌ 加一句理由，看不出是评审角色判的还是主持代验的。
        // 验收方不同结论的可信度完全不同，必须让人看得见。
        is ClusterEvent.Verdict -> listOf(
            msg(
                sender = e.verifier.ifBlank { "主持" },
                content = buildString {
                    append(if (e.pass) "✅ 验收通过" else "❌ 验收未通过")
                    if (e.verifier.isNotBlank()) append("（验收方：").append(e.verifier).append("）")
                    if (e.reason.isNotBlank()) append("：").append(e.reason)
                },
                host = true,
            )
        )""",
'verdict')

# ═══ 常量 ═══
sub(
"""    private const val TOOL_MERGE_WINDOW_MS = 3000L""",
"""    private const val TOOL_MERGE_WINDOW_MS = 3000L

    /**
     * 产物正文在气泡里最多展示多少字。
     *
     * 🔴 为什么不全文贴：产物动辄上万字（集群跑 14 轮就是），全贴会把对话框撑爆，
     * 反而又是「输出不完整」——用户要滚动十几屏才看到结论。截断 + 标注原长度，
     * 既看得到内容、也看得出「后面还有」。
     */
    private const val BODY_PREVIEW = 1200""",
'const')

assert src != orig
t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(src)
os.replace(t, p)
print('OK  %d -> %d' % (len(orig), len(src)))