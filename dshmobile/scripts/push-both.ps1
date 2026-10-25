#!/usr/bin/env pwsh
# push-both.ps1 — 双树镜像**按正确顺序**推送（0.14.0 反复踩到后固化）。
#
# 陷阱：coord 仓 CI 的「镜像一致性门禁」会 clone **apk 仓的对端分支**并逐字节比对。
# 两条分支是两个仓库，推送是两次独立操作。若先推 coord，coord CI 可能在 apk 镜像 commit
# 落地前就跑去 clone，于是必然报「内容漂移」——**假红**，而且看起来像真缺陷。
#
# 本轮因此误判/重跑多次。正确顺序：**先推镜像方（apk），再推权威方（coord）**；
# 中间留出窗口让远端可见，再推第二个。
#
# 用法：pwsh scripts\push-both.ps1 [-Message <注记>]
param([string]$Message = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$coord = Join-Path $root 'dsh-mobile'
$apk = Join-Path $root 'dsh-mobile-apk'
if (-not (Test-Path $coord)) { $coord = $root }
if (-not (Test-Path $apk)) { $apk = Join-Path $root 'dsh-mobile-apk' }

function Push-Retry([string]$dir, [string]$branch) {
  for ($i = 1; $i -le 6; $i++) {
    $o = git -C $dir push origin $branch 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) { Write-Host ("PUSH OK  " + $branch); return $true }
    Write-Host ("  retry $i : " + ($o.Trim().Split("`n")[-1]))
    Start-Sleep -Seconds (5 * $i)
  }
  return $false
}

Write-Host "推送顺序：先镜像方(apk) → 等远端可见 → 再权威方(coord)  $(if ($Message) { "[$Message]" })"
if (-not (Push-Retry $apk 'feat/0140-preview-shell')) { throw 'apk 推送失败' }
# 让 GitHub 侧先见到 apk 的新 commit（coord CI 随后 clone 它）。
Start-Sleep -Seconds 15
if (-not (Push-Retry $coord 'feat/0140-preview-engine')) { throw 'coord 推送失败' }
Write-Host '两仓已按镜像顺序推送完成（coord CI 不会再因推送顺序假红）'
