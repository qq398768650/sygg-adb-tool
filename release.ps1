# 一键发布：重建 launcher.exe -> 刷新 version.json -> 提交并推送
# 用法: 右键"使用 PowerShell 运行"，或在 PowerShell 里执行 .\release.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

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

$version = Get-Date -Format 'yyyyMMddHHmm'
Write-Host "[release] 生成 version.json (版本 $version) ..." -ForegroundColor Cyan
$obj = [ordered]@{
    version = $version
    notes   = "release $version"
    files   = [ordered]@{
        'launcher.exe'   = (Get-Md5 (Join-Path $root 'launcher.exe'))
        'web/server.py'  = (Get-Md5 (Join-Path $root 'web/server.py'))
        'web/index.html' = (Get-Md5 (Join-Path $root 'web/index.html'))
        'updater.ps1'    = (Get-Md5 (Join-Path $root 'updater.ps1'))
        'start.bat'      = (Get-Md5 (Join-Path $root 'start.bat'))
    }
}
[IO.File]::WriteAllText((Join-Path $root 'version.json'), ($obj | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false))

Write-Host '[release] 提交并推送 ...' -ForegroundColor Cyan
git add -A
git commit -m "release $version"
git push

# 让 jsDelivr 立即刷新缓存（否则镜像可能最长 12 小时才更新）
Write-Host '[release] 刷新 jsDelivr 缓存 ...' -ForegroundColor Cyan
$curl = Join-Path $env:SystemRoot 'System32\curl.exe'
foreach ($f in @('version.json','launcher.exe','web/server.py','web/index.html','updater.ps1','start.bat')) {
    & $curl -s -o NUL "https://purge.jsdelivr.net/gh/qq398768650/sygg-adb-tool@main/$f" | Out-Null
}

Write-Host "[release] 完成，已发布版本 $version" -ForegroundColor Green
