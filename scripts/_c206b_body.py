import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterModel.kt'
s = io.open(p, encoding='utf-8', newline='').read()
o = s

old = '    data class ArtifactProduced(override val taskId: String, val nodeId: String, val personaId: String, val title: String, override val ts: Long = now()) : ClusterEvent'
new = (
'    /**\r\n'
'     * 某个节点产出了成果。\r\n'
'     *\r\n'
'     * @param body **产物正文**（2026-10-08 新增）。\r\n'
'     *   旧事件只带 title，于是对话框投影出来只有「📎 产出：标题」——\r\n'
'     *   集群真跑了 14 轮、用户看到的是十几条没有内容的空气泡。\r\n'
'     *   默认空串以兼容既有构造。\r\n'
'     */\r\n'
'    data class ArtifactProduced(\r\n'
'        override val taskId: String,\r\n'
'        val nodeId: String,\r\n'
'        val personaId: String,\r\n'
'        val title: String,\r\n'
'        val body: String = "",\r\n'
'        override val ts: Long = now(),\r\n'
'    ) : ClusterEvent'
)
assert s.count(old) == 1, 'anchor count=%d' % s.count(old)
s = s.replace(old, new, 1)

t = p + '.tmp'
io.open(t, 'w', encoding='utf-8', newline='').write(s)
os.replace(t, p)
print('OK Model  %d -> %d' % (len(o), len(s)))

# ═══ 产出处补正文（ClusterEngine.kt, LF）══
p2 = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt'
e = io.open(p2, encoding='utf-8').read()
o2 = e
old2 = '        emit(ClusterEvent.ArtifactProduced(t.id, node.id, role.personaId, node.title))'
new2 = ('        // 🔴 带上正文：只有标题的话对话框里就是一条空气泡（用户报「输出不是正常文本内容和产物」）\r\n'
        '        emit(ClusterEvent.ArtifactProduced(t.id, node.id, role.personaId, node.title, out.text))')
assert e.count(old2) == 1, 'engine anchor count=%d' % e.count(old2)
e = e.replace(old2, new2, 1)

t2 = p2 + '.tmp'
io.open(t2, 'w', encoding='utf-8', newline='').write(e)
os.replace(t2, p2)
print('OK Engine %d -> %d' % (len(o2), len(e)))