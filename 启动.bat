@echo off
cd /d "%~dp0"

rem 已内置在线更新：launcher.exe 启动后会自己检查并升级，不再需要额外的更新脚本
rem 直接双击 launcher.exe 效果相同，这个 bat 只是保留老习惯
if not exist "launcher.exe" (
    echo [错误] 未找到 launcher.exe，请确认文件是否完整
    pause
    exit /b 1
)

start "" "launcher.exe"
