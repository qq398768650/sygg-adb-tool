@echo off
setlocal
cd /d "%~dp0"

rem 关闭已在运行的旧实例（释放 8765 端口并解锁 launcher.exe 以便更新）
taskkill /F /IM launcher.exe >nul 2>&1

rem 启动前检查并应用在线更新（失败不阻断）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0updater.ps1"

if not exist "%~dp0launcher.exe" (
    echo [错误] 未找到 launcher.exe，请检查文件是否完整。
    pause
    exit /b 1
)

rem 启动主程序（前台运行，控制台显示日志）
launcher.exe
