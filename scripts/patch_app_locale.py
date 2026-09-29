# -*- coding: utf-8 -*-
"""QuroApplication：
1) attachBaseContext 里把语言配置包进 Application 的 base context（覆盖 Service/Provider/qstr）；
2) onCreate 里注册 Activity 跟踪，切语言后主动重建已打开界面。
"""
import io

P = 'app/src/main/java/com/ai/assistance/quro/activity/QuroApplication.kt'
src = io.open(P, encoding='utf-8').read()

OLD1 = """    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appCtx = base.applicationContext ?: base
"""
NEW1 = """    override fun attachBaseContext(base: Context) {
        // 语言：Android 12 及以下 AppCompatDelegate.setApplicationLocales 不会更新 Application
        // 的资源配置，Service / ContentProvider / qstr() 会一直用系统语言。这里自己包一层，
        // 让整个进程的默认资源也跟随所选语言。
        val localizedBase = com.ai.assistance.quro.util.QuroLocale.wrap(base)
        super.attachBaseContext(localizedBase)
        // 注意仍取原始 base 的 applicationContext：必须保持 appCtx 是真正的 Application 实例。
        appCtx = base.applicationContext ?: base
"""
assert OLD1 in src, 'anchor1 miss'
src = src.replace(OLD1, NEW1, 1)

OLD2 = """        // 非 @Composable 位置的取串运行时（工具类 / 回调 / 协程里的界面文案）依赖它。
        com.ai.assistance.quro.util.QuroI18nRt.appContext = applicationContext
"""
NEW2 = """        // 非 @Composable 位置的取串运行时（工具类 / 回调 / 协程里的界面文案）依赖它。
        com.ai.assistance.quro.util.QuroI18nRt.appContext = applicationContext

        // 记录 Activity 生命周期：切语言后立即重建已打开的界面，
        // 否则要等下次冷启才生效（用户会以为「切了语言没反应」）。
        try {
            com.ai.assistance.quro.util.QuroLocale.track(this)
        } catch (_: Throwable) {
        }
"""
assert OLD2 in src, 'anchor2 miss'
src = src.replace(OLD2, NEW2, 1)

io.open(P, 'w', encoding='utf-8', newline='').write(src)
print('QuroApplication 已更新')
