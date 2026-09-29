# -*- coding: utf-8 -*-
"""审计：Kotlin 三引号原始字符串（\"\"\"…\"\"\"）内部被误塞进 qstr()/stringResource()。

原始字符串内部不做转义，塞进去的取串调用只是**普通文本**——
表现是 shell 命令变成 `echo qstr(R.string.qk_x)`、JSON 参数说明变成字面源码，
这些位置一律要还原成中文字面量。
"""
import glob
import io

NEEDLES = ('qstr(R.string.', 'stringResource(R.string.')


def main():
    total = 0
    for p in glob.glob('app/src/main/java/**/*.kt', recursive=True):
        src = io.open(p, encoding='utf-8').read()
        n = len(src)
        i = 0
        found = []
        while i < n:
            if src[i:i + 3] == '"""':
                j = src.find('"""', i + 3)
                j = n if j < 0 else j
                body = src[i:j]
                for nd in NEEDLES:
                    if nd in body:
                        found.append((src[:i].count('\n') + 1, body[:160].replace('\n', ' ')))
                        break
                i = j + 3
                continue
            i += 1
        if found:
            print('\n%s' % p.replace('app/src/main/java/com/ai/assistance/quro/', ''))
            for ln, snip in found:
                print('   行%d  %s' % (ln, snip))
            total += len(found)
    print('\n合计 %d 处原始字符串内混入取串调用' % total)


if __name__ == '__main__':
    main()
