# 在线更新：比对远端 version.json，逐文件校验 MD5，只下载有变化的文件
# 由 启动.bat（启动前）与网页「检查更新」调用；更新失败不阻断启动。
# 多镜像：version.json 取各镜像中版本号最高者；文件下载逐个镜像校验 MD5，命中才采用。
$ErrorActionPreference = 'SilentlyContinue'
# Windows PowerShell 5.1 默认可能还在用 TLS1.0，GitHub / 镜像都会直接失败
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo     = 'qq398768650/sygg-adb-tool'
$branch   = 'main'
$localVer = Join-Path $root 'version.json'
$curl     = Join-Path $env:SystemRoot 'System32\curl.exe'
if (-not (Test-Path -LiteralPath $curl)) { $curl = 'curl.exe' }
# 加时间戳绕过镜像缓存（刚发布时镜像可能还缓存着旧版 version.json）
$ts       = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

# 多个下载源，逐个尝试（GitHub 直连在国内常被墙，用镜像兜底）
$mirrors = @(
    "https://ghfast.top/https://raw.githubusercontent.com/$repo/$branch",
    "https://gh-proxy.com/https://raw.githubusercontent.com/$repo/$branch",
    "https://cdn.jsdelivr.net/gh/$repo@$branch",
    "https://raw.githubusercontent.com/$repo/$branch"
)

$logFile  = Join-Path $root '.update.log'
$progFile = Join-Path $root '.update.progress.json'
$utf8NoBom = New-Object Text.UTF8Encoding $false
$script:updIndex = 0
$script:updCount = 0

# 进度同时写控制台（启动.bat 双击可见）和 .update.progress.json（网页轮询可见）
function Write-Log([string]$msg, [string]$color = '') {
    if ($color) { Write-Host $msg -ForegroundColor $color } else { Write-Host $msg }
    try { [IO.File]::AppendAllText($logFile, "$msg`r`n", $utf8NoBom) } catch {}
}

function Set-ProgressState {
    param(
        [string]$phase, [string]$msg, [string]$file = '',
        [long]$done = 0, [long]$size = 0, [int]$filePct = -1,
        [int]$index = -1, [int]$count = -1
    )
    if ($index -lt 0) { $index = $script:updIndex }
    if ($count -lt 0) { $count = $script:updCount }
    if ($filePct -lt 0) {
        $filePct = 0
        if ($size -gt 0) { $filePct = [int][Math]::Min(100, [Math]::Floor($done * 100 / $size)) }
    }
    $overall = 0
    if ($phase -eq 'done') { $overall = 100 }
    elseif ($count -gt 0) {
        $overall = [int][Math]::Floor((($index - 1) + ($filePct / 100.0)) * 100 / $count)
        if ($overall -gt 100) { $overall = 100 }
        if ($overall -lt 0) { $overall = 0 }
    }
    $o = [ordered]@{
        phase = $phase; msg = $msg; file = $file; index = $index; count = $count
        done = $done; size = $size; pct = $filePct; overall = $overall
        ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    }
    try { [IO.File]::WriteAllText($progFile, ($o | ConvertTo-Json -Compress), $utf8NoBom) } catch {}
}

function Format-Size([long]$b) {
    if ($b -ge 1048576) { return ('{0:N1}MB' -f ($b / 1MB)) }
    if ($b -ge 1024) { return ('{0:N0}KB' -f ($b / 1KB)) }
    return "$b B"
}

# 单行原地刷新的下载进度（\r 覆盖），同时落盘给网页读
function Show-Download([string]$file, [long]$done, [long]$size) {
    $pct = 0
    if ($size -gt 0) { $pct = [int][Math]::Min(100, [Math]::Floor($done * 100 / $size)) }
    Set-ProgressState 'download' "下载 $file" $file $done $size $pct
    $t = "  [$script:updIndex/$script:updCount] $file  $pct%  $(Format-Size $done)"
    if ($size -gt 0) { $t += " / $(Format-Size $size)" }
    Write-Host ("`r" + $t.PadRight(88)) -NoNewline
}

function Get-Md5([string]$p) {
    if (Test-Path -LiteralPath $p) { return (Get-FileHash -LiteralPath $p -Algorithm MD5).Hash.ToLower() }
    return ''
}

# 语义化版本比较：'v0.1.6' / '0.1.6' -> (0,1,6)，按数值比较（不能按字符串比，否则 0.1.10 < 0.1.9）
# 只认「主.次.修订」；其它（例如旧的时间戳版本号）一律当作 0.0.0，避免某个镜像缓存了旧版
# version.json 时，把旧版误判成"最高版本"从而把新版本降级覆盖回去。
function Convert-Ver([string]$v) {
    $v = "$v".TrimStart('v', 'V')
    if ($v -notmatch '^(\d+)\.(\d+)\.(\d+)') { return ,@(0, 0, 0) }
    return ,@([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
}
function Compare-Ver([string]$a, [string]$b) {
    $x = Convert-Ver $a; $y = Convert-Ver $b
    for ($i = 0; $i -lt 3; $i++) {
        if ($x[$i] -lt $y[$i]) { return -1 }
        if ($x[$i] -gt $y[$i]) { return 1 }
    }
    return 0
}

# 依次尝试各镜像把 $rel 下载到 $dest；若给了 $want，则要求 MD5 命中才返回 $true
# （避免某个镜像缓存旧文件被误用）。用 .NET 流式下载，边下边报进度。
function Get-Remote([string]$rel, [string]$dest, [string]$want = '', [int]$maxTime = 180) {
    # 文件名可能含中文（如 启动.bat），URL 路径必须转义，否则请求直接失败
    $relEsc = [uri]::EscapeDataString($rel) -replace '%2F', '/'
    foreach ($m in $mirrors) {
        Remove-Item -LiteralPath $dest -Force
        $req = [Net.HttpWebRequest]::Create("$m/${relEsc}?t=$ts")
        $req.Timeout = 15000
        $req.ReadWriteTimeout = $maxTime * 1000
        $req.UserAgent = 'sygg-updater'
        $resp = $null; $rs = $null; $fs = $null
        $ok = $false
        try {
            $resp = $req.GetResponse()
            $size = [long]$resp.ContentLength
            $rs = $resp.GetResponseStream()
            $fs = [IO.File]::Create($dest)
            $buf = New-Object byte[] 131072
            $done = [long]0
            $tick = [Environment]::TickCount
            while ($true) {
                $n = $rs.Read($buf, 0, $buf.Length)
                if ($n -le 0) { break }
                $fs.Write($buf, 0, $n)
                $done += $n
                $now = [Environment]::TickCount
                if (($now - $tick) -ge 150) { $tick = $now; Show-Download $rel $done $size }
            }
            $ok = ($done -gt 0)
            Show-Download $rel $done $size
            Write-Host ''
        } catch {
            Write-Host ''
        }
        if ($fs) { try { $fs.Close() } catch {} }
        if ($rs) { try { $rs.Close() } catch {} }
        if ($resp) { try { $resp.Close() } catch {} }
        if (-not $ok) { Remove-Item -LiteralPath $dest -Force; continue }
        if ($want -and ((Get-Md5 $dest) -ne $want)) {
            Write-Host "  [校验] $rel 来自镜像内容不一致，换下一个镜像" -ForegroundColor Yellow
            Remove-Item -LiteralPath $dest -Force
            continue
        }
        return $true
    }
    Remove-Item -LiteralPath $dest -Force
    return $false
}

Remove-Item -LiteralPath $logFile -Force
Write-Log '[update] 检查最新版本...' 'Cyan'
Set-ProgressState 'checking' '检查远端版本'

# 取各镜像中版本号最高的 version.json（避免某个镜像缓存旧版本导致误判）
$remote = $null
$mi = 0
foreach ($m in $mirrors) {
    $mi++
    Set-ProgressState 'checking' "获取远端版本（镜像 $mi/$($mirrors.Count)）"
    $t = Join-Path $root '.version.try.json'
    Remove-Item -LiteralPath $t -Force
    & $curl -fsSL --connect-timeout 5 --max-time 10 -o $t "$m/version.json?t=$ts" 2>$null | Out-Null
    if (-not (Test-Path -LiteralPath $t)) { continue }
    if ((Get-Item -LiteralPath $t).Length -le 0) { continue }
    try { $r = Get-Content -LiteralPath $t -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $r = $null }
    Remove-Item -LiteralPath $t -Force
    if (-not $r -or -not $r.version) { continue }
    if (-not $remote -or (Compare-Ver $r.version $remote.version) -gt 0) { $remote = $r }
}
if (-not $remote) {
    Write-Log '[update] 无法获取远端版本（网络不可用），跳过更新。' 'Yellow'
    Set-ProgressState 'offline' '网络不可用，跳过更新' '' 0 0 100
    exit 0
}

$localVersion = ''
if (Test-Path -LiteralPath $localVer) {
    try { $localVersion = (Get-Content -LiteralPath $localVer -Raw -Encoding UTF8 | ConvertFrom-Json).version } catch {}
}
Write-Log "[update] 本地 $localVersion / 远端 $($remote.version)"

# 本地不旧于远端则跳过，绝不把本地更新版本降级（镜像缓存滞后时尤其重要）
if ($localVersion -and ((Compare-Ver $remote.version $localVersion) -le 0)) {
    Write-Log "[update] 已是最新版本 $localVersion" 'Green'
    Set-ProgressState 'uptodate' "已是最新版本 $localVersion" '' 0 0 100
    exit 0
}

$failed   = $false
$changed  = $false
$deferred = $false
$files = @($remote.files.PSObject.Properties)
$script:updCount = $files.Count
$idx = 0
foreach ($prop in $files) {
    $idx++
    $script:updIndex = $idx
    $rel    = $prop.Name
    $want   = "$($prop.Value)".ToLower()
    $target = Join-Path $root $rel

    if ((Get-Md5 $target) -eq $want) { continue }

    $changed = $true
    $dir = Split-Path -Parent $target
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

    $tmp = "$target.new"
    Write-Log "  [下载] $rel（$idx/$($files.Count)）" 'Cyan'
    Set-ProgressState 'download' "下载 $rel" $rel 0 0 0
    if (-not (Get-Remote $rel $tmp $want 180)) {
        Write-Log "  [失败] $rel 下载失败或各镜像内容不一致" 'Red'
        Remove-Item -LiteralPath $tmp -Force
        $failed = $true
        continue
    }
    try {
        Move-Item -LiteralPath $tmp -Destination $target -Force -ErrorAction Stop
        Write-Log "  [完成] $rel 已更新" 'Green'
    } catch {
        # 文件被占用（例如正在运行的 launcher.exe）：安排本程序退出后自动替换
        Write-Log "  [待生效] $rel 被占用，将在程序退出后自动替换" 'Yellow'
        $deferred = $true
        $s = $tmp.Replace("'", "''")
        $d = $target.Replace("'", "''")
        $helper = "for(`$i=0;`$i -lt 600;`$i++){ try { Move-Item -LiteralPath '$s' -Destination '$d' -Force -ErrorAction Stop; break } catch { Start-Sleep -Seconds 1 } }"
        Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile','-WindowStyle','Hidden','-Command',$helper -WindowStyle Hidden
    }
}

if ($failed) {
    Write-Log '[update] 部分文件更新失败，请稍后重试。' 'Yellow'
    Set-ProgressState 'error' '部分文件更新失败，请稍后重试' '' 0 0 0
    exit 1
}

if ($deferred) {
    # 有文件待生效（正在运行的 launcher.exe 被占用）时不写 version.json，
    # 避免版本号虚高导致下次误判"已是最新"、主程序永远换不掉。
    Write-Log '[update] 主程序已下载，重启后生效。' 'Yellow'
    Set-ProgressState 'deferred' '主程序已下载，重启后生效' '' 0 0 100
    exit 2
}

# 全部文件均为最新后才写 version.json
try { [IO.File]::WriteAllText($localVer, ($remote | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false)) } catch {}

if ($changed) {
    Write-Log '[update] 更新完成。' 'Green'
    Set-ProgressState 'done' '更新完成' '' 0 0 100
} else {
    Write-Log "[update] 已是最新版本 $($remote.version)" 'Green'
    Set-ProgressState 'uptodate' "已是最新版本 $($remote.version)" '' 0 0 100
}
