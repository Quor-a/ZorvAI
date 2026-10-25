# check-snapshot-asset.ps1 — 发布快照资产一致性门禁（0.13.2 增补）
# 背景（0.13.1 实锤教训）：Release 的 snapshot-<abi>.tar.xz 资产被误取为注入前的
# build-snapshot 原始产物（缺 6 个注入包 + shell-termux 0.1.2 陈旧无 FENCE_KEYS），
# 而 APK 内嵌的是注入后 snap-final2。铁律：发布快照资产必须与 APK 内嵌快照同源一致。
# 用法：pwsh scripts\check-snapshot-asset.ps1 -ApkPath <apk> -SnapshotPath <xz>
# 通过条件：APK 内嵌 assets/snapshot.tar.xz 的 sha256 == 待发布 snapshot 文件 sha256
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$SnapshotPath
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $ApkPath)) { throw "APK 不存在: $ApkPath" }
if (-not (Test-Path $SnapshotPath)) { throw "快照文件不存在: $SnapshotPath" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
# GitHub Actions 的 Linux PowerShell 不保证 `$env:TEMP` 存在；使用运行时平台临时目录，避免跨平台发布链传入 null Path。
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("snap-embed-" + [guid]::NewGuid().ToString('N') + ".tar.xz")
try {
    $entry = $zip.GetEntry('assets/snapshot.tar.xz')
    if (-not $entry) { throw "APK 内缺少 assets/snapshot.tar.xz: $ApkPath" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $tmp, $true)
}
finally { $zip.Dispose() }
$h1 = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
Remove-Item $tmp -Force
$h2 = (Get-FileHash $SnapshotPath -Algorithm SHA256).Hash.ToLower()
if ($h1 -ne $h2) {
    Write-Error ("FAIL: 快照资产与 APK 内嵌不一致（勿取注入前 snapshot.tar.xz——0.13.1 实锤教训）`n" +
        "  APK 内嵌: " + $h1 + "`n" +
        "  资产文件: " + $h2 + "  (" + $SnapshotPath + ")")
    exit 1
}
Write-Host ("PASS: " + [IO.Path]::GetFileName($SnapshotPath) + " 与 APK 内嵌快照一致 (" + $h1 + ")")