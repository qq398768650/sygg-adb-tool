# AGENTS.md — 沈阳故宫 ADB 批量安装工具

给后续维护者（人 / AI）看的项目说明。**动手改之前先读「5. 坑位」**，那里每一条都是实际踩过的雷。

---

## 1. 这是什么

Windows 上的 **ADB 批量安装工具**：

- 后端：`web/server.py`（标准库 `http.server`），打包成 `launcher.exe`（PyInstaller onefile）
- 前端：`web/index.html`（单页，浏览器打开 `http://127.0.0.1:8765/`）
- 启动：`启动.bat` → `updater.ps1`（在线更新）→ 后台拉起 `launcher.exe`（隐藏控制台 + 打开浏览器）

功能：批量装 APK、注入 fsa 数据、配 WiFi、采集 MAC/序列号、抓 deviceId、批量重启/关机、scrcpy 投屏、蓝牙配对、ADB 终端、日志查看。

仓库：<https://github.com/qq398768650/sygg-adb-tool>（public，`main`）

---

## 2. 目录与关键文件

| 路径 | 作用 |
|---|---|
| `web/server.py` | **后端全部逻辑**：HTTP 路由、adb 调用、APK 解析、fsa 注入、更新器调用 |
| `web/index.html` | **前端单页**：所有 UI + JS。运行时从磁盘读，改完刷新即生效 |
| `launcher.exe` | PyInstaller onefile，**内嵌 `web/server.py`** |
| `启动.bat` | 启动入口（GBK 编码）：杀旧进程 → 跑 `updater.ps1` → 后台启动 launcher |
| `updater.ps1` | 在线更新（UTF-8 **带 BOM**） |
| `release.ps1` | 一键发布 |
| `version.json` | 当前版本号 + 每个受管文件的 MD5 |
| `platform-tools/adb.exe` | adb |
| `scrcpy/` | 精简版 scrcpy 3.3.4（8 个文件，约 10MB） |
| `fsa/` | 本地 fsa 备用目录（见 §6.3） |

---

## 3. 运行 / 打包 / 发布

### 本地跑后端（改 server.py 时最快的调试方式）
```bat
python web\server.py
```
非 frozen 模式下 `BASE_DIR = web/`、`PROJECT_DIR = 项目根`，端口 8765。

### 只改前端
直接改 `web/index.html`，浏览器刷新即可，**不用重打包**。

### 改后端
`launcher.exe` 内嵌了 `server.py`，**只改 `web/server.py` 不重打包是无效的**（这正是历史上「普通安装没权限」的根因）。

```bat
build_launcher.bat
```
或手动：
```bat
python -m PyInstaller --onefile --name launcher ^
  --distpath build_tmp\dist --workpath build_tmp\build --specpath build_tmp ^
  --noconfirm web\server.py
```
打包前先 `taskkill /F /IM launcher.exe`，否则 exe 被占用、替换会失败。

### 发布（会 push + 建 Release，需要开 Clash）
```powershell
.\release.ps1 -Version 0.1.17 -Note "本次改了什么"
```
`release.ps1` 会：按需重建 exe → 写 `version.json`（含各文件 MD5）→ 在 `CHANGELOG.md` 顶部插一条 → `git commit/push` → `gh release create` → 刷 jsDelivr 缓存。

> `release.ps1` 里有优化：`web/server.py` 没变就**跳过重建 launcher.exe**（只改版本号）。

---

## 4. 在线更新机制

`updater.ps1` 的流程：

1. 从**多个镜像**拉 `version.json?t=<时间戳>`，取版本号最高的一份（防某个镜像缓存旧版）
   - 镜像：`ghfast.top`、`gh-proxy.com`、`cdn.jsdelivr.net`、`raw.githubusercontent.com`
   - **不要加回 `gcore.jsdelivr.net`**：它缓存过极旧的清单，曾导致客户端降级
2. 版本号只认语义化 `v主.次.补`；非语义化（如旧的 `202610131253`）一律当 `0.0.0`
3. 本地版本 ≥ 远端 → 已是最新，退出
4. 逐文件比对 MD5，只下载有变化的；每个文件逐个镜像试，**MD5 命中才采用**
5. 若目标是 `launcher.exe` 且正被运行中的进程占用 → 下载成 `launcher.exe.new`，退出码 **2**（deferred）
6. 全部成功且无 deferred 才写 `version.json`

前端 `doUpdate()` 收到 `deferred: true` 时会 `POST /api/restart`，由后台助手：杀掉 launcher → 等 `launcher.exe.new` 被替换 → 再跑一次 `updater.ps1` → 启动新 launcher。

---

## 5. 坑位（务必先读）

### 5.1 `launcher.exe` 内嵌 `server.py`
改后端必须重打包。判断当前 exe 是否含某段代码：直接搜 exe 里的字符串，或看 `web/server.py` 与 `version.json` 的 MD5 是否一致。

### 5.2 行尾符会毁掉 MD5 校验
`.gitattributes` 里是 **`* -text`**（关闭行尾自动转换），`core.autocrlf=false`。
原因：`release.ps1` 按**工作区字节**算 MD5，若 git 提交时把 CRLF 转成 LF，GitHub 发的就是 LF，MD5 永远对不上，更新会一直报「各镜像内容不一致」。

**不要删 `.gitattributes`，也不要改回 `core.autocrlf=true`。**

### 5.3 PyInstaller 的 `_PYI_*` 环境变量
frozen 进程会带 `_PYI_ARCHIVE_FILE` / `_PYI_APPLICATION_HOME_DIR` / `_PYI_PARENT_PROCESS_LEVEL` 等变量。子进程再启动 `launcher.exe` 时，bootloader 会误以为自己是被父进程拉起的子进程而**直接退出**（表现为「重启后没反应」）。

`/api/restart` 拉助手时已过滤：
```python
env = {k: v for k, v in os.environ.items()
       if not k.upper().startswith("_PYI") and k.upper() != "_MEIPASS2"}
```
**任何「由 exe 再启动 exe」的新代码都要做同样过滤。**

### 5.4 后台助手必须脱离父进程
直接 `subprocess.Popen` 的子进程会随服务进程退出被杀。当前用：
```python
subprocess.Popen(["cmd", "/c", "start", "", "/min", "powershell", ...],
                 creationflags=0x08000000, env=env, ...)
```

### 5.5 PowerShell / bat 编码
- `.ps1` 必须 **UTF-8 带 BOM**，否则 Windows PowerShell 5.1 解析中文会报 `MissingArrayIndexExpression`
- `.bat` 用 **GBK**，不要加 `chcp 65001`
- `version.json` 写盘用 `[IO.File]::WriteAllText($p, $json, (New-Object Text.UTF8Encoding $false))`（无 BOM）

### 5.6 镜像缓存
刚发布后几分钟内，各镜像可能仍返回旧 `version.json`。更新器已用 `?t=` 时间戳 + 取最高版本缓解，但**发布后立刻测更新可能还是旧版**，等几分钟即可。

### 5.7 单实例与端口
`main()` 启动时 `_free_port(8765)`：`netstat` 找监听该端口的 PID 并 `taskkill`，然后绑定。所以新实例会顶掉旧实例，不需要手动关。

### 5.8 `git` 索引的 stat 缓存
用脚本直接改文件后，`git status` 偶尔不认（尤其改完立刻 commit）。必要时 `touch` 文件再 `git add -f`。

---

## 6. 功能实现索引

### 6.1 安装
- `install_one()` —— **完整安装**：装包 → fsa → root → WiFi/蓝牙 → 授权 → 设桌面 → 启动 → 重启
- `plain_install_one()` —— **普通安装**：只 `adb install -r`（可选 `-g` + 授权），不卸载/不推 fsa/不 root
- `parse_apk_manifest()` / `parse_apk_permissions()` —— 纯 Python 解析二进制 `AndroidManifest.xml`（不依赖 aapt）
- 并行安装：每台设备一个线程跑 `install_one`

### 6.2 权限
`RUNTIME_PERMS`（14 个运行时权限）+ `APPOP_PERMS`（`MANAGE_EXTERNAL_STORAGE` / `SYSTEM_ALERT_WINDOW` / `POST_NOTIFICATION`）。
`grant_permissions(serial, pkg, declared)` 只对 **APK 清单里声明过的** 权限执行 `pm grant`；未声明的记 `[SKIP]` 而不是 `[FAIL]`（避免 `SecurityException` 刷屏）。
读写 `/sdcard` 在 Android 11+ 靠 `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`。

### 6.3 fsa 数据
优先级：**APK 内置 `assets/aikit/fsa/`** → 本地 `fsa/`（仅当 `.fsa_meta.json` 记录的归属 APK 与当前 APK 一致）→ 都没有则告警跳过。

**设备只认 `.txt`**：APK 解包、zip/文件夹上传、推送三处都会过滤非 txt 文件（扩展名大小写不敏感），并在面板/安装日志提示被忽略的文件。别放宽这条——混入其他格式会让 App 读取异常。

- `fsa_in_apk_report(apk)` / `extract_fsa_from_apk(apk)`：扫描 APK 内置 fsa，只解包 `.txt` 到 `.fsa_from_apk/<apk名>/fsa/`
- `fsa_bad_files(dir)`：列出目录内非 `.txt` 文件（`.meta` 除外）
- `push_fsa_dir(serial, fsa_src, log, step)`：源目录混入非 txt 时先过滤到临时目录（目录名保持 `fsa`）；再 `ls` 检查并 `rm -rf` 设备上的 `/sdcard/aikit/esr/fsa` 和 `/sdcard/aikit/fsa`（防污染）→ push → `find` 校验文件数一致
- `.fsa_meta.json` 记录本地 fsa 属于哪个 APK，防止把上个 APK 的 fsa 误用

### 6.4 scrcpy 投屏
`find_scrcpy()` 优先项目内 `scrcpy/scrcpy.exe`（否则 PATH 里可能命中别的版本）；`launch_scrcpy()` 用项目自带 adb（`env["ADB"]`），`DETACHED_PROCESS` 启动。

### 6.5 更新
`read_local_version()` / `fetch_remote_version()` / `start_update()` / `_update_worker()`；
路由：`GET /api/version`、`GET /api/check_update`、`POST /api/update`（异步，立即返回 `started`）、`GET /api/update_status`（进度 + 日志）、`POST /api/restart`。

---

## 7. 代码约定

- 后端只用标准库，**不引入第三方依赖**
- 前端单文件，不引框架、不引 CDN
- 新增文件若要参与在线更新，必须在 `release.ps1` 的 `files` 列表里登记，否则客户端拿不到
- 中文注释、日志前缀统一：`[WARN]` / `[SKIP]` / `[FAIL]`
- 改动 `web/server.py` 后至少跑一遍 `python -m py_compile web/server.py`

---

## 8. 常见问题速查

| 现象 | 原因 |
|---|---|
| 改了后端没生效 | 没重打包 `launcher.exe` |
| 更新报「各镜像内容不一致」 | 行尾符 / MD5 不一致（见 §5.2） |
| 更新完提示「已下载，重启后生效」但没重启 | `_PYI_*` 环境变量（见 §5.3） |
| 检查更新看不到新版本 | 镜像缓存（见 §5.6） |
| 装完没有读写权限 | APK 未声明权限，或 `MANAGE_EXTERNAL_STORAGE` 未 allow |
| 装的是旧 APK | 下拉框选错 / 同名文件被覆盖 |
| 普通安装没推 fsa | 普通安装**本来就不推** fsa，用「并行安装」 |
