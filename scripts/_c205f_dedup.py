import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

#① firstJson 里的宽松修复改走协议层（唯一实现）
sub('runCatching { return JSONObject(loosenJson(trimmed)) }',
    'runCatching { return JSONObject(ClusterVerdictProtocol.loosenJson(trimmed)) }',
    'firstJson')

#② verdictOf 本体 → 转发（删掉重复实现）
i = src.index('    internal fun verdictOf(')
j = src.index('    /** 供 UI 展示：当前所有角色的预算占用 */')
src = src[:i] + '''    internal fun verdictOf(
        text: String?,
    ): ClusterVerdictProtocol.ClusterVerdict = ClusterVerdictProtocol.verdictOf(text)

''' + src[j:]

#③ 删掉 relaxJson 实现体（loosenJson 及其 4 个私有辅助），全部走协议层
i = src.index('    internal fun loosenJson(raw: String): String {')
j = src.index('    /**\n     * 判定结果的**三态**')
src = src[:i] + src[j:]

#④ 删掉实例侧的旧 enum（协议层已有一份，避免两份定义漂移）
i = src.index('    enum class ClusterVerdict {')
j = src.index('    internal fun verdictOf(')
src = src[:i] + src[j:]

#⑤ 调用点改走协议层
src = src.replace('val v = verdictOf(out.text)', 'val v = ClusterVerdictProtocol.verdictOf(out.text)')
src = src.replace('val v = verdictOf(out?.text)', 'val v = ClusterVerdictProtocol.verdictOf(out?.text)')
src = src.replace('ClusterVerdict.PASS', 'ClusterVerdictProtocol.ClusterVerdict.PASS')
src = src.replace('ClusterVerdict.FAIL', 'ClusterVerdictProtocol.ClusterVerdict.FAIL')
src = src.replace('ClusterVerdict.UNPARSABLE', 'ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE')

assert 'internal fun loosenJson' not in src
assert 'enum class ClusterVerdict {' not in src
assert 'private fun quoteBareKeys' not in src
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  %d -> %d' % (len(orig), len(src)))