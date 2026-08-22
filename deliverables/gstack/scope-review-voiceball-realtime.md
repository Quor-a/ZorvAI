# 悬浮语音球增强 — 范围评审 + 交互/架构设计

> 评审对象：QuroAI Android app「悬浮语音球」三项增强 — ①饰配 TTS/STT ②可随意拖动 ③开开关即实时对话
> 评审人：GStack 产品评审员 ｜ 结论：范围收敛、无需推翻重写，仅 1 处缺口 + 交互/架构加法
> 范围模式：**SELECTIVE EXPANSION**（既有链路可用，补实时循环 + 拖动，砍掉「再加子开关」的过度设计）

---

## A. TTS/STT 接入确认结论

**结论：已接好，本项无需重写。唯一缺口 = TTS 缺「播完回调」。**

代码实证（`service/QuroVoiceBallService.kt`）：
- `startListening()`(:248) 已调 `QuroSttHolder.startListening(context, language, partialResults, onPartial, onFinal, onError)` — STT 接入 OK。
- `speak(text)`(:372) 已调 `QuroTtsHolder.ensureReady(...)` + `QuroTtsHolder.speak(text)` — TTS 接入 OK。
- `process(text)`(:294) 串好 `store.add(user)` → `assistant.ask(...)` → `speak(reply)` — STT→LLM→TTS 全链路 OK。

缺口实证（`core/tools/QuroTtsHolder.kt`）：
- `speak(text): Int`(:216) 只 return `0/-1/-2`；`speakMinimal(text): Int`(:233) 同理。
- `postInitSetup`(:188) 里 `UtteranceProgressListener.onDone(u)` 仅 `log("onDone: $u")`，**未对外暴露完成事件**。
- 实时循环需要「回复播完 → 自动续听」，必须由 TTS 播完驱动下一次 `startListening`，故必须补 onDone。

**onDone 最小改动方案（QuroTtsHolder）：**
```
// 新增字段（线程安全）
private val doneCallbacks = mutableMapOf<String, (() -> Unit)?>()

// 签名变更：两个 speak 都加 onDone
suspend fun speak(text: String, onDone: (() -> Unit)? = null): Int
suspend fun speakMinimal(text: String, onDone: (() -> Unit)? = null): Int

// speak 内部：生成 id → 若 onDone!=null 存入 map → 用带 id 的 overload 朗读
val id = UUID.randomUUID().toString()
if (onDone != null) doneCallbacks[id] = onDone
val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
if (r != SUCCESS) doneCallbacks.remove(id)   // 失败即清，避免泄漏

// postInitSetup 的 onDone(u) 改为：
override fun onDone(u: String?) {
    log("onDone: $u")
    val cb = u?.let { doneCallbacks.remove(it) }
    cb?.invoke()          // ← 对外回调点
}
// 未就绪 / tts==null 的提前返回分支：onDone?.invoke()（保活循环用）
```
**关键约束**：`onDone` 在 TTS binder 线程回调，而 `startListening` 必须在主线程。Service 侧接 onDone 时必须 `mainHandler.post { ... }` 切主线程再续听。

---

## B. 可拖动方案

**核心：WindowManager 从 gravity 定位改为绝对 x/y + `OnTouchListener` 拖动；区分「拖动」与「点击」用移动阈值；边界钳制到屏幕内。**

`FLAG_NOT_FOCUSABLE` **保持不变** —— 它只禁用焦点、不屏蔽触摸，浮窗仍收得到 `MotionEvent`（当前 `.clickable` 正是靠它工作），加 `OnTouchListener` 无需改 flag。

**LayoutParams 改动（`addBall`）：**
```
val dm = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
screenW = dm.widthPixels; screenH = dm.heightPixels
val params = WindowManager.LayoutParams(
    WRAP_CONTENT, WRAP_CONTENT,
    TYPE_APPLICATION_OVERLAY,              // minSdk<O 回退 TYPE_PHONE（沿用现有分支）
    FLAG_NOT_FOCUSABLE,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.LEFT  // ← 改绝对坐标，去掉 BOTTOM|END
    x = screenW - 24.dp - ballW             // 初始仍落右下角（从底/右换算）
    y = screenH - 140.dp - ballH
}
windowManager.addView(composeView, params)
composeView?.post { ballW = composeView.width; ballH = composeView.height }  // 实测尺寸做钳制
```

**触摸处理放 `composeView` 层级（不在 Compose `pointerInput`）：**
```
val moveThreshold = (8 * resources.displayMetrics.density).toInt()  // 8dp
composeView.setOnTouchListener { _, e ->
    when (e.action) {
        ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; initX=params.x; initY=params.y; moved=false; true }
        ACTION_MOVE -> {
            val dx=e.rawX-downX; val dy=e.rawY-downY
            if (abs(dx)>moveThreshold || abs(dy)>moveThreshold) moved=true
            params.x=(initX+dx).coerceIn(0, screenW-ballW)
            params.y=(initY+dy).coerceIn(0, screenH-ballH)
            windowManager.updateViewLayout(composeView, params); true
        }
        ACTION_UP -> { if (!moved) onBallClick(); true }   // 未移动 = 点击
        else -> false
    }
}
```

**务必**：去掉 `QuroVoiceBallView.kt` Composable 的 `.clickable`（`ui/QuroVoiceBallView.kt:32`），否则点击会被「Service 触摸层 + Compose clickable」双重触发。

---

## C. 实时对话交互设计（核心）

**开关 ON = Service 启动 = `onCreate` 末尾自动 `startConversation()`；全程自动循环，无需每次点/长按。**

状态字段（Service 内）：
- `conversationActive: Boolean` —— 实时对话主开关（点击球切换）
- `listening: Boolean`（沿用）—— 正在听
- `speaking: Boolean`（新增）—— 正在 TTS 播报

循环编排（编排全在 Service，STT 仍单轮）：
```
startConversation()  // conversationActive=true; status="聆听中…"; startListening()

startListening() onFinal(text):
    listening=false
    if (text.isNotBlank()) { status="你说：$text"; resetEmptyCount(); process(text) }
    else { status="没听清，再试一次"; onEmptyOrError() }   // 退避后续听

startListening() onError(code,msg):
    listening=false; status="识别出错($msg)"; onEmptyOrError()

process(text):  // 沿用 store.add + assistant.ask
    status="思考中…"
    reply = assistant.ask(...)
    status="回复中…"
    speak(reply, onDone = { mainHandler.post {
        if (conversationActive) { status="聆听中…"; startListening() }   // ← 仅播完续听，无回声
    }})

onEmptyOrError():  // 退避 + 连续无语音保护
    emptyCount++
    if (emptyCount > MAX_CONSECUTIVE_EMPTY) { stopConversation(); return }  // 防无限旋转
    mainHandler.postDelayed({ if (conversationActive) startListening() }, BACKOFF_MS)  // 600ms
```

**关键不变量**：
- TTS 播报期间**绝不**调用 `startListening`，唯一续听入口是 `speak(..., onDone)` → 杜绝回声/抢占。
- 点击球 = 暂停/恢复（`conversationActive` 切换），不再是「启动一轮」的必需动作 → 满足用户「不用点一下或长按」。
- `onDone` 回调内判 `conversationActive` 的**实时值**（闭包读取当前字段，非捕获），暂停后不会续听。

**状态文案**：`聆听中…` / `思考中…` / `回复中…` / `已暂停`（暂停时 `status="已暂停"`，球变灰/描边态）。

**边界与健壮性**：
- 错误退避 600ms（`BACKOFF_MS`），避免 `ERROR_NO_MATCH`/`SPEECH_TIMEOUT` 错误风暴。
- 连续空/错超 `MAX_CONSECUTIVE_EMPTY=3` → 自动 `stopConversation()` + 文案提示，不再无限旋转。
- **STT 单例冲突（非阻塞）**：`QuroSttHolder` 是 `object` 单例，设置页 STT 测试与语音球共用；若两者同开，后调者会覆盖 `recognizer` 与回调。当前阶段接受（语音球为首选实时模式），**Phase 2** 给设置页测试独立实例/接口隔离。
- **TTS 单例被打断（非阻塞）**：语音球播报中若 LLM 工具调用 `speak`，`t.stop()` 会中断当前 utterance 并触发其 `onDone` → 可能提前续听。影响轻微，Phase 2 再隔离。

---

## D. 文件级改动清单

| 文件 | 职责 | 关键改动 |
|---|---|---|
| `core/tools/QuroTtsHolder.kt` | **缺口修复** | `speak`/`speakMinimal` 加 `onDone: (() -> Unit)? = null`；新增 `doneCallbacks` map；`postInitSetup.onDone(u)` 取 map 并 `invoke()`；失败/未就绪分支 `onDone?.invoke()` 保活 |
| `service/QuroVoiceBallService.kt` | 主改动 | ① `addBall()` 改绝对 x/y + `OnTouchListener` 拖动 + `DisplayMetrics` 钳制；② 新增 `conversationActive/speaking/emptyCount` 与 `startConversation()/stopConversation()`；③ `onBallClick()` 改为暂停/恢复 toggle；④ `startListening()` 内补 `onEmptyOrError` 退避 + 连续空保护；⑤ `process()` 内 `speak` 带 `onDone` 续听；⑥ 私有 `speak(text, onDone)` 透传 onDone 到 TTS（含 `speakMinimal` 兜底，双失败仍 `onDone?.invoke()`）；⑦ `onCreate` 末尾 `startConversation()`；⑧ `onDestroy` 置 `conversationActive=false` + 确保 `stopListening()`；⑨ Composable 调用去掉 `onClick` lambda，传 `speaking/paused` |
| `ui/QuroVoiceBallView.kt` | UI 态 | 去掉 `onClick` 参数与 `.clickable`；新增 `speaking`/`paused` 视觉态（播报=蓝、暂停=灰描边、聆听=红，沿用现有红/primary 逻辑扩展） |
| `app/build.gradle.kts` | 版本 | `versionCode` 41→**42**；`versionName` "1.0.41"→**"1.0.42"** |
| `ui/ChatScreen.kt` | **无需改动** | 推荐：开关即实时对话，**不新增子开关**（保持简洁，满足用户「开开关就对话」）。如产品坚持「点按启动」可选模式，再在此加一行 `SetRow` + 状态传递（本方案默认不做） |

**新增 import（Service）**：`android.view.MotionEvent`、`android.util.DisplayMetrics`（如改用绝对坐标需保留 `Gravity` 仅用于 `TOP or LEFT`）。

---

## E. 风险与建议

1. **拖动/点击判定阈值**：`MOVE_THRESHOLD` 用 dp 换算（8dp ≈ 8×density px），过低会误触拖动、过高会吞掉点击；建议 8–10dp，可在真机校准。
2. **浮窗触摸兼容**：`TYPE_APPLICATION_OVERLAY` 在 Android 12+ 默认可触摸（`FLAG_NOT_FOCUSABLE` 足够）；个别 OEM（小米/华为）对悬浮窗触摸有权限/手势限制，无法代码规避，需在自测清单覆盖。
3. **实时循环耗电/回声**：仅 `onDone` 续听 + 600ms 退避已从设计上消除回声与错误风暴；连续无语音自动暂停避免空转耗电。建议自测：对着麦静默 10s 验证自动暂停。
4. **STT/TTS 单例冲突**：与设置页同进程共用 `object` 单例（已确认非阻塞），Phase 2 隔离；本期在文档/提示中标注「实时对话中勿同时跑设置页 STT 测试」。
5. **线程安全**：`onDone` 在 TTS binder 线程 → Service 侧一律 `mainHandler.post` 后再 `startListening`；`doneCallbacks` 用 `mutableMapOf` 按 utterance id 隔离，支持并发 speak 不串回调。
6. **版本纪律**：每次 UI/功能改动必升 `versionCode/versionName`，本次 41→42。

---

## 交付判定（评审结论）

- **范围**：做满用户三件事，但「饰配」实为确认固化（已完成），真实工作量 = 1 处 TTS 缺口 + 拖动 + 实时循环编排。
- **过度设计已砍**：不加「实时对话」子开关（默认即实时），不加新权限（SYSTEM_ALERT_WINDOW / RECORD_AUDIO 已声明）。
- **可执行性**：改动集中在 4 个文件 + 1 处版本号，关键签名与方法职责已列明，可直接交开发落地（任务 #1）。
