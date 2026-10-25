# e2e-provider-ui.ps1 — 纯 UI 路径添加自定义供应商（0.13.5 W3 自动补全回归方案）
#
# 目的：**不走文件写入**，用 ADB 模拟点击在模拟器上走一遍真实用户路径
# （☰ → 设置 → 模型 → 添加自定义提供方 → 填 Provider ID / 显示名称 / API 地址 / API 密钥
#  → 获取可用模型 → 创建提供方），用于回归「能力自动补全」：供应商创建后，插件应在数秒内
# 把引擎目录里声明的 reasoningEfforts 写回 settings.yaml，使模型选择器出现思考档位。
#
# 坐标基准：MuMu x86_64 900x1600 / density 320（2026-09-10 实测校准）。
# 换机型/分辨率需重新校准——脚本每步截图落 $ShotDir，便于核对。
#
# 用法：
#   pwsh -File scripts\e2e-provider-ui.ps1 -ApiKey (Read-Host 'API key' -AsSecureString)
#   pwsh -File scripts\e2e-provider-ui.ps1 -ApiKey (Read-Host 'API key' -AsSecureString) -FetchModels
#   pwsh -File scripts\e2e-provider-ui.ps1 -ApiKey (Read-Host 'API key' -AsSecureString) -ManualModel glm-5.3-flash
param(
  [string]$Serial = "127.0.0.1:16416",
  [string]$ProviderId = "mhs",
  [string]$DisplayName = "MHS Relay",
  [string]$BaseUrl = "https://api.mhsapi.top/v1",
  [System.Security.SecureString]$ApiKey = $null,
  [switch]$FetchModels,
  [string]$ManualModel = "",
  [string]$ShotDir = ".deploy-tmp\0135",
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $ShotDir | Out-Null
$step = 0
$script:credentialCaptureRestricted = ($null -ne $ApiKey -and $ApiKey.Length -gt 0)

function Shot([string]$name) {
  if ($script:credentialCaptureRestricted) {
    Write-Output "  shot: suppressed (secure credential supplied)"
    return
  }
  $script:step++
  $file = Join-Path $ShotDir ("ui-{0:d2}-{1}.png" -f $script:step, $name)
  adb -s $Serial exec-out screencap -p > $file
  Write-Output "  shot: $file"
}

function Tap([int]$x, [int]$y, [int]$waitMs = 900, [string]$note = "") {
  if ($DryRun) { Write-Output ("  [dry] tap {0},{1} {2}" -f $x, $y, $note); return }
  adb -s $Serial shell input tap $x $y | Out-Null
  Start-Sleep -Milliseconds $waitMs
  if ($note -ne "") { Write-Output "  tap $x,$y ($note)" }
}

function TypeText([string]$text, [string]$note = "", [switch]$Sensitive) {
  $length = if ($null -eq $text) { 0 } else { $text.Length }
  if ($DryRun) { Write-Output ("  [dry] type field={0} chars={1}" -f $note, $length); return }
  adb -s $Serial shell input text $text *> $null
  if ($LASTEXITCODE -ne 0) { throw ("ADB text entry failed (field={0} chars={1})" -f $note, $length) }
  Start-Sleep -Milliseconds 500
  $redacted = if ($Sensitive) { " (redacted)" } else { "" }
  Write-Output ("  type field={0} chars={1}{2}" -f $note, $length, $redacted)
}

function ConvertFrom-SecureApiKey([System.Security.SecureString]$value) {
  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  }
  finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
  }
}

Write-Output "== 1. 前置：应用置前台（要求从聊天页开始；若停在设置/表单里请先手动 BACK 或重开应用）=="
adb -s $Serial shell input keyevent 3 | Out-Null
Start-Sleep -Milliseconds 700
adb -s $Serial shell am start -n com.ai.assistance.quro.dshmobile/.MainActivity | Out-Null
Start-Sleep -Seconds 3
Shot "start"

Write-Output "== 2. 打开侧边栏 → 设置 → 模型 =="
Tap 62 62 900 "hamburger"
Shot "sidebar"
Tap 81 1548 1400 "设置"
Shot "settings"
Tap 394 62 1600 "模型 tab"
Shot "models"

Write-Output "== 3. 打开自定义提供方表单（并把表单滚到可视区顶部，坐标以 form-open 截图为基准）=="
Tap 655 1070 1500 "添加自定义提供方"
# 表单在列表下方：上滑把表单顶到视口顶部，之后的字段坐标才稳定（MuMu 900x1600 校准值）
adb -s $Serial shell input swipe 450 1400 450 620 350 | Out-Null
Start-Sleep -Milliseconds 900
Shot "form-open"

Write-Output "== 4. 填写表单 =="
Tap 430 440 600 "Provider ID 输入框"
TypeText $ProviderId "provider id"
Tap 430 668 600 "显示名称输入框"
TypeText $DisplayName "display name"
Tap 430 818 600 "API 地址输入框"
TypeText $BaseUrl "base url"
if ($null -ne $ApiKey -and $ApiKey.Length -gt 0) {
  Tap 430 1100 600 "API 密钥输入框"
  if ($DryRun) {
    Write-Output ("  [dry] type field=api key chars={0} (redacted)" -f $ApiKey.Length)
  } else {
    $plainApiKey = ConvertFrom-SecureApiKey $ApiKey
    try { TypeText $plainApiKey "api key" -Sensitive }
    finally { $plainApiKey = $null }
  }
}
Shot "form-filled"

if ($FetchModels) {
  Write-Output "== 5. 点「获取可用模型」（引擎内建发现：只回 id/name/contextWindow/maxTokens）=="
  Tap 708 1199 6000 "获取可用模型"
  Shot "models-fetched"
}

if ($ManualModel -ne "") {
  Write-Output "== 5b. 手工添加模型 =="
  Tap 141 1391 900 "添加模型"
  Shot "model-row"
  # 模型行内的 ID 输入框（表单滚动后位置）——校准值见 model-row 截图
  Tap 430 1250 600 "模型 ID 输入框"
  TypeText $ManualModel "model id"
  Shot "model-filled"
}

Write-Output "== 6. 创建提供方 =="
Shot "before-create"
Tap 707 1523 4000 "创建提供方"
Shot "created"

Write-Output "== 7. 校验落盘（settings.yaml 是否出现该路由）=="
$yaml = adb -s $Serial shell "run-as com.ai.assistance.quro.dshmobile cat files/home/.dsh/settings.yaml" 2>$null
$hasRoute = ($yaml -join "`n") -match "(?m)^\s*$([regex]::Escape($ProviderId)):"
Write-Output ("  settings.yaml route present: {0}" -f $hasRoute)
if (-not $hasRoute) { Write-Output "  （提示：路由写入有延迟，可在 5-10 秒后重跑第 7 步校验）" }
Write-Output "完成。截图见 $ShotDir"
