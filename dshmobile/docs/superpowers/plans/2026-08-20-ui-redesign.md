# UI 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将原生启动/测试界面与控制台重构为"高端极简 + teal 点缀 + 双主题"设计。

**Architecture:** 颜色/尺寸/主题全部进 Android 资源(values + values-night 限定符),Kotlin 代码只引用 `R.color`/`R.dimen`;控制台复用同一 token 命名的 CSS 变量。不引入 XML 布局,保持程序化构建。

**Tech Stack:** Android resources(colors/dimens/themes)、Kotlin(程序化 View)、vanilla HTML/CSS/JS。

**Spec:** `docs/superpowers/specs/2026-08-20-ui-redesign-design.md`

## 执行结果(2026-08-20,PR #61)

- [x] Task 1-4 全部完成并提交(提交历史:`442b6ea`→`54fdcac`,后经上游重建为单提交 `20643c4`,PR #61)
- [x] 编译验证改走**远程 CI**(Kotlin 编译门禁 + 冲突标记/敏感信息扫描,全绿)——用户禁止本地重编译
- [x] **设计演进 v1→v2**:初版实现被判定效果差,按 redesign/high-end 规范重构为
  「简洁优雅现代 + Double-Bezel 双边框嵌套 + 背景微光 + 入场动效」,最终方案以
  spec(v2)为准,本 plan 代码块为 v1 版本,仅供参考
- [x] 修复链:elevation Int→Float 编译错误;`ds_danger_soft` 缺失闭合标签导致资源合并失败

**v1 → v2 关键差异**(详见 spec):

| 维度 | v1(本 plan) | v2(实际落地) |
|---|---|---|
| 设计方向 | 高端极简,单层描边卡片 | 简洁优雅现代,Double-Bezel 双层同心圆角(26/22dp) |
| 背景 | 纯平铺 `#FAFAF9`/`#0C0C0E` | 暖灰/近黑 + 顶部 teal 径向微光 |
| 品牌区 | 32dp 图标 + 20sp 字标横排 | squircle 图标容器(48dp,accent_soft 底)+ 22sp 字标 |
| 状态卡 | 单层 surface 卡片(圆角 16) | shell(26dp)+ core(22dp)嵌套,28dp 内衬 |
| 崩溃提示 | 纯红文字 | 胶囊徽章(danger_soft 底,12sp) |
| 进度条 | 6dp 平轨道 | 4dp 胶囊轨道 + teal 填充 |
| 操作区 | 实心 teal + 描边幽灵按钮 | 胶囊主按钮「启动引擎」+ 文字样式次操作(Ripple) |
| 动效 | 按钮 scale 0.97 | 入场 stagger(90ms×3,420ms,fade+rise)+ Ripple 按压 |
| 控制台 | 平铺 surface 分区 | 玻璃渐变状态条 + 发光状态点 + 双边框输入岛 + focus ring |

## Global Constraints

- 全 UI 仅一个高饱和点缀色 teal(浅 `#0D9488`,深 `#2DD4BF`)
- 中性灰一律暖调(禁止冷灰与暖灰混用);背景非纯黑/纯白(浅 `#F6F6F4`/深 `#0A0A0C`)
- 深色模式由 `values-night` 限定符切换,所有文本/按钮显式设色(修复 P0 黑字黑底)
- 间距用 8pt 网格(4/8/12/16/24/32/40/48),圆角:内嵌 6、卡片 22、外壳 26、胶囊 999
- 消灭硬编码魔数(尺寸/颜色必须走资源)
- 不改任何业务逻辑与字符串文案;console.html 的 JS 逻辑(裁剪/滚动/桥调用)不变
- 验证命令:`./gradlew :app:compileDebugKotlin`(项目无单测框架,编译即门禁;实际走远程 CI)

---

### Task 1: 设计 Token 资源(颜色/尺寸/主题,双态)

**Files:**
- Modify: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/values-night/colors.xml`
- Create: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values-night/themes.xml`

**Interfaces:**
- Produces: 资源名(后续任务引用):`R.color.ds_bg`、`R.color.ds_surface`、`R.color.ds_border`、`R.color.ds_text_primary`、`R.color.ds_text_secondary`、`R.color.ds_text_tertiary`、`R.color.ds_accent`、`R.color.ds_accent_pressed`、`R.color.ds_danger`、`R.color.ds_progress_track`、`R.dimen.ds_space_*`(4/8/12/16/24/32/40)、`R.dimen.ds_radius_sm`(6dp)、`R.dimen.ds_radius_md`(16dp)、`R.dimen.ds_radius_full`(999dp)

- [ ] **Step 1: 重写浅色 colors.xml**

`app/src/main/res/values/colors.xml` 全量替换为:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <!-- 启动图标背景(保持) -->
  <color name="ic_launcher_background">#ffffff</color>

  <!-- UI 设计 token:高端极简 + teal 点缀(浅色) -->
  <color name="ds_bg">#FAFAF9</color>
  <color name="ds_surface">#FFFFFF</color>
  <color name="ds_border">#14000000</color>
  <color name="ds_text_primary">#18181B</color>
  <color name="ds_text_secondary">#8C18181B</color>
  <color name="ds_text_tertiary">#5918181B</color>
  <color name="ds_accent">#0D9488</color>
  <color name="ds_accent_pressed">#0F766E</color>
  <color name="ds_danger">#DC2626</color>
  <color name="ds_progress_track">#1A18181B</color>
</resources>
```

- [ ] **Step 2: 创建深色 colors.xml**

`app/src/main/res/values-night/colors.xml`(限定符自动覆盖):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <color name="ds_bg">#0C0C0E</color>
  <color name="ds_surface">#141416</color>
  <color name="ds_border">#1AFFFFFF</color>
  <color name="ds_text_primary">#E7E7E8</color>
  <color name="ds_text_secondary">#8CE7E7E8</color>
  <color name="ds_text_tertiary">#59E7E7E8</color>
  <color name="ds_accent">#2DD4BF</color>
  <color name="ds_accent_pressed">#5EEAD4</color>
  <color name="ds_danger">#F87171</color>
  <color name="ds_progress_track">#1AE7E7E8</color>
</resources>
```

- [ ] **Step 3: 创建 dimens.xml**

`app/src/main/res/values/dimens.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <dimen name="ds_space_4">4dp</dimen>
  <dimen name="ds_space_8">8dp</dimen>
  <dimen name="ds_space_12">12dp</dimen>
  <dimen name="ds_space_16">16dp</dimen>
  <dimen name="ds_space_24">24dp</dimen>
  <dimen name="ds_space_32">32dp</dimen>
  <dimen name="ds_space_40">40dp</dimen>
  <dimen name="ds_radius_sm">6dp</dimen>
  <dimen name="ds_radius_md">16dp</dimen>
  <dimen name="ds_radius_full">999dp</dimen>
  <dimen name="ds_progress_height">6dp</dimen>
  <dimen name="ds_logo_size">32dp</dimen>
</resources>
```

- [ ] **Step 4: 重写浅色 themes.xml**

`app/src/main/res/values/themes.xml` 全量替换(显式文本色,修复 P0):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <style name="Theme.Dsh" parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:windowBackground">@color/ds_bg</item>
    <item name="android:statusBarColor">@color/ds_bg</item>
    <item name="android:textColor">@color/ds_text_primary</item>
    <item name="android:textColorPrimary">@color/ds_text_primary</item>
    <item name="android:colorAccent">@color/ds_accent</item>
  </style>
</resources>
```

- [ ] **Step 5: 重写深色 themes.xml**

`app/src/main/res/values-night/themes.xml` 全量替换:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <style name="Theme.Dsh" parent="android:Theme.Material.NoActionBar">
    <item name="android:windowBackground">@color/ds_bg</item>
    <item name="android:statusBarColor">@color/ds_bg</item>
    <item name="android:textColor">@color/ds_text_primary</item>
    <item name="android:textColorPrimary">@color/ds_text_primary</item>
    <item name="android:colorAccent">@color/ds_accent</item>
  </style>
</resources>
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL(资源编译通过)

- [ ] **Step 7: 提交**

```bash
git add app/src/main/res/
git commit -m "feat(ui): 设计 token 体系(colors/dimens/主题双态,teal 点缀)"
```

---

### Task 2: 原生启动/测试界面(guide view)重构

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/MainActivity.kt:1112-1201`(buildGuideView)、`:1367-1383`(showGuide)
- Modify: `app/src/main/java/com/dshmobile/shell/MainActivity.kt:1211-1215`(shutdownToGuide 状态 UI)
- Modify: `app/src/main/java/com/dshmobile/shell/MainActivity.kt:1250-1295`(startEngineFlow 状态 UI)

**Interfaces:**
- Consumes: Task 1 的全部资源名
- Produces: `buildGuideView()` 返回新结构的三层 LinearLayout(品牌区/状态卡/操作区);控件字段 `crashBanner`、`engineStatus`、`progressBar`、`progressText`、`logSummary` 名字不变(调用方零改动)

- [ ] **Step 1: 重写 buildGuideView 为三层结构**

将 `MainActivity.kt` 的 `buildGuideView()`(行 1112-1201)整体替换为:

```kotlin
  private fun buildGuideView(): LinearLayout {
    val ctx = this
    fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    val guide = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
      gravity = android.view.Gravity.CENTER
      visibility = View.GONE
    }

    // 1. 品牌区(顶部,克制)
    val brandRow = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      lp.setMargins(0, 0, 0, dp(40f))
      layoutParams = lp
    }
    val icon = ImageView(ctx).apply {
      setImageResource(R.mipmap.ic_launcher)
      layoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.ds_logo_size),
        resources.getDimensionPixelSize(R.dimen.ds_logo_size),
      )
    }
    val title = TextView(ctx).apply {
      text = "DeepCode"
      textSize = sp(20f)
      setTextColor(getColor(R.color.ds_text_primary))
      setPadding(dp(10f), 0, 0, 0)
      typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
      gravity = android.view.Gravity.CENTER_VERTICAL
    }
    brandRow.addView(icon)
    brandRow.addView(title)
    guide.addView(brandRow)

    // 2. 状态卡(surface 卡片)
    val card = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      elevation = dp(1f)
      setPadding(
        resources.getDimensionPixelSize(R.dimen.ds_space_24),
        resources.getDimensionPixelSize(R.dimen.ds_space_24),
        resources.getDimensionPixelSize(R.dimen.ds_space_24),
        resources.getDimensionPixelSize(R.dimen.ds_space_24),
      )
      val lp = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      )
      lp.setMargins(0, 0, 0, resources.getDimensionPixelSize(R.dimen.ds_space_24))
      layoutParams = lp
    }
    // 圆角卡片:单 background + outline
    card.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    card.clipToOutline = true
    card.background = android.graphics.drawable.GradientDrawable().apply {
      setColor(getColor(R.color.ds_surface))
      cornerRadius = resources.getDimension(R.dimen.ds_radius_md)
      setStroke(dp(1f), getColor(R.color.ds_border))
    }

    engineStatus = TextView(ctx).apply {
      textSize = sp(16f)
      setTextColor(getColor(R.color.ds_text_primary))
      gravity = android.view.Gravity.CENTER
      setPadding(0, 0, 0, dp(16f))
    }

    // 崩溃警示条
    crashBanner = TextView(ctx).apply {
      textSize = sp(12f)
      setTextColor(getColor(R.color.ds_danger))
      setPadding(0, dp(6f), 0, dp(10f))
      gravity = android.view.Gravity.CENTER
      visibility = View.GONE
    }

    progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
      visibility = View.GONE
      progressDrawable = android.graphics.drawable.ClipDrawable(
        android.graphics.drawable.GradientDrawable().apply {
          setColor(getColor(R.color.ds_accent))
          cornerRadius = resources.getDimension(R.dimen.ds_radius_full)
        },
        android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL,
      )
      progressBackgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.ds_progress_track))
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.ds_progress_height),
      )
    }

    progressText = TextView(ctx).apply {
      textSize = sp(13f)
      setTextColor(getColor(R.color.ds_text_secondary))
      setPadding(0, dp(8f), 0, dp(16f))
      gravity = android.view.Gravity.CENTER
      visibility = View.GONE
    }

    logSummary = TextView(ctx).apply {
      textSize = sp(11f)
      setTextColor(getColor(R.color.ds_text_tertiary))
      setPadding(0, dp(16f), 0, 0)
      gravity = android.view.Gravity.CENTER
      typeface = android.graphics.Typeface.MONOSPACE
      visibility = View.GONE
    }

    card.addView(engineStatus)
    card.addView(crashBanner)
    card.addView(progressBar)
    card.addView(progressText)
    card.addView(logSummary)
    guide.addView(card)

    // 3. 操作区(底部):主按钮实心 teal,次按钮幽灵
    fun makeButton(text: String, filled: Boolean, onClick: () -> Unit): Button {
      return Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = sp(14f)
        if (filled) {
          setTextColor(getColor(R.color.ds_surface))
          background = android.graphics.drawable.GradientDrawable().apply {
            setColor(getColor(R.color.ds_accent))
            cornerRadius = resources.getDimension(R.dimen.ds_radius_sm)
          }
          stateListAnimator = null
        } else {
          setTextColor(getColor(R.color.ds_text_primary))
          background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = resources.getDimension(R.dimen.ds_radius_sm)
            setStroke(dp(1f), getColor(R.color.ds_border))
          }
          stateListAnimator = null
        }
        setOnClickListener {
          animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            onClick()
          }.start()
        }
      }
    }
    fun buttonRow(vararg buttons: Button): LinearLayout {
      val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layoutParams = lp
      }
      for (b in buttons) {
        val blp = LinearLayout.LayoutParams(0, dp(48f), 1f)
        blp.setMargins(dp(6f), 0, dp(6f), 0)
        row.addView(b, blp)
      }
      return row
    }
    val retry = makeButton("重试", filled = true) { startEngineFlow() }
    val openConsole = makeButton("打开控制台", filled = false) {
      startActivity(Intent(this@MainActivity, ConsoleActivity::class.java))
    }
    val update = makeButton("检查运行时更新", filled = false) {
      UpdateManager(this@MainActivity).checkAndApply { status ->
        runOnUiThread { engineStatus.text = status }
      }
    }
    guide.addView(buttonRow(openConsole, retry, update))
    return guide
  }
```

- [ ] **Step 2: 检查 showGuide/shutdownToGuide/startEngineFlow 状态 UI 兼容**

确认现有逻辑只读写 `crashBanner`/`engineStatus`/`progressBar`/`progressText`/`logSummary` 的 `text`/`visibility`,字段名与签名未变。`showGuide()` 中 logSummary 可见性切换逻辑不变(行 1367-1383)。若 `crashBanner` 之前的红色 `0xFFF85149` 有硬编码引用,确认已全部替换为 `R.color.ds_danger`(无其他硬编码残留)。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若 `android.graphics.view.Gravity` 报错(应为 `android.view.Gravity`),修正后重编译。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/dshmobile/shell/MainActivity.kt
git commit -m "feat(ui): 启动/测试界面三层结构重构(品牌区/状态卡/操作区)"
```

---

### Task 3: 控制台 console.html 重构

**Files:**
- Modify: `app/src/main/assets/console.html`(样式与结构,CSS 变量区、状态栏、输入行;JS 逻辑不动)

**Interfaces:**
- Consumes: 无(独立文件)
- Produces: 与原生 token 同命名的 CSS 变量:`--ds-bg`/`--ds-surface`/`--ds-border`/`--ds-text-primary`/`--ds-text-secondary`/`--ds-accent`

- [ ] **Step 1: 重写样式为 --ds token 体系**

`console.html` 的 `<style>` 块中 `:root` 变量区替换为:

```css
  :root {
    --ds-bg: #0C0C0E;
    --ds-surface: #141416;
    --ds-border: #1AFFFFFF;
    --ds-text-primary: #E7E7E8;
    --ds-text-secondary: rgba(231, 231, 232, 0.55);
    --ds-accent: #2DD4BF;
    --ds-accent-pressed: #5EEAD4;
    --ds-danger: #F87171;
    --ds-radius-sm: 6px;
    --ds-radius-md: 16px;
  }
```

- [ ] **Step 2: 变量引用替换**

将 `<style>` 内所有硬编码颜色替换为 var() 引用:body 背景 `var(--ds-bg)`、状态栏/输入框背景 `var(--ds-surface)`、边框 `var(--ds-border)`、标题/文本 `var(--ds-text-primary)`、状态文本 `var(--ds-text-secondary)`、prompt 与 send 按钮 `var(--ds-accent)`、dot.err 与警示 `var(--ds-danger)`。圆角 6px→`var(--ds-radius-sm)`。

- [ ] **Step 3: 输入框 focus ring + 按钮按压反馈**

`#cmd:focus` 改为 `border-color: var(--ds-accent); box-shadow: 0 0 0 2px color-mix(in srgb, var(--ds-accent) 25%, transparent);`;`#send` 背景 `var(--ds-accent)`,文字深色 `#0C0C0E`,`#send:active { background: var(--ds-accent-pressed); transform: scale(0.98); }`。

- [ ] **Step 4: 结构微调**

状态栏加 `--ds-radius-sm` 胶囊状态点(`#statusbar .dot` 保持 8px 圆点);输入行保持吸底。HTML 结构与 JS 逻辑(trimOutput/scrollBottom/桥调用)一律不动。

- [ ] **Step 5: 静态验证**

Run: `python3 -c "import html.parser,sys; html.parser.HTMLParser().feed(open('app/src/main/assets/console.html').read()); print('HTML OK')"`
Expected: HTML OK;再人工检查 CSS 变量无未定义引用(`grep -o 'var(--[a-z-]*)' console.html | sort -u` 与 `:root` 定义集合一致)。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/assets/console.html
git commit -m "feat(ui): 控制台 token 化重构(teal 点缀/胶囊状态/按压反馈)"
```

---

### Task 4: 收尾验证

- [ ] **Step 1: 全量编译 + 资源 lint**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:lintDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 硬编码魔数残留检查**

Run: `grep -nE '0xFF[A-Fa-f0-9]{6}|setPadding\([0-9]+ \* density|\([0-9]+ \* density\)' app/src/main/java/com/dshmobile/shell/MainActivity.kt`
Expected: 仅保留非 UI 相关(如品牌区间距逻辑引用资源后的空匹配;若仍有 UI 魔数残留,评估是否属于本计划范围)

- [ ] **Step 3: 深色资源一致性检查**

Run: 对比 `values/colors.xml` 与 `values-night/colors.xml` 键集合(应完全一致,仅值不同)
Expected: 键集合一致

- [ ] **Step 4: 最终提交(如有遗留)**

```bash
git add -A
git commit -m "chore(ui): 重构收尾"
```