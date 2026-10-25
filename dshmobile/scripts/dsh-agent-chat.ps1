# dsh-agent-chat.ps1 — 与模拟器内 DSH agent 对话（0.13.5 W4c 协作轮通道）
#
# 用途：把提示词发给设备内引擎的 agent（它用的是设备上配置的模型），读回最终回复。
# 与 scripts/e2e-phone-test.ps1 的区别：那个是旧 wire（点号）与旧端口；本脚本走 0.13.3+
# 的斜杠 wire + 浏览器 Cookie 鉴权（token 从 engine.log 取，GET / 换 Set-Cookie）。
#
# 用法：
#   pwsh -File scripts\dsh-agent-chat.ps1 -Serial 127.0.0.1:16416 -Port 23180 -Prompt "你好"
#   pwsh -File scripts\dsh-agent-chat.ps1 ... -Session <sessionId> -Prompt "继续"   # 续同一会话
#   pwsh -File scripts\dsh-agent-chat.ps1 ... -New -Prompt "..."                  # 强制新建会话
#
# 退出码：0 成功（打印 ASSISTANT 文本）/ 1 失败。
param(
  [string]$Serial = "127.0.0.1:16416",
  [int]$Port = 23180,
  [string]$Prompt = "",
  [string]$Session = "",
  [int]$TimeoutSec = 240,
  [switch]$New
)

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port"

function Get-EngineCookie {
  $raw = (adb -s $Serial shell "run-as com.ai.assistance.quro.dshmobile grep -o 'token=[A-Za-z0-9_-]*' files/engine.log | tail -1" 2>$null) -join "`n"
  if (-not $raw) { throw "engine.log 中找不到 token（引擎未启动？）" }
  $token = ($raw -replace '(?s).*token=', '').Trim()
  if ($token.Length -lt 16) { throw "token 解析失败（len=$($token.Length)）" }
  $headers = curl.exe -s -i -m 12 "$base/?token=$token" 2>$null
  $line = $headers | Where-Object { $_ -match '^(?i)set-cookie:' } | Select-Object -First 1
  if (-not $line) { throw "GET / 未返回 Set-Cookie（token 过期？）" }
  $cookie = (($line -replace '^(?i)set-cookie:\s*', '') -split ';')[0].Trim()
  if ($cookie.Length -lt 16) { throw "cookie 解析失败" }
  return $cookie
}

function Invoke-Rpc {
  param([string]$Method, [hashtable]$Request, [string]$Cookie, [int]$RpcSeq)
  # 各端点对「请求对象」的参数名不完全一致（实测：session/list 用 _request、
  # session/create 用 request）。先按 request 发；网关回 missing "_request" 时换名重试一次。
  foreach ($key in @('request', '_request')) {
    $envelope = @{
      type    = 'client-request'
      rpcId   = "chat-$RpcSeq"
      method  = $Method
      payload = @{ args = @{ $key = $Request } }
    }
    $body = $envelope | ConvertTo-Json -Depth 12 -Compress
    $tmp = Join-Path $env:TEMP "dsh-chat-$RpcSeq.json"
    [IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
    try {
      $resp = curl.exe -s -m 30 -X POST -H "Content-Type: application/json" -H "Cookie: $Cookie" --data-binary "@$tmp" "$base/api/$Method" 2>$null
    } finally {
      Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
    if (-not $resp) { throw "RPC $Method 无响应" }
    $json = $resp | ConvertFrom-Json
    $failed = $json.result -and $json.result.ok -eq $false
    if (-not $failed) { return $json }
    $message = [string]$json.result.error.message
    if ($key -eq 'request' -and $message -match 'missing "_request"') { continue }
    throw "RPC $Method 失败：$($json.result.error.code) $message"
  }
  throw "RPC $Method 失败：参数名协商失败"
}

$seq = 0
$cookie = Get-EngineCookie

if ($New -or $Session -eq '') {
  $seq++
  $created = Invoke-Rpc -Method 'session/create' -Request @{} -Cookie $cookie -RpcSeq $seq
  $Session = $created.result.value.sessionId
  if (-not $Session) { $Session = $created.result.value.id }
  if (-not $Session) { throw "session/create 未返回 sessionId：$($created | ConvertTo-Json -Depth 6 -Compress)" }
  Write-Output "SESSION_ID: $Session"
}

if ($Prompt -ne '') {
  $seq++
  # session/prompt 的请求体（Typert 边界校验实测）：requestId + sessionId + mode + content。
  $promptReq = @{
    requestId = [guid]::NewGuid().ToString()
    sessionId = $Session
    mode      = 'queue'
    content   = @(@{ type = 'text'; text = $Prompt })
  }
  $sent = Invoke-Rpc -Method 'session/prompt' -Request $promptReq -Cookie $cookie -RpcSeq $seq
  Write-Output "PROMPT_SENT: $($sent.result.ok)"
}

function Get-SessionState {
  param([string]$Cookie, [string]$Id, [int]$Seq)
  $listed = Invoke-Rpc -Method 'session/list' -Request @{} -Cookie $Cookie -RpcSeq $Seq
  $items = @($listed.result.value.items)
  return $items | Where-Object { $_.sessionId -eq $Id } | Select-Object -First 1
}

function Get-AssistantText {
  param([string]$Cookie, [string]$Id, [double]$ThroughSeq, [int]$Seq)
  if (-not $ThroughSeq -or $ThroughSeq -le 0) { return '' }
  $page = Invoke-Rpc -Method 'session/page' -Request @{
    address      = @{ kind = 'session'; sessionId = $Id }
    throughSeq   = [int]$ThroughSeq
    maxMessages  = 40
  } -Cookie $Cookie -RpcSeq $Seq
  $texts = @()
  foreach ($record in @($page.result.value.records)) {
    $ev = $record.event
    if ($null -eq $ev) { continue }
    if ($ev.type -eq 'message' -and $ev.data.role -eq 'assistant') {
      $chunk = ($ev.data.content | Where-Object { $_.type -eq 'text' } | ForEach-Object { $_.text }) -join ''
      if ($chunk) { $texts += $chunk }
    }
  }
  if ($texts.Count -eq 0) { return '' }
  return $texts[-1]
}

# 轮询会话状态（session/list 的 projections.asOfSeq 就是游标），直到本轮结束。
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$lastText = ''
$sawRunning = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 3
  $seq++
  try {
    $state = Get-SessionState -Cookie $cookie -Id $Session -Seq $seq
  } catch {
    Write-Output "POLL_ERROR: $($_.Exception.Message)"
    continue
  }
  if ($null -eq $state) { continue }
  $asOf = 0
  if ($state.projections -and $state.projections.asOfSeq) { $asOf = [double]$state.projections.asOfSeq }
  if ($state.running) { $sawRunning = $true }
  if ($asOf -gt 0) {
    $seq++
    try { $text = Get-AssistantText -Cookie $cookie -Id $Session -ThroughSeq $asOf -Seq $seq } catch { $text = '' }
    if ($text -ne '') { $lastText = $text }
  }
  if (-not $state.running -and $lastText -ne '' -and ($sawRunning -or $asOf -gt 0)) {
    Write-Output '--- ASSISTANT ---'
    Write-Output $lastText
    exit 0
  }
}

Write-Output '--- TIMEOUT (last assistant text) ---'
Write-Output $lastText
exit 1
