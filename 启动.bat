@echo off
setlocal
cd /d "%~dp0"

rem 关闭正在运行的旧实例，释放 8765 端口（新实例会直接顶掉旧的）
taskkill /F /IM launcher.exe >nul 2>&1

rem 启动前检查并应用在线更新，失败不阻断
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0updater.ps1"

if not exist "%~dp0launcher.exe" (
    echo [错误] 未找到 launcher.exe，请确认文件是否完整。
    pause
    exit /b 1
)

rem 后台隐藏启动，本窗口随即关闭
powershell -NoProfile -Command "Start-Process -FilePath '.\launcher.exe' -WindowStyle Hidden"
