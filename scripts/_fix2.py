import io, os

p = 'app/src/main/java/com/ai/assistance/quro/ui/QuroChatViewModel.kt'
s = io.open(p, encoding='utf-8', errors='replace').read()

BAD = 'card_catalog`。"' + chr(34) + '\n        )'
GOOD = 'card_catalog`。' + chr(92) + 'n' + chr(34) + '\n        )'
assert s.count(BAD) == 1, s.count(BAD)
s = s.replace(BAD, GOOD)

# CardSdk 的 import：QuroChatViewModel 在 ui 包，需要显式引 core.cards
imp = 'import com.ai.assistance.quro.core.cards.CardSdk'
if imp not in s:
    anchor = 'package com.ai.assistance.quro.ui\n'
    assert s.count(anchor) == 1
    s = s.replace(anchor, anchor + '\n' + imp + '\n', 1)

io.open(p + '.x', 'w', encoding='utf-8', newline='\n').write(s)
os.replace(p + '.x', p)
print('OK', len(s))