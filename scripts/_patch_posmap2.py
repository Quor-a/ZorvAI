# -*- coding: utf-8 -*-
"""补丁：修正 _PosMap —— delta 累加必须与记录顺序无关。"""
import io

p = 'scripts/i18n_build.py'
s = io.open(p, encoding='utf-8').read()

old = '''    def __init__(self):
        self.marks = []     # [(new_pos, cumulative_delta)]，按 new_pos 升序
        self.delta = 0

    def reset(self):
        self.marks = []
        self.delta = 0

    def add(self, new_pos, old_len, new_len):
        d = new_len - old_len
        if d == 0:
            return
        self.delta += d
        self.marks.append((new_pos, self.delta))

    def to_src(self, pos):
        lo, hi = 0, len(self.marks)
        while lo < hi:
            mid = (lo + hi) // 2
            if self.marks[mid][0] <= pos:
                lo = mid + 1
            else:
                hi = mid
        d = self.marks[lo - 1][1] if lo > 0 else 0
        return pos - d'''

assert old in s, 'PosMap anchor'

new = '''    def __init__(self):
        self.marks = []          # [(pos, delta)]，delta = 该处替换造成的长度变化
        self._sorted = False
        self._keys = []
        self._pref = []

    def reset(self):
        self.marks = []
        self._sorted = False
        self._keys = []
        self._pref = []

    def add(self, new_pos, old_len, new_len):
        d = new_len - old_len
        if d == 0:
            return
        self.marks.append((new_pos, d))
        self._sorted = False

    def _build(self):
        # 关键：不同 re.sub 模式命中的位置并不单调（模式 A 可能命中 2000、模式 B 命中 1000），
        # 所以必须排序后用前缀和 —— 早期版本假定 add() 的位置单调递增并据此二分，
        # 一旦乱序就会把位置映射错几百字符，导致 @Composable 判定整体随机化。
        self.marks.sort(key=lambda x: x[0])
        self._keys = [m[0] for m in self.marks]
        acc = 0
        pref = []
        for _, d in self.marks:
            acc += d
            pref.append(acc)
        self._pref = pref
        self._sorted = True

    def to_src(self, pos):
        if not self.marks:
            return pos
        if not self._sorted:
            self._build()
        # 二分：最后一个 keys <= pos 的下标
        lo, hi = 0, len(self._keys)
        while lo < hi:
            mid = (lo + hi) // 2
            if self._keys[mid] <= pos:
                lo = mid + 1
            else:
                hi = mid
        d = self._pref[lo - 1] if lo > 0 else 0
        return pos - d'''

s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('posmap fixed')
