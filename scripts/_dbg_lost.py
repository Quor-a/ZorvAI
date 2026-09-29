# -*- coding: utf-8 -*-
import sys
sys.path.insert(0, 'scripts')
import audit_lost_strings as A

qmap = A.load_map()
print('qmap size =', len(qmap))
print('qk_03170 =', repr(qmap.get('qk_03170')))
print('qk_03168 =', repr(qmap.get('qk_03168')))

f = 'app/src/main/java/com/ai/assistance/quro/ui/WorkspaceCodeScreen.kt'
h = A.head_text(f)
cur = A.read(f)
hs = A.zh_set(h, qmap)
cs = A.zh_set(cur, qmap)
print('HEAD 条数', len(hs), ' CUR 条数', len(cs))
tgt = A.norm('已保存 ${f.name}')
print('目标 norm =', repr(tgt))
print('在 HEAD 集合?', tgt in hs, ' 在 CUR 集合?', tgt in cs)
print('--- CUR 里含「已保存」的项 ---')
for s in sorted(cs):
    if '已保存' in s:
        print('  ', repr(s))
print('--- REF2 命中数 ---', len(A.REF2.findall(cur)))
miss = [k for k in A.REF2.findall(cur) if k not in qmap]
print('未在 qmap 的 key 数 =', len(miss), miss[:10])
