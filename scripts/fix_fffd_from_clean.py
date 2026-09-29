# -*- coding: utf-8 -*-
"""修复 QuroChatViewModel.kt 里历史遗留的 U+FFFD 乱码。

该文件在很久以前就被某次编码事故写坏（1460 处 `\\ufffd?`，一个汉字被替换掉），
线上界面里会出现「新对�?」这类乱码。仓库历史里每个版本都带伤，无法从 git 恢复；
但本机 D 盘另有一份**完好副本**（Calw OS 项目里的同名文件，FFFD=0），
用它按「段内定位」把每个丢字补回来。

用法：
  python scripts/fix_fffd_from_clean.py           # 预览
  python scripts/fix_fffd_from_clean.py --apply   # 写入
"""
import io
import sys

TARGET = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
CLEAN = ('D:/Calw OS-project/Calw OS/app/src/main/java/'
         'com/ai/assistance/calw/os/quro/ui/QuroChatViewModel.kt')
BAD = '\ufffd'


def restore(cur_line, clean_lines):
    """用干净副本恢复一行里的所有 `\\ufffd?`。返回 (恢复后行, 替换处数) 或 (None, 原因)。"""
    parts = cur_line.split(BAD + '?')
    if len(parts) < 2:
        return None, 'no-marker'
    # 只要求「足够长」的段作为锚点，太短的段（如 `"` / `,`）不参与筛选
    strong = [p.strip() for p in parts if len(p.strip()) >= 4]
    cands = clean_lines
    if strong:
        cands = [h for h in clean_lines if all(p in h for p in strong)]
    if not cands:
        return None, 'no-candidate(%d strong)' % len(strong)
    if len(cands) > 1:
        # 多候选时用全部段（含短段）再收一次
        c2 = [h for h in cands if all(p in h for p in parts if p)]
        if len(c2) == 1:
            cands = c2
        else:
            return None, 'ambig:%d' % len(cands)
    h = cands[0]
    # 在 h 里按顺序重新拼：段与段之间的字符就是丢失的字
    out, pos, ok = '', 0, True
    for idx, p in enumerate(parts):
        k = h.find(p, pos) if p else pos
        if k < 0:
            ok = False
            break
        if idx > 0:
            out += h[pos:k]  # 被吃掉的字
        out += p
        pos = k + len(p)
    if not ok:
        return None, 'align-fail'
    return out, len(parts) - 1


def main():
    apply = '--apply' in sys.argv
    clean = io.open(CLEAN, encoding='utf-8').read().split('\n')
    src = io.open(TARGET, encoding='utf-8').read()
    lines = src.split('\n')

    fixed, skipped = 0, []
    for i, l in enumerate(lines):
        if BAD not in l:
            continue
        new, why = restore(l, clean)
        if new is None:
            skipped.append((i + 1, why, l.strip()[:90]))
            continue
        if not apply:
            print('%6d %s' % (i + 1, why))
            print('  -  %s' % l.strip()[:110])
            print('  +  %s' % new.strip()[:110])
        lines[i] = new
        fixed += 1

    print('\n%s：修复 %d 行，跳过 %d 行' % ('已写入' if apply else '待修复', fixed, len(skipped)))
    for ln, why, t in skipped[:20]:
        print('   跳过 %6d [%s] %s' % (ln, why, t))

    if apply and fixed:
        rest = '\n'.join(lines)
        io.open(TARGET, 'w', encoding='utf-8', newline='').write(rest)
        print('剩余 U+FFFD：%d' % rest.count(BAD))


if __name__ == '__main__':
    main()
