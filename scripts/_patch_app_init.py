# -*- coding: utf-8 -*-
"""回滚后重新注入：Application 初始化 i18n 运行时。幂等。"""
import io, sys

p = 'app/src/main/java/com/ai/assistance/quro/activity/QuroApplication.kt'
s = io.open(p, encoding='utf-8').read()
if 'QuroI18nRt.appContext' in s:
    print('already init')
    sys.exit(0)
a = '        super.onCreate()\n'
assert a in s, 'anchor miss'
s = s.replace(a, a +
              '\n        // 非 @Composable 位置的取串运行时（工具类 / 回调 / 协程里的界面文案）依赖它。\n'
              '        com.ai.assistance.quro.util.QuroI18nRt.appContext = applicationContext\n', 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('init ok')
