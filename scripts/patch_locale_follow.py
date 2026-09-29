# -*- coding: utf-8 -*-
"""让 qstr() 跟随应用内语言 + 早期初始化 + 修语言名。

必须在 i18n_build.py 之后跑（QuroLocale.kt / QuroApplication.kt 会被转换器改写）。
"""
import io
import re

# ---------- 1) QuroLocale.apply() 同步 QuroI18nRt 的语言 ----------
p = 'app/src/main/java/com/ai/assistance/quro/util/QuroLocale.kt'
s = io.open(p, encoding='utf-8').read()

if 'QuroI18nRt.setLocale' not in s:
    old = '        AppCompatDelegate.setApplicationLocales(locales)'
    assert old in s, 'apply() anchor'
    s = s.replace(old, old + """
        // qstr()（非 @Composable 位置取串）走的是 ApplicationContext，
        // 而 AppCompatDelegate.setApplicationLocales 在 Android 12 及以下只更新 Activity 的
        // 资源配置、不动 Application —— 不同步的话这些文案会一直用系统语言（中文）。
        QuroI18nRt.setLocale(if (locales.isEmpty) "system" else locales.toLanguageTags())""", 1)

# ---------- 2) 语言显示名必须是固定字面量，不能随当前语言变 ----------
s = re.sub(r'\bqstr\(R\.string\.qk_02504\)', '"日本語"', s)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('locale follow ok')

# ---------- 3) QuroApplication.attachBaseContext 里尽早就绪 ----------
p = 'app/src/main/java/com/ai/assistance/quro/activity/QuroApplication.kt'
s = io.open(p, encoding='utf-8').read()
if 'QuroI18nRt.appContext = appCtx' not in s:
    old = '        appCtx = base.applicationContext ?: base'
    assert old in s, 'attachBaseContext anchor'
    s = s.replace(old, old + """
        // 尽早注入：qstr() 在 attachBaseContext / ContentProvider 阶段就可能被调用，
        // 晚注入会短暂返回空串（表现成界面文案空白、磁贴标题空白）。
        com.ai.assistance.quro.util.QuroI18nRt.appContext = appCtx""", 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('early init ok')
