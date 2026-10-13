# 一键发布：重建 launcher.exe -> 刷新 version.json -> 提交并推送
# 用法: 右键"使用 PowerShell 运行"，或在 PowerShell 里执行 .\release.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host '[release] 重新打包 launcher.exe ...' -ForegroundColor Cyan
if (Test-Path launcher.exe.bak) { Remove-Item launcher.exe.bak -Force }
if (Test-Path launcher.exe) { Move-Item launcher.exe launcher.exe.bak -Force }
if (Test-Path build_tmp) { Remove-Item build_tmp -Recurse -Force }

pyinstaller --onefile --name launcher `
    --distpath "$root\build_tmp\dist" `
    --workpath "$root\build_tmp\build" `
    --specpath "$root\build_tmp" `
    --noconfirm "$root\web\server.py" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'pyinstaller 打包失败' }

Copy-Item "$root\build_tmp\dist\launcher.exe" "$root\launcher.exe" -Force

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
    }
}
$obj | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $root 'version.json') -Encoding UTF8

Write-Host '[release] 提交并推送 ...' -ForegroundColor Cyan
git add -A
git commit -m "release $version"
git push

Write-Host "[release] 完成，已发布版本 $version" -ForegroundColor Green
