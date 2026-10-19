# 沈阳故宫 ADB 批量安装工具

Windows 下通过 ADB 批量安装 / 更新 APK 的本地 Web 工具，带设备识别、WiFi 下发、权限自动授予、并行安装等功能。

## 目录结构

```
launcher.exe          主程序（PyInstaller 打包的 web/server.py）
启动.bat             启动入口：先检查在线更新，再启动 launcher.exe
updater.ps1           在线更新脚本（比对 version.json，下载变化文件）
version.json          版本清单（版本号 + 各文件 MD5）
web/server.py         后端源码
web/index.html        前端页面
platform-tools/       adb 工具（随仓库分发）
使用说明.md           使用文档
build_launcher.bat    维护用：从 web/server.py 重新打包 launcher.exe
release.ps1           维护用：打包 + 刷新 version.json + 提交推送
```

## 使用

1. 把 APK 放在本目录（或按说明放到指定子目录）。
2. 双击 **启动.bat** 启动（会自动检查更新并打开浏览器）。
3. 设备连接后，在网页里选择 APK 执行「普通安装」或「并行安装」。

> 请始终用 启动.bat 启动，才能自动获取最新版本。

## 在线更新机制

- 每次启动，`启动.bat` 调用 `updater.ps1` 拉取远端 `version.json`。
- 本地版本号一致则跳过；不一致时，逐文件比对 MD5，只下载有变化的
  `launcher.exe` / `web/server.py` / `web/index.html`。
- 下载后校验 MD5，通过才替换；失败（如被占用）保留旧文件，不阻断启动。
- 更新源：`https://raw.githubusercontent.com/qq398768650/sygg-adb-tool/main/`

## 维护 / 发布

- 改完代码后，运行 `release.ps1`：自动重建 `launcher.exe`、刷新 `version.json`、提交并推送。
- 仅推送必要文件与工具，资源文件（APK、固件、数据等）由 `.gitignore` 排除。
