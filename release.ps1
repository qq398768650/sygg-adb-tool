# 一键发布：重建 launcher.exe -> 刷新 version.json -> 追加更新日志 -> 提交并推送 -> 建 GitHub Release
# 用法: 右键"使用 PowerShell 运行"，或在 PowerShell 里执行 .\release.ps1
#       也可带说明：.\release.ps1 -Note "修复了 XXX"
param([string]$Version = '', [string]$Note = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$repoSlug = 'qq398768650/sygg-adb-tool'
$proxy    = 'http://127.0.0.1:7897'

Write-Host '[release] 重新打包 launcher.exe ...' -ForegroundColor Cyan
if (Test-Path launcher.exe.bak) { Remove-Item launcher.exe.bak -Force }
if (Test-Path launcher.exe) { Move-Item launcher.exe launcher.exe.bak -Force }
if (Test-Path build_tmp) { Remove-Item build_tmp -Recurse -Force }

$pyArgs = @('--onefile','--name','launcher',
    '--distpath',"$root\build_tmp\dist",
    '--workpath',"$root\build_tmp\build",
    '--specpath',"$root\build_tmp",
    '--noconfirm',"$root\web\server.py")
$built = $false
try { python -m PyInstaller @pyArgs | Out-Null; if ($LASTEXITCODE -eq 0) { $built = $true } } catch {}
if (-not $built) { pyinstaller @pyArgs | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'pyinstaller 打包失败' } }

Copy-Item "$root\build_tmp\dist\launcher.exe" "$root\launcher.exe" -Force

# 确保 .ps1 带 UTF-8 BOM（编辑器保存常会丢 BOM，Windows PowerShell 5.1 无 BOM 会解析中文报错）
$utf8 = New-Object Text.UTF8Encoding $true
foreach ($f in @('updater.ps1','release.ps1')) {
    $p = Join-Path $root $f
    [IO.File]::WriteAllText($p, ([IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)), $utf8)
}

function Get-Md5([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm MD5).Hash.ToLower() }

# 版本号：读当前 version.json，默认自动 +1（patch）；也可 -Version 指定或运行时输入
$curVer = ''
$vj = Join-Path $root 'version.json'
if (Test-Path $vj) {
    try { $curVer = ([IO.File]::ReadAllText($vj, [Text.Encoding]::UTF8) | ConvertFrom-Json).version } catch {}
}
$suggest = '0.1.0'
if ($curVer -match '^v?(\d+)\.(\d+)\.(\d+)$') {
    $suggest = "$($Matches[1]).$($Matches[2]).$([int]$Matches[3] + 1)"
}
if (-not $Version) {
    Write-Host "[release] 版本号（回车用 $suggest）:" -ForegroundColor Cyan
    $vi = Read-Host
    if ($vi) { $Version = $vi.Trim() }
}
if (-not $Version) { $Version = $suggest }
$version = $Version.TrimStart('v', 'V')

# 本次更新说明：命令行 -Note 优先，否则交互输入，留空用默认
if (-not $Note) {
    Write-Host "[release] 本次更新说明（可留空，回车用默认）:" -ForegroundColor Cyan
    $input = Read-Host
    if ($input) { $Note = $input.Trim() }
}
if (-not $Note) { $Note = "版本 $version" }

Write-Host "[release] 生成 version.json (版本 $version) ..." -ForegroundColor Cyan
$obj = [ordered]@{
    version = $version
    notes   = $Note
    files   = [ordered]@{
        'launcher.exe'   = (Get-Md5 (Join-Path $root 'launcher.exe'))
        'web/server.py'  = (Get-Md5 (Join-Path $root 'web/server.py'))
        'web/index.html' = (Get-Md5 (Join-Path $root 'web/index.html'))
        'updater.ps1'    = (Get-Md5 (Join-Path $root 'updater.ps1'))
        'start.bat'      = (Get-Md5 (Join-Path $root 'start.bat'))
    }
}
[IO.File]::WriteAllText((Join-Path $root 'version.json'), ($obj | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false))

# 追加更新日志（新版本在最前）
$clPath = Join-Path $root 'CHANGELOG.md'
$entry  = "## v$version - $(Get-Date -Format 'yyyy-MM-dd')`r`n`r`n$Note`r`n`r`n"
if (Test-Path $clPath) {
    $old = ([IO.File]::ReadAllText($clPath, [Text.Encoding]::UTF8)).TrimStart([char]0xFEFF)
    $idx = $old.IndexOf('## ')
    if ($idx -ge 0) { $new = $old.Substring(0, $idx) + $entry + $old.Substring($idx) }
    else            { $new = $old.TrimEnd() + "`r`n`r`n" + $entry }
} else {
    $new = "# 更新日志`r`n`r`n" + $entry
}
[IO.File]::WriteAllText($clPath, $new, (New-Object Text.UTF8Encoding $false))

Write-Host '[release] 提交并推送 ...' -ForegroundColor Cyan
git add -A
git commit -m "release $version"
git push

# 创建 GitHub Release（发布信息 + 更新日志），走 Clash 代理
Write-Host '[release] 创建 GitHub Release ...' -ForegroundColor Cyan
$env:HTTPS_PROXY = $proxy
$env:HTTP_PROXY  = $proxy
$tag     = "v$version"
$relBody = "$Note`r`n`r`n### 文件`r`n" + (($obj.files.GetEnumerator() | ForEach-Object { "- ``$($_.Key)`` — ``$($_.Value)``" }) -join "`r`n")
$relBody += "`r`n`r`n> 客户端无需手动下载，运行 ``start.bat`` 会自动更新到本版本。"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& gh release create $tag --repo $repoSlug --title $tag --notes $relBody 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { Write-Host '[release] 创建 Release 失败（检查 Clash/网络后可用 gh release create 重试）' -ForegroundColor Yellow }
$ErrorActionPreference = $prevEap

# 让 jsDelivr 立即刷新缓存（否则镜像可能最长 12 小时才更新）
Write-Host '[release] 刷新 jsDelivr 缓存 ...' -ForegroundColor Cyan
$curl = Join-Path $env:SystemRoot 'System32\curl.exe'
foreach ($f in @('version.json','CHANGELOG.md','launcher.exe','web/server.py','web/index.html','updater.ps1','start.bat')) {
    & $curl -s -o NUL "https://purge.jsdelivr.net/gh/$repoSlug@main/$f" | Out-Null
}

Write-Host "[release] 完成，已发布版本 $version" -ForegroundColor Green
