# -*- coding: utf-8 -*-
"""把「视频通话」的入口按钮 + 功能模型配置新项接线到源码。

只做 4 个文件的定点修改（本仓 Edit 工具不可靠，统一走 Python 读-改-写）：
1) QuroFeatureModelConfig.kt        —— 枚举新增 VIDEO_CALL（视频通话）
2) QuroFeatureModelConfigScreen.kt  —— 图标映射（穷举 when，必须补）+ 已接入引擎标记 + 说明文案更新
3) ChatTopBar.kt                    —— 顶栏新增「视频通话」图标按钮（真正的入口按钮）
4) ChatScreen.kt                    —— 权限申请 + 悬浮窗检查 + 拉起视频通话服务
"""
import io, os, sys

ROOT = 'app/src/main/java/com/ai/assistance/quro'
FAIL = []


def rw(path, fn):
    p = os.path.join(ROOT, path)
    src = io.open(p, encoding='utf-8').read()
    out, n = fn(src)
    if n == 0:
        FAIL.append('%s: 0 处命中' % path)
        print('  !! %s 未命中任何替换点' % path)
        return
    io.open(p, 'w', encoding='utf-8', newline='\n').write(out)
    print('  ok %s (%d 处)' % (path, n))


# ─────────────── 1) QuroFunctionType 新增 VIDEO_CALL ───────────────
def f_feature_cfg(s):
    n = 0
    old = "    VIDEO_RECOGNITION(R.string.qk_03750, R.string.qk_03761),\n    IMAGE_GEN("
    new = ("    VIDEO_RECOGNITION(R.string.qk_03750, R.string.qk_03761),\n"
           "    VIDEO_CALL(R.string.qk_00183, R.string.qk_03873),\n"
           "    IMAGE_GEN(")
    if old in s:
        s = s.replace(old, new, 1); n += 1
    s2 = s.replace(
        " * 功能类型覆盖 FunctionType 全集（CHAT/SUMMARY/MEMORY/UI_CONTROL/TRANSLATION/GREP/\n"
        " * PERSONA_INCUBATE/IMAGE_RECOGNITION/AUDIO_RECOGNITION/VIDEO_RECOGNITION/IMAGE_GEN/VIDEO_GEN）。",
        " * 功能类型覆盖 FunctionType 全集（CHAT/SUMMARY/MEMORY/UI_CONTROL/TRANSLATION/GREP/\n"
        " * PERSONA_INCUBATE/IMAGE_RECOGNITION/AUDIO_RECOGNITION/VIDEO_RECOGNITION/VIDEO_CALL/\n"
        " * IMAGE_GEN/VIDEO_GEN）。其中 VIDEO_CALL 为「视频通话」独立模型绑定，\n"
        " * 由 QuroVideoCallService 的实时对话与画面理解消费。", 1)
    if s2 != s:
        s = s2; n += 1
    return s, n


rw('core/model/QuroFeatureModelConfig.kt', f_feature_cfg)


# ─────────────── 2) 功能模型配置页 ───────────────
def f_feature_screen(s):
    n = 0
    old_icon = "    QuroFunctionType.VIDEO_RECOGNITION -> Icons.Filled.Videocam\n"
    new_icon = ("    QuroFunctionType.VIDEO_RECOGNITION -> Icons.Filled.Videocam\n"
                "    QuroFunctionType.VIDEO_CALL -> Icons.Filled.Videocam\n")
    if old_icon in s:
        s = s.replace(old_icon, new_icon, 1); n += 1
    old_wired = ("    QuroFunctionType.IMAGE_RECOGNITION,\n"
                 "    QuroFunctionType.VIDEO_RECOGNITION -> EngineWired(qstr(R.string.qk_03778), true)")
    new_wired = ("    QuroFunctionType.IMAGE_RECOGNITION,\n"
                 "    QuroFunctionType.VIDEO_RECOGNITION,\n"
                 "    QuroFunctionType.VIDEO_CALL -> EngineWired(qstr(R.string.qk_03778), true)")
    if old_wired in s:
        s = s.replace(old_wired, new_wired, 1); n += 1
    if 'stringResource(R.string.qk_03766)' in s:
        s = s.replace('stringResource(R.string.qk_03766)', 'stringResource(R.string.qk_03903)', 1); n += 1
    if 'stringResource(R.string.qk_03767)' in s:
        s = s.replace('stringResource(R.string.qk_03767)', 'stringResource(R.string.qk_03904)', 1); n += 1
    s2 = s.replace('设计、移植。为 12 类 AI 能力各自绑定模型', '设计、移植。为 13 类 AI 能力各自绑定模型', 1)
    if s2 != s:
        s = s2; n += 1
    s2 = s.replace(
        " * VIDEO_RECOGNITION 由 video_understanding 工具接入 resolveConfig；二者均真实消费绑定模型。",
        " * VIDEO_RECOGNITION 由 video_understanding 工具接入 resolveConfig；\n"
        " * VIDEO_CALL 由 QuroVideoCallService 的实时对话接入 resolveConfig；以上均真实消费绑定模型。", 1)
    if s2 != s:
        s = s2; n += 1
    return s, n


rw('ui/QuroFeatureModelConfigScreen.kt', f_feature_screen)


# ─────────────── 3) 对话页顶栏：新增视频通话按钮 ───────────────
def f_topbar(s):
    n = 0
    old_sig = "    onToolCenter: () -> Unit = {},\n    onMinimize: () -> Unit = {},"
    new_sig = ("    onToolCenter: () -> Unit = {},\n"
               "    /** 视频通话入口：拉起 QuroVideoCallService（相机 + 麦克风前台服务，通话界面在悬浮窗）。 */\n"
               "    onVideoCall: () -> Unit = {},\n"
               "    onMinimize: () -> Unit = {},")
    if old_sig in s:
        s = s.replace(old_sig, new_sig, 1); n += 1
    old_btn = "        IconButton(onClick = onSettings, modifier = Modifier.size(TOP_BAR_TOUCH)) {"
    new_btn = ("        IconButton(onClick = onVideoCall, modifier = Modifier.size(TOP_BAR_TOUCH)) {\n"
               "            LucideIcon(\"video\", stringResource(R.string.qk_00183), Modifier.size(21.dp), tint = cs.onBackground)\n"
               "        }\n"
               "        IconButton(onClick = onSettings, modifier = Modifier.size(TOP_BAR_TOUCH)) {")
    if old_btn in s:
        s = s.replace(old_btn, new_btn, 1); n += 1
    s2 = s.replace(
        "        // ③ 右侧固定：终端 + 设置。无 weight → 与 ① 同批被测量，长模型名/长人格名都挤不掉它",
        "        // ③ 右侧固定：终端 + 视频通话 + 设置 + 最小化。无 weight → 与 ① 同批被测量，\n"
        "        //    长模型名/长人格名都挤不掉它们", 1)
    if s2 != s:
        s = s2; n += 1
    return s, n


rw('ui/chat/ChatTopBar.kt', f_topbar)


# ─────────────── 4) ChatScreen：权限 + 拉起服务 ───────────────
def f_chatscreen(s):
    n = 0
    # 4.1 补 import
    if 'import androidx.core.content.ContextCompat' not in s:
        s = s.replace('import android.content.Context\n',
                      'import android.content.Context\nimport android.content.pm.PackageManager\n', 1)
        # ContextCompat 放到 androidx 段：紧跟 rememberLauncherForActivityResult 之后
        s = s.replace('import androidx.activity.compose.rememberLauncherForActivityResult\n',
                      'import androidx.activity.compose.rememberLauncherForActivityResult\n'
                      'import androidx.core.content.ContextCompat\n', 1)
        n += 1
    # 4.2 在 pickLauncher 之后插入 launcher + 启动函数
    anchor = """    // 屏幕捕获（MediaProjection 媒体投影 / 录屏投屏）系统授权 launcher："""
    block = """    // 视频通话：先申请相机/麦克风（Android 14+ 前台服务类型 camera|microphone 要求运行时权限已授予），
    // 再检查悬浮窗权限（通话界面是 WindowManager 悬浮窗），最后拉起 QuroVideoCallService。
    val videoCallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val cam = result[android.Manifest.permission.CAMERA] == true
        val mic = result[android.Manifest.permission.RECORD_AUDIO] == true
        if (cam && mic) startVideoCall(ctx)
        else Toast.makeText(ctx, qstr(R.string.qk_03901), Toast.LENGTH_LONG).show()
    }
    val openVideoCall: () -> Unit = {
        val cam = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (cam && mic) startVideoCall(ctx)
        else videoCallLauncher.launch(
            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        )
    }

"""
    if anchor in s and 'videoCallLauncher' not in s:
        s = s.replace(anchor, block + anchor, 1); n += 1
    # 4.3 顶栏接线
    old_call = "                        onToolCenter = { showToolCenter = true },\n                        onMinimize = { chatMinimized = true },"
    new_call = ("                        onToolCenter = { showToolCenter = true },\n"
                "                        onVideoCall = openVideoCall,\n"
                "                        onMinimize = { chatMinimized = true },")
    if old_call in s:
        s = s.replace(old_call, new_call, 1); n += 1
    # 4.4 文件末尾追加启动函数
    tail = """

/**
 * 拉起视频通话（[com.ai.assistance.quro.service.QuroVideoCallService]）。
 * 通话界面是该服务的 WindowManager 悬浮窗，因此必须先有悬浮窗权限；没有则引导去系统设置。
 */
private fun startVideoCall(ctx: Context) {
    if (!android.provider.Settings.canDrawOverlays(ctx)) {
        Toast.makeText(ctx, qstr(R.string.qk_03902), Toast.LENGTH_LONG).show()
        runCatching {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return
    }
    runCatching {
        val i = Intent(ctx, com.ai.assistance.quro.service.QuroVideoCallService::class.java)
            .setAction(com.ai.assistance.quro.service.QuroVideoCallService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
        else ctx.startService(i)
    }
}
"""
    if 'private fun startVideoCall(ctx: Context)' not in s:
        s = s.rstrip('\n') + '\n' + tail
        n += 1
    return s, n


rw('ui/ChatScreen.kt', f_chatscreen)

print('FAILED:' if FAIL else 'ALL OK', FAIL if FAIL else '')
sys.exit(1 if FAIL else 0)
