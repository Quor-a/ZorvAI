import io, os

# ═══ClusterModel.kt（CRLF）：给 Verdict 补验收方字段 ═══
p1 = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterModel.kt'
s1 = io.open(p1, encoding='utf-8', newline='').read()
o1 = s1

old = '    data class Verdict(override val taskId: String, val nodeId: String, val pass: Boolean, val reason: String, override val ts: Long = now()) : ClusterEvent'
new = (
'    /**\r\n'
'     * 裁决 / 验收结论。\r\n'
'     *\r\n'
'     * @param verifier **谁给出的结论**（2026-10-08 新增）。\r\n'
'     *   用户直接问过「关键是谁验收？」——旧事件里只有 pass + reason，\r\n'
'     *   看到「验收未通过」根本不知道是评审角色判的、还是主持代验的。\r\n'
'     *   默认空串以兼容既有序列化（trace/UI 都按可空读）。\r\n'
'     */\r\n'
'    data class Verdict(\r\n'
'        override val taskId: String,\r\n'
'        val nodeId: String,\r\n'
'        val pass: Boolean,\r\n'
'        val reason: String,\r\n'
'        val verifier: String = "",\r\n'
'        override val ts: Long = now(),\r\n'
'    ) : ClusterEvent'
)
assert s1.count(old) == 1, 'verdict anchor count=%d' % s1.count(old)
s1 = s1.replace(old, new, 1)

t = p1 + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(s1)
os.replace(t, p1)
print('OK Model  %d -> %d' % (len(o1), len(s1)))