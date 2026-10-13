# 在线更新：比对远端 version.json，逐文件校验 MD5，只下载有变化的文件
# 由 start.bat（启动前）与网页「检查更新」调用；更新失败不阻断启动。
# 多镜像：version.json 取各镜像中版本号最高者；文件下载逐个镜像校验 MD5，命中才采用。
$ErrorActionPreference = 'SilentlyContinue'

$root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo     = 'qq398768650/sygg-adb-tool'
$branch   = 'main'
$localVer = Join-Path $root 'version.json'
$curl     = Join-Path $env:SystemRoot 'System32\curl.exe'
if (-not (Test-Path -LiteralPath $curl)) { $curl = 'curl.exe' }

# 多个下载源，逐个尝试（GitHub 直连在国内常被墙，用镜像兜底）
$mirrors = @(
    "https://ghfast.top/https://raw.githubusercontent.com/$repo/$branch",
    "https://gh-proxy.com/https://raw.githubusercontent.com/$repo/$branch",
    "https://gcore.jsdelivr.net/gh/$repo@$branch",
    "https://cdn.jsdelivr.net/gh/$repo@$branch",
    "https://raw.githubusercontent.com/$repo/$branch"
)

function Get-Md5([string]$p) {
    if (Test-Path -LiteralPath $p) { return (Get-FileHash -LiteralPath $p -Algorithm MD5).Hash.ToLower() }
    return ''
}

# 依次尝试各镜像把 $rel 下载到 $dest；若给了 $want，则要求 MD5 命中才返回 $true
# （避免某个镜像缓存旧文件被误用）
function Get-Remote([string]$rel, [string]$dest, [string]$want = '', [int]$maxTime = 20) {
    foreach ($m in $mirrors) {
        Remove-Item -LiteralPath $dest -Force
        & $curl -fsSL --connect-timeout 5 --max-time $maxTime -o $dest "$m/$rel" 2>$null | Out-Null
        if (-not (Test-Path -LiteralPath $dest)) { continue }
        if ((Get-Item -LiteralPath $dest).Length -le 0) { continue }
        if ($want -and ((Get-Md5 $dest) -ne $want)) { continue }
        return $true
    }
    Remove-Item -LiteralPath $dest -Force
    return $false
}

Write-Host '[update] 检查最新版本...' -ForegroundColor Cyan

# 取各镜像中版本号最高的 version.json（避免某个镜像缓存旧版本导致误判）
$remote = $null
foreach ($m in $mirrors) {
    $t = Join-Path $root '.version.try.json'
    Remove-Item -LiteralPath $t -Force
    & $curl -fsSL --connect-timeout 5 --max-time 10 -o $t "$m/version.json" 2>$null | Out-Null
    if (-not (Test-Path -LiteralPath $t)) { continue }
    if ((Get-Item -LiteralPath $t).Length -le 0) { continue }
    try { $r = Get-Content -LiteralPath $t -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $r = $null }
    Remove-Item -LiteralPath $t -Force
    if (-not $r -or -not $r.version) { continue }
    if (-not $remote -or "$($r.version)" -gt "$($remote.version)") { $remote = $r }
}
if (-not $remote) {
    Write-Host '[update] 无法获取远端版本（网络不可用），跳过更新。' -ForegroundColor Yellow
    exit 0
}

$localVersion = ''
if (Test-Path -LiteralPath $localVer) {
    try { $localVersion = (Get-Content -LiteralPath $localVer -Raw -Encoding UTF8 | ConvertFrom-Json).version } catch {}
}
Write-Host "[update] 本地 $localVersion / 远端 $($remote.version)"

# 本地不旧于远端则跳过，绝不把本地更新版本降级（镜像缓存滞后时尤其重要）
if ($localVersion -and ("$($remote.version)" -le "$localVersion")) {
    Write-Host "[update] 已是最新版本 $localVersion" -ForegroundColor Green
    exit 0
}

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
    if (-not (Get-Remote $rel $tmp $want 180)) {
        Write-Host "  [失败] $rel 下载失败或各镜像内容不一致" -ForegroundColor Red
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

# 全部文件均为最新且无待生效项后才写 version.json，避免版本号虚高
if (-not $deferred) {
    try { [IO.File]::WriteAllText($localVer, ($remote | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false)) } catch {}
}

if ($deferred) {
    Write-Host '[update] 部分文件将在程序退出后自动生效，请关闭窗口后重新运行 start.bat。' -ForegroundColor Yellow
} elseif ($changed) {
    Write-Host '[update] 更新完成。' -ForegroundColor Green
} else {
    Write-Host "[update] 已是最新版本 $($remote.version)"
}
