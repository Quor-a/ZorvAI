import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterTrace.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

old = '                            trace(e.taskId, "裁决「${if (e.pass) "通过" else "不通过"}」：${e.reason.take(120)}")'
new = "\n".join([
    '                            // 🔴 带上验收方（用户问过「关键是谁验收？」）',
    '                            // 排查时这条最关键：结论是谁给的。',
    '                            trace(',
    '                                e.taskId,',
    '                                "验收「${if (e.pass) "通过" else "不通过"}」" +',
    '                                    " by ${e.verifier.ifBlank { "主持" }}" +',
    '                                    "：${e.reason.take(120)}"',
    '                            )',
])
assert src.count(old) == 1, 'verdict trace anchor count=%d' % src.count(old)
src = src.replace(old, new, 1)

old2 = '                            trace(e.taskId, "${nameOf(e.personaId)} 产出：${e.title}")'
new2 = "\n".join([
    '                            // 🔴 记产物正文字数：旧实现只记标题，于是',
    '                            // 「产物本身是空的」与「产物没被记下来」在 trace 上长得一模一样。',
    '                            trace(',
    '                                e.taskId,',
    '                                "${nameOf(e.personaId)} 产出：${e.title}" +',
    '                                    if (e.body.isNotBlank()) "（${e.body.length} 字）" else "（⚠ 正文为空）"',
    '                            )',
])
assert src.count(old2) == 1, 'artifact trace anchor count=%d' % src.count(old2)
src = src.replace(old2, new2, 1)

assert src != orig
t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(src)
os.replace(t, p)
print('OK  %d -> %d' % (len(orig), len(src)))