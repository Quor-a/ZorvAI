# -*- coding: utf-8 -*-
"""把本轮成果沉淀进长期项目记忆 MEMORY.md。"""
import io

P = 'D:/WyDownloads/2026-08-28-00-11-37/.workbuddy/memory/MEMORY.md'
s = io.open(P, encoding='utf-8').read()

ADD = """

## 22b. 视频通话（真按钮 + 真界面 + VIDEO_CALL 模型配置，2026-09-29）

- **入口（三处，都走同一条权限感知路径 `startVideoCall(ctx)`，在 `ui/ChatScreen.kt` 文件末尾）**：
  ① `ui/chat/ChatTopBar.kt` 顶栏图标按钮（`LucideIcon("video", ...)` + 新参数 `onVideoCall`）；
  ② 设置 → 外观 → 对话分组那行 SetRow；③ 快捷磁贴 `QuroVideoCallTileService`；
  ④ 通知栏动作。流程：`RECORD_AUDIO`+`CAMERA` 运行时权限 → `Settings.canDrawOverlays`（无则 Toast +
  跳 `ACTION_MANAGE_OVERLAY_PERMISSION`）→ `startForegroundService(ACTION_START)`。
- **界面** = `QuroVideoCallService` 的 WindowManager 悬浮窗（不是 App 内 Compose 页）：
  可拖动状态条（绿点=通话中）+ 实时摄像头预览 + 底部字幕 + 圆形三键（麦克风 / 前后摄 / 挂断）。
  窗口 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`，`addCallView()` 返回 Boolean 以表达
  「无悬浮窗权限」而不是抛异常。
- **🔴 曾修掉的硬 bug**：`textureView` **从未被赋值**（`surfaceTextureListener` 定义了但没挂到任何视图），
  所以预览全黑 + `captureFrame()` 恒 null → **画面理解层完全失效**。修法：界面里用
  `AndroidView { TextureView(ctx).apply { surfaceTextureListener = ...; textureView = this } }`。
  **以后再动这个服务，先确认 `textureView` 有赋值点**。
- **前台服务类型必须按已授权权限拼**：Android 14 若声明 `camera|microphone` 但权限未授予，
  `startForeground(id, notif, type)` 直接抛 SecurityException。故 `onCreate` 里逐权限 or 出 type，
  为 0 时兜底 MICROPHONE（外层 try/catch → `stopSelf()`）。
- **Camera2 换镜头必须 close → 重开**：同一 `CameraDevice` 上改不了 `LENS_FACING`（`switchCamera()`）。
- **`QuroFunctionType.VIDEO_CALL`（第 13 项，插在 VIDEO_RECOGNITION 之后）**：
  label 复用 `qk_00183`（视频通话，11 语言已有），desc = `qk_03873`。
  配置页遍历 `values()` 自动出现；但 **`featureIcon()` 是穷举 `when`，新增枚举必须补分支否则编译失败**。
  `engineWired(VIDEO_CALL)=true`。服务 `process()` 用 `resolveConfig(VIDEO_CALL, baseCfg)`；
  `captionFrame()` 优先 VIDEO_CALL 独立绑定、否则回落 VIDEO_RECOGNITION。
- **服务内保留中文的 3 处是 AI 提示词**（系统提示 / `[视频通话实时画面…]` 注入标记 /
  截帧描述指令）—— 按规则**不翻译**，别"顺手翻掉"。
- 词表本轮 +32（`qk_03873..qk_03904`，en+ja 真译）；`qk_03766/03767` 说明文案改写为 `qk_03903/03904`。

## 22c. i18n 铁律补充：关键词表绝不 i18n + 流水线 `LOGIC_SKIP_FILES`

- **判定口径**：中文串若会被 `contains` / `==` 与**数据**比对（关键词表、失败/交付标记表、
  路由词表、API 标签值、命令名），**绝不可进 i18n 词表** —— 翻译后非中文语言下永不命中，
  功能"没反应"却不报错（最难查的一类 bug）。
- **审计口令**：搜 `(listOf|setOf|arrayOf|mutableListOf)` 后紧跟 `qstr(` / `stringResource(` 的行。
  注意：**多数命中是正当的展示标签表**（fontNames / tabs / 模式名 / RRULE_DAY_LABELS），别一刀切改。
- `scripts/i18n_build.py` 已加 `LOGIC_SKIP_FILES`（整文件跳过替换），当前 8 个：
  `Verifier.kt`、`DeliverabilityJudge.kt`、`CanvasRouter.kt`、`QuroExperienceEngine.kt`、
  `IntentRouter.kt`、`FluidCloudBridge.kt`、`QuroCloudTtsCatalog.kt`、`ToolCapabilityDirectory.kt`。
  **往这 8 个文件里加文案不会被翻译，属预期**；新增同类文件请一并登记。
- **流水线幂等校验**：改完文案后重跑 `i18n_build.py`，期望输出「替换完成：0 个文件被修改」。
  若仍有文件被改，说明还有逻辑串在词表里。
- 本轮还修了上轮「还原不彻底」：19 处 `qstr` 残留（工具执行异常/幻灯/导图/异常/失败/可用/成功/工具/
  版本/执行中/完成/粤语/静音/做个视频/导出产物/今天/刚刚/本周），脚本 `scripts/fix_logic_keyword_tables.py`。
- **词表 `i18n_strings.json` 是 list，索引即 `qk_%05d`，只能追加**；它原本是
  `json.dumps(..., ensure_ascii=False, indent=0)` 的格式（**无缩进**），改写时不要用 indent=1，
  否则整个文件 4 万行全变、diff 失控。
"""

if '## 22b. 视频通话' in s:
    print('[skip] MEMORY.md 已含本节')
else:
    io.open(P, 'a', encoding='utf-8', newline='\n').write(ADD)
    print('MEMORY.md updated')
