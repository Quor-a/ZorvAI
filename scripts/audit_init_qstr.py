# -*- coding: utf-8 -*-
"""找出「类加载 / 对象初始化期」就取串的位置。

这些位置在 App 启动最早阶段执行，一旦此刻取串拿不到 Context（或资源缺失），
返回值就是空串——界面上表现为**整条目/标签消失**，用户看到的就是「功能被删了」。
典型：enum 构造参数、顶层 val、object 属性、companion object 属性。
"""
import glob
import io
import re

Q = re.compile(r'(?:qstr|stringResource)\(\s*R\.string\.')
ENUM = re.compile(r'^\s*(?:enum\s+class|sealed\s+class)\s+(\w+)')
TOP = re.compile(r'^(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+)?(?:const\s+)?(?:val|var)\s+(\w+)')
OBJ = re.compile(r'^\s*(?:private\s+|internal\s+|public\s+)?(?:companion\s+)?object\s+(\w+)')


def main():
    hits = []
    for p in glob.glob('app/src/main/java/**/*.kt', recursive=True):
        src = io.open(p, encoding='utf-8').read()
        lines = src.split('\n')
        for i, l in enumerate(lines):
            if not Q.search(l):
                continue
            indent = len(l) - len(l.lstrip())
            # 判断所属上下文：向上找最近的类/对象/枚举声明
            owner, kind, own_indent = None, None, -1
            for j in range(i - 1, -1, -1):
                lj = lines[j]
                if not lj.strip():
                    continue
                ij = len(lj) - len(lj.lstrip())
                if ij >= indent and j != i:
                    continue
                me = ENUM.match(lj)
                mo = OBJ.match(lj)
                if me:
                    owner, kind, own_indent = me.group(1), 'enum', ij
                    break
                if mo:
                    owner, kind, own_indent = mo.group(1), 'object', ij
                    break
                mt = TOP.match(lj)
                if mt:
                    owner, kind, own_indent = mt.group(1), 'val', ij
                    break
                if ij < indent:
                    break
            if kind in ('enum', 'val'):
                hits.append((p, i + 1, kind, owner, l.strip()[:120]))
            elif kind == 'object' and indent <= own_indent + 8:
                hits.append((p, i + 1, kind, owner, l.strip()[:120]))

    print('=== 类加载/对象初始化期取串：%d 处 ===' % len(hits))
    cur_file = None
    for p, ln, kind, owner, txt in hits:
        f = p.replace('app/src/main/java/com/ai/assistance/quro/', '')
        if f != cur_file:
            print('\n%s' % f)
            cur_file = f
        print('   %5d [%s:%s] %s' % (ln, kind, owner, txt))


if __name__ == '__main__':
    main()
