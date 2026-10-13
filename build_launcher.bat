@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo [build] 从 web\server.py 重新打包 launcher.exe ...

if exist "%~dp0launcher.exe.bak" del /f /q "%~dp0launcher.exe.bak"
if exist "%~dp0launcher.exe" move /y "%~dp0launcher.exe" "%~dp0launcher.exe.bak" >nul
if exist "%~dp0build_tmp" rmdir /s /q "%~dp0build_tmp"

pyinstaller --onefile --name launcher ^
  --distpath "%~dp0build_tmp\dist" ^
  --workpath "%~dp0build_tmp\build" ^
  --specpath "%~dp0build_tmp" ^
  --noconfirm "%~dp0web\server.py"

if errorlevel 1 (
    echo [build] 打包失败。
    exit /b 1
)

copy /y "%~dp0build_tmp\dist\launcher.exe" "%~dp0launcher.exe" >nul
echo [build] 完成：%~dp0launcher.exe
