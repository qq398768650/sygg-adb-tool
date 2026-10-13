# 启动前在线更新：比对远端 version.json，只下载有变化的文件
# 由 start.bat 调用；更新失败不阻断启动。
$ErrorActionPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo     = 'qq398768650/sygg-adb-tool'
$branch   = 'main'
$base     = "https://raw.githubusercontent.com/$repo/$branch"
$localVer = Join-Path $root 'version.json'

function Get-Md5([string]$p) {
    if (Test-Path -LiteralPath $p) { return (Get-FileHash -LiteralPath $p -Algorithm MD5).Hash.ToLower() }
    return ''
}

Write-Host '[update] 检查最新版本...' -ForegroundColor Cyan
$remote = $null
try {
    $remote = Invoke-RestMethod -Uri "$base/version.json?t=$([DateTime]::Now.Ticks)" -TimeoutSec 15
} catch {
    Write-Host '[update] 无法连接 GitHub，跳过更新，直接启动。' -ForegroundColor Yellow
    exit 0
}

$localVersion = ''
if (Test-Path -LiteralPath $localVer) {
    try { $localVersion = (Get-Content -LiteralPath $localVer -Raw -Encoding UTF8 | ConvertFrom-Json).version } catch {}
}

if ($localVersion -eq $remote.version) {
    Write-Host "[update] 已是最新版本 $($remote.version)"
    exit 0
}
Write-Host "[update] 发现新版本: $localVersion -> $($remote.version)" -ForegroundColor Green

$bust = "?v=$($remote.version)"
foreach ($prop in $remote.files.PSObject.Properties) {
    $rel    = $prop.Name
    $want   = "$($prop.Value)".ToLower()
    $target = Join-Path $root $rel

    if ((Get-Md5 $target) -eq $want) { Write-Host "  [跳过] $rel"; continue }

    $dir = Split-Path -Parent $target
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

    $tmp = "$target.new"
    Write-Host "  [下载] $rel" -ForegroundColor Cyan
    try {
        Invoke-WebRequest -Uri "$base/$rel$bust" -OutFile $tmp -TimeoutSec 180
    } catch {
        Write-Host "  [失败] $rel 下载失败：$($_.Exception.Message)" -ForegroundColor Red
        Remove-Item -LiteralPath $tmp -Force
        continue
    }

    if ((Get-Md5 $tmp) -ne $want) {
        Write-Host "  [失败] $rel 校验不一致（可能是 CDN 缓存），保留旧文件" -ForegroundColor Red
        Remove-Item -LiteralPath $tmp -Force
        continue
    }

    try {
        Move-Item -LiteralPath $tmp -Destination $target -Force
        Write-Host "  [完成] $rel 已更新"
    } catch {
        Write-Host "  [失败] $rel 替换失败（文件被占用？）：$($_.Exception.Message)" -ForegroundColor Red
        Remove-Item -LiteralPath $tmp -Force
    }
}

try { $remote | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $localVer -Encoding UTF8 } catch {}
Write-Host '[update] 更新完成。' -ForegroundColor Green
