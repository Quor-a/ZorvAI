# -*- coding: utf-8 -*-
"""设置 → 外观 → 视频通话 入口改为「权限感知」的共享启动路径。

原来只有一行裸 Intent（不过权限、不看悬浮窗），点了没反应也没提示。
现在和顶栏按钮同一套：申请相机/麦克风 → 检查悬浮窗 → 拉起 QuroVideoCallService。
"""
import io, os, sys

P = 'app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt'
fail = []

s = io.open(P, encoding='utf-8').read()

# ① 在外观设置页里补一个权限 launcher
anchor = "    val vcCtx = LocalContext.current\n"
block = anchor + """    // 视频通话权限 launcher：与本页入口同一套（先授权 → 检查悬浮窗 → 拉起服务）
    val videoCallPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val cam = result[android.Manifest.permission.CAMERA] == true
        val mic = result[android.Manifest.permission.RECORD_AUDIO] == true
        if (cam && mic) startVideoCall(vcCtx)
        else Toast.makeText(vcCtx, qstr(R.string.qk_03901), Toast.LENGTH_LONG).show()
    }
"""
if 'val videoCallPermLauncher' in s:
    print('  [skip] 已存在 videoCallPermLauncher')
elif anchor in s:
    s = s.replace(anchor, block, 1)
    print('  ok 外观设置页已加权限 launcher')
else:
    fail.append('未找到 vcCtx 锚点')

# ② 替换裸 Intent 的 onClick
old = """                    {
                        runCatching {
                            val it2 = Intent(vcCtx, com.ai.assistance.quro.service.QuroVideoCallService::class.java)
                                .setAction(com.ai.assistance.quro.service.QuroVideoCallService.ACTION_VIDEO_CALL)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                vcCtx.startForegroundService(it2)
                            } else {
                                vcCtx.startService(it2)
                            }
                        }
                    },"""
new = """                    {
                        val cam = ContextCompat.checkSelfPermission(vcCtx, android.Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        val mic = ContextCompat.checkSelfPermission(vcCtx, android.Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (cam && mic) startVideoCall(vcCtx)
                        else videoCallPermLauncher.launch(
                            arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
                        )
                    },"""
if old in s:
    s = s.replace(old, new, 1)
    print('  ok 设置页视频通话入口已改为权限感知路径')
elif new in s:
    print('  [skip] 设置页入口已是权限感知路径')
else:
    fail.append('未找到设置页裸 Intent 块')

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('FAILED:' if fail else 'ALL OK')
for f in fail:
    print('  !!', f)
sys.exit(1 if fail else 0)
