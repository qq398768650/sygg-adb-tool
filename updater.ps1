# 在线更新：比对远端 version.json，逐文件校验 MD5，只下载有变化的文件
# 由 start.bat（启动前）与网页「检查更新」调用；更新失败不阻断启动。
# 优先用 Windows 自带 curl.exe（比 .NET 的 Invoke-WebRequest 更稳定、更快）。
$ErrorActionPreference = 'SilentlyContinue'

$root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo     = 'qq398768650/sygg-adb-tool'
$branch   = 'main'
$base     = "https://raw.githubusercontent.com/$repo/$branch"
$localVer = Join-Path $root 'version.json'
$curl     = Join-Path $env:SystemRoot 'System32\curl.exe'
if (-not (Test-Path -LiteralPath $curl)) { $curl = 'curl.exe' }

function Get-Md5([string]$p) {
    if (Test-Path -LiteralPath $p) { return (Get-FileHash -LiteralPath $p -Algorithm MD5).Hash.ToLower() }
    return ''
}

Write-Host '[update] 检查最新版本...' -ForegroundColor Cyan
$verTmp = Join-Path $root '.version.remote.json'
& $curl -fsSL --max-time 30 -o $verTmp "$base/version.json?t=$([DateTime]::Now.Ticks)" 2>$null | Out-Null
$remote = $null
if ((Test-Path -LiteralPath $verTmp) -and (Get-Item $verTmp).Length -gt 0) {
    try { $remote = Get-Content -LiteralPath $verTmp -Raw -Encoding UTF8 | ConvertFrom-Json } catch {}
}
Remove-Item -LiteralPath $verTmp -Force
if (-not $remote) {
    Write-Host '[update] 无法获取远端版本，跳过更新。' -ForegroundColor Yellow
    exit 0
}

$localVersion = ''
if (Test-Path -LiteralPath $localVer) {
    try { $localVersion = (Get-Content -LiteralPath $localVer -Raw -Encoding UTF8 | ConvertFrom-Json).version } catch {}
}
Write-Host "[update] 本地 $localVersion / 远端 $($remote.version)"

$bust     = "?v=$($remote.version)"
$failed   = $false
$changed  = $false
$deferred = $false
foreach ($prop in $remote.files.PSObject.Properties) {
    $rel    = $prop.Name
    $want   = "$($prop.Value)".ToLower()
    $target = Join-Path $root $rel

    if ((Get-Md5 $target) -eq $want) { continue }

    $changed = $true
    $dir = Split-Path -Parent $target
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

    $tmp = "$target.new"
    Write-Host "  [下载] $rel" -ForegroundColor Cyan
    & $curl -fsSL --max-time 180 -o $tmp "$base/$rel$bust" 2>$null | Out-Null

    if (-not (Test-Path -LiteralPath $tmp) -or (Get-Item $tmp).Length -eq 0) {
        Write-Host "  [失败] $rel 下载失败" -ForegroundColor Red
        Remove-Item -LiteralPath $tmp -Force
        $failed = $true
        continue
    }
    if ((Get-Md5 $tmp) -ne $want) {
        Write-Host "  [失败] $rel 校验不一致（可能是 CDN 缓存），保留旧文件" -ForegroundColor Red
        Remove-Item -LiteralPath $tmp -Force
        $failed = $true
        continue
    }
    try {
        Move-Item -LiteralPath $tmp -Destination $target -Force -ErrorAction Stop
        Write-Host "  [完成] $rel 已更新" -ForegroundColor Green
    } catch {
        # 文件被占用（例如正在运行的 launcher.exe）：安排本程序退出后自动替换
        Write-Host "  [待生效] $rel 被占用，将在程序退出后自动替换" -ForegroundColor Yellow
        $deferred = $true
        $s = $tmp.Replace("'", "''")
        $d = $target.Replace("'", "''")
        $helper = "for(`$i=0;`$i -lt 600;`$i++){ try { Move-Item -LiteralPath '$s' -Destination '$d' -Force -ErrorAction Stop; break } catch { Start-Sleep -Seconds 1 } }"
        Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile','-WindowStyle','Hidden','-Command',$helper -WindowStyle Hidden
    }
}

if ($failed) {
    Write-Host '[update] 部分文件更新失败，请稍后重试。' -ForegroundColor Yellow
    exit 1
}

# 全部文件均为最新后才写 version.json，避免个别文件被占用时版本号虚高
try { [IO.File]::WriteAllText($localVer, ($remote | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false)) } catch {}

if ($deferred) {
    Write-Host '[update] 部分文件将在程序退出后自动生效，请关闭窗口后重新运行 start.bat。' -ForegroundColor Yellow
} elseif ($changed) {
    Write-Host '[update] 更新完成。' -ForegroundColor Green
} else {
    Write-Host "[update] 已是最新版本 $($remote.version)"
}
