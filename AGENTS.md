# AGENTS.md — 沈阳故宫 ADB 批量安装工具

给后续维护者（人 / AI）看的项目说明。**动手改之前先读「5. 坑位」**，那里每一条都是实际踩过的雷。

---

## 1. 这是什么

Windows 上的 **ADB 批量安装工具**：

- 后端：`web/server.py`（标准库 `http.server`），打包成 `launcher.exe`（PyInstaller onefile）
- 前端：`web/index.html`（单页，浏览器打开 `http://127.0.0.1:8765/`）
- 启动：双击 `launcher.exe`（或 `启动.bat`）→ 隐藏控制台 + 打开浏览器；启动后自己检查更新

功能：批量装 APK、注入 fsa 数据、配 WiFi、采集 MAC/序列号、抓 deviceId、批量重启/关机、scrcpy 投屏、蓝牙配对、ADB 终端、日志查看。

仓库：<https://github.com/qq398768650/sygg-adb-tool>（public，`main`）

---

## 2. 目录与关键文件

| 路径 | 作用 |
|---|---|
| `web/server.py` | **后端全部逻辑**：HTTP 路由、adb 调用、APK 解析、fsa 注入、更新器调用 |
| `web/index.html` | **前端单页**：所有 UI + JS。运行时从磁盘读，改完刷新即生效 |
| `launcher.exe` | PyInstaller onefile，**内嵌 `web/server.py`** |
| `启动.bat` | 可选启动入口（GBK 编码）：只启动 `launcher.exe`，等价于直接双击 exe |
| `web/updater.py` | 在线更新（纯 Python，打进 launcher.exe；也负责替换自身并拉起新版） |
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

`web/updater.py` 的流程：

1. 从**多个镜像**拉 `version.json?t=<时间戳>`，取版本号最高的一份（防某个镜像缓存旧版）
   - 镜像：`ghfast.top`、`gh-proxy.com`、`cdn.jsdelivr.net`、`raw.githubusercontent.com`
   - **不要加回 `gcore.jsdelivr.net`**：它缓存过极旧的清单，曾导致客户端降级
   - `web/server.py` 的 `fetch_remote_version()` 同样必须取最高版本，**不能只认第一个可用的镜像**：
     曾因 ghfast 缓存旧清单，出现「发现新版本 v1.0.1（当前 v1.0.1）」的误报
2. 版本号只认语义化 `v主.次.补`；非语义化（如旧的 `202610131253`）一律当 `0.0.0`
3. 本地版本 ≥ 远端 → 已是最新，退出
4. 逐文件比对 MD5，只下载有变化的；每个文件逐个镜像试，**MD5 命中才采用**
5. 但 `launcher.exe` 排在**最后**替换：中途失败时其余文件是齐的，不会出现半新半旧
6. 全部到位后才写 `version.json`（避免版本号虚高，下次误判「已是最新」）

### 4.1 自更新怎么绕开「exe 不能替换自己」

Windows 不允许进程覆盖自己正在运行的 exe。所以流程是「让位」而不是「硬换」：

1. 网页点更新 → `POST /api/update` → `start_self_update()` 把**当前 exe 复制到 `%TEMP%`**
   （`sygg_updater_upd_<pid>.exe`），以 `--update --root <项目根>` 启动副本
2. 主进程 `threading.Timer(1.0, os._exit(0))` 退出，8765 和 `launcher.exe` 都被放开
3. 副本（`server.py` 的 `--update` 分支 → `updater.main()`）下载、校验、直接覆盖原文件
4. 不管成功失败都 `launch_main()` 把 `launcher.exe` 拉回来——主进程已经死了，不拉起用户就看不到界面
5. `cleanup_old_copies()` 在下次运行时删掉 `%TEMP%` 里的旧副本（当前这个自己在跑，删不掉）

前端 `doUpdate()` 看到响应里的 `self: true` 就走 `watchSelfUpdate()`：每秒试 `api('version')`，
服务回来就读 `.update.progress.json` 判断 `phase` 是 `done` 还是 `error`，然后刷新页面。

源码模式（`python web/server.py`）不复制 exe，直接 `sys.executable web/updater.py --root <项目根>`。

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
`RUNTIME_PERMS`（14 个运行时权限）+ `APPOP_PERMS`（`MANAGE_EXTERNAL_STORAGE` / `SYSTEM_ALERT_WINDOW` / `POST_NOTIFICATION` / `REQUEST_INSTALL_PACKAGES`）。
`grant_permissions(serial, pkg, declared)` 只对 **APK 清单里声明过的** 权限执行 `pm grant`；未声明的记 `[SKIP]` 而不是 `[FAIL]`（避免 `SecurityException` 刷屏）。
读写 `/sdcard` 在 Android 11+ 靠 `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`。
`REQUEST_INSTALL_PACKAGES` 就是系统设置里的「安装未知应用」，不开的话 App 内自更新/差分更新装不进去。

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
`read_local_version()` / `fetch_remote_version()` / `start_self_update()` / `_updater_cmd()`；
路由：`GET /api/version`、`GET /api/check_update`、`POST /api/update`（异步，立即返回 `started`）、`GET /api/update_status`（进度 + 日志）、`POST /api/restart`。

### 6.6 无线调试（adb over TCP）
`device_ip(serial)` / `wifi_debug_enable(serial, port=5555, keep=True)` / `wifi_debug_disable(serial)`；
路由：`POST /api/wifi_debug`、`POST /api/wifi_debug_off`。开启流程 = 读 IP → `adb root` → 可选写
`persist.adb.tcp.port`（重启后 adbd 自动监听）→ `adb tcpip 5555` → `adb connect <ip>:5555` → 校验状态为 `device`。

- 无线设备在 `adb devices` 里的 serial 是 `<ip>:5555`：按 serial 的现有流程（安装/投屏/日志）都能直接用，
  但做 MAC、序列号、去重对比时要先剥掉 `:5555`
- 前提是电脑和设备在同一 WiFi 网段，且 AP 没开客户端隔离；设备需能 `adb root` 才能写持久化属性
- 设备卡片上的「无线」按钮：USB 设备=开启，带「无线」标签的设备=断开并切回 USB

### 6.7 WiFi 注入（预置网络）
`wifi_inject(serial, ssid, password, hidden=False)`；路由 `POST /api/wifi_inject` `{serials?, ssid, pass, hidden?}`。

用途：**设备当前不在该 WiFi 范围内也能把配置写进设备**，搬到覆盖区域后自动连接。
原理：`cmd wifi connect-network <ssid> wpa2|open [pass] [-m]` 会先把网络写入 `WifiConfigStore`
再尝试连接，所以「连接失败」不代表「没有保存」。返回值里 `saved`（配置已入库）与 `connected`（当前已连上）分开：
`cmd wifi list-networks` 里能查到 SSID 即 `saved=true`，`cmd wifi status` 显示连的就是它才算 `connected`。

- 实测（Android 11 / rk3566_r）：注入不存在的 SSID → 输出 `Connection initiated`、rc=0，
  `list-networks` 立刻出现该网络；当前连接会短暂断开，十几秒后自动回到原网络
- 已存在同名网络时再次注入 = 更新密码并重连，不需要先 forget
- 前端在 ② WiFi 区块的「注入WiFi」按钮，对**全部在线设备**注入 `#ssid` / `#pass` 里的值

### 6.8 设备端「设备更新助手」App（脱离电脑更新）
目录 `devupdate-app/`，包名 `com.hsy.devupdate`，产物 `devupdate-app/DevUpdate.apk`（约 37 KB）。
场景：出差人员在酒店，没电脑，用遥控器让设备自己连 WiFi、从景区发布后台下载并安装最新包。

- **构建**（本机没有 gradle / Android Studio）：`bash devupdate-app/build.sh`
  - JDK 用 `H:\GitWork2026\Tools\jdk17\jdk-17.0.21.1+2`
  - build-tools 必须用 **36.0.0**（30.0.3 的 `d8.bat` / `apksigner.bat` 带 `-Djava.ext.dirs`，JDK 17 上直接报 `Could not create the Java Virtual Machine`）
  - 流程：`aapt2 compile/link` → `javac` → `d8` → `jar uf` → `zipalign` → `apksigner`；签名 keystore `devupdate-app/devupdate.jks`（`123456` / alias `devupdate`），首次自动生成
- **图标**用代码画，不依赖生图服务：`devupdate-app/tools/MakeIcon.java`（Java2D）
  `javac -encoding UTF-8 -d tools tools/MakeIcon.java && java -cp tools MakeIcon res`
  → 输出 `res/mipmap-{m,h,x,xh,xxh,xxxh}dpi/` 下的 `ic_launcher.png`（完整圆角图标）、`ic_launcher_fg.png` / `ic_launcher_bg.png`（自适应图标两层，108dp 画布，前景内容占 66%）。
  图形：紫色渐变底（#7C3AED→#4C1D95→#1E1B4B）+ 白色中式飞檐 + 向下箭头 + U 形托盘。
  自适应图标声明在 `res/mipmap-anydpi-v26/ic_launcher.xml`；`AndroidManifest.xml` 的 `android:icon="@mipmap/ic_launcher"`。
  **改图形只改 MakeIcon 里的坐标重跑，不要手改 PNG**（多密度会不同步）。
- **界面是 D-pad 导航**：设备没有触摸屏，只有 `adc-keys`（上下左右 + KEY_ENTER）和 `gpio-keys`（BACK / 音量）。
  列表项高亮用 `res/drawable/item_bg.xml` 的 **`state_selected`**（用 `state_focused` 不会亮）。
- **连 WiFi**：扫描（`startScan` + SCAN_RESULTS，需 ACCESS_FINE_LOCATION）→ 选热点 → D-pad 软键盘输密码 →
  `WifiNetworkSuggestion` + `addNetworkSuggestions`（status 0 成功、3 重复也算成功）→ `disconnect()` 促使重选 → 轮询 `getConnectionInfo()`。
  首次会弹系统「是否允许连接到建议的 WLAN」，用遥控器按确认即可，之后不再弹。
- **检查更新**：内置项目清单 `UpdateActivity.PROJECTS`（13 个 key，**与 Unity 工程 `D:\UnityWork2026\MRShenYang\Assets\Editor\ScenicProjectMap.json` 同步**；新增景区必须同时改这里并重新打 APK，否则列表里看不到——CDN 不支持列举目录，没有项目清单接口），直接读公开 CDN（**不需要 api_key**）
  `http://cdn2.vr.hoseavr.com/arasset/{proj}/index.json` → `currentVersionCode` + 对应版本的 packageName；
  `{proj}/v{vc}/meta.json` → `.apk` 文件名与大小 → `{CDN}{proj}/v{vc}/{apkName}`。
  本机版本用 `PackageManager.getPackageInfo`，需要 **`QUERY_ALL_PACKAGES`** 权限，否则 Android 11 包可见性会让别的 App 一律显示「未安装」。
- **安装**：`PackageInstaller` Session（`MODE_FULL_INSTALL`）→ `commit(PendingIntent)`。
  **DO 被 `com.hsy.sygg` 占用（non-testOnly，Android 11 无法移除）**，所以新包拿不到静默安装：
  回调收到 `STATUS_PENDING_USER_ACTION (-1)` 时必须自己 `startActivity(intent.getParcelableExtra(Intent.EXTRA_INTENT))` 才会弹确认框，遥控器按「安装」。
  另外要在系统里允许「安装未知应用」：`adb shell appops set com.hsy.devupdate REQUEST_INSTALL_PACKAGES allow`。
- 其他权限：INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / RECEIVE_BOOT_COMPLETED；`usesCleartextTraffic="true"`（CDN 是 http）。
- 实测（DR301V010320250726000496 / rk3566_r / Android 11）：扫描 14 个热点 → 输密码连上 Xiaomi_HSY → 列表拉到 10 个项目（5 个有发布记录，5 个 404 显示「后台暂无发布记录」）→ 云冈石窟 324 MB 下载 → 弹安装框 → 装上 `com.hsy.ygsk` → 列表自动刷新成「本机 v32 · 最新 v32 ✅ 已最新」。
- 列表排序：可更新的排最前，其次「已最新」，最后是后台还没发布过的项目（`rank()` + `sortItems()`）。
- 点「已最新」的项目会弹「本机已是最新 vX，要现在打开它吗？」，按「打开」用 `getLaunchIntentForPackage` 直接启动该 App（现场看效果不用再找图标）。
- **可作为默认桌面（HOME）**：`MainActivity` 的 intent-filter 同时声明 `LAUNCHER` + `HOME` + `DEFAULT`；主菜单第 3 项「默认桌面」走 `RoleManager.createRequestRoleIntent(ROLE_HOME)` 弹系统确认框（遥控器上下 + 确认就能操作）。
  已验证：切换后 `dumpsys role` 显示 `android.app.role.HOME holders=com.hsy.devupdate`，`cmd package resolve-activity -c android.intent.category.HOME` 返回 devupdate；切回用 `pm set-home-activity <包名>/<Activity>`。
  用途：现场业务 App 崩了 / 没装时，把本程序设为桌面，开机就有入口能连 WiFi、拉新包。
- 主页「连接 WiFi」的副标题会显示当前网络（`MainActivity.wifiSub()`）：连上时「已连接 Xiaomi_HSY」，否则「扫描附近热点并输入密码」。
  `onResume()` 每次都重算，从 WiFi 页回来就更新。SSID 在 Android 10+ 要定位权限，进主页时会申请一次；没权限时降级成「已连接 WiFi（看不到名称，检查定位权限）」。
- **密码输错不能卡死**：`PassActivity` 连接中会禁用并变灰键盘，主按钮变「✖ 取消」（底部按钮排第一个），
  BACK 随时取消；轮询 20 次 × 2 秒 = **40 秒**仍没连上就提示「40 秒内没连上。可能密码不对、不在范围内，或热点有客户端隔离」并恢复可编辑。
  另外如果目标 SSID 就是当前已连网络，直接提示「当前已经连在…上」——否则输错密码也会被误判成功（旧 SSID 没变）。
- **扫码连接 WiFi**：WiFi 页 header 的「扫码连接」→ `ScanActivity`，相机预览 + ZXing 解 `WIFI:T:WPA;S:ssid;P:pass;;` 二维码，识别到直接走 `WifiConnect`（与手输密码共用同一套连接逻辑，40 秒超时、BACK 可取消）。
  - 依赖 `devupdate-app/libs/core-3.5.3.jar`（ZXing core）。**build.sh 里 `javac -classpath` 和 `d8` 的输入都要带上它**，只加 javac 会编过但运行时 `NoClassDefFoundError`。
  - 取景框是自绘的 `ScanOverlay`：中间正方形亮窗（边长 = 屏幕短边 × `FRAME_RATIO 0.56`）、四周 `0xB0000000` 压暗、四角白色 L 线。
    **解码只取框内 ROI**（`updateRoi()` 把控件坐标按 `frameW/viewW` 换算成预览帧坐标），框内没有码就什么都不做，跟常见扫码 App 的行为一致。
    `activity_scan.xml` 里 `ScanOverlay` 必须放在 `SurfaceView` 之后、标题/状态 `TextView` 之前，否则遮罩会盖住文字。
  - 相机三个坑：① `Camera.open()` 要 1~3 秒，**必须放后台线程**（主线程会 ANR，`WifiActivity ... Waited 5000ms for FocusEvent`）；
    ② 预览分辨率按 `{1280*720, 960*540, 640*480, 320*240}` 优先选，选 1600x1200 时外接摄像头只有 **0.097 FPS**，选 1280x720 才 31.7 FPS；
    ③ `setDisplayOrientation(0)`，设 180 画面会倒。
  - 连接失败会 `handled = false` 并重开相机，用户不用退出重进。
  - 实测：相机 1280x720 / 31.7 FPS 正常取景，框内对准二维码能识别并进入「识别到 X，正在连接…」。
- **踩过的坑**：① 后台线程直接改 UI 会抛 `Only the original thread that created a view hierarchy can touch its views`，所有 `setStatus` / `progress` 必须 `ui.post`；② 下载中断后重试要先比对文件大小，完整则跳过重下。

#### 6.8.1 差分更新（只下变化的块）

- 算法与 Unity 端 `D:\UnityWork2026\MRShenYang\Assets\Scripts\JS\Update\FastCdc.cs` / `PatchAssembler.cs` **必须逐字节一致**，任何常量改动都会让命中率归零：
  `MinSize=16*1024`、`AvgSize=64*1024`、`MaxSize=128*1024`、`MaskSBits=18`、`MaskLBits=14`；gear 表用 splitmix64（`x` 初值 `0x9E3779B97F4A7C15`，每轮先 `x += 0x9E3779B97F4A7C15`）。
- 客户端实现：`FastCdc.java`（流式 `Chunker`，`BlockSink.onBlock(pos, data, off, len)`）、`XxHash64.java`、
  `PatchBuilder.java`（切旧包建「指纹→偏移」索引 → 命中就从旧包拷、未命中记 pending → 合并相邻缺口 → HTTP Range 下载 → sha256 校验）。
- patch 清单 `{proj}/v{vc}/{pkg}_patch-{vc}.json` 格式：`{"algorithm":"fastcdc-v1","newSize":…,"newSha256":…,"blocks":[{"o":0,"l":64466,"h":"c9d69e73812b2553"},…]}`，不含 baseVersionCode（任何旧包都能试匹配）。
- 流程：`meta.json` 里同时有 `.apk` 和名字含 `patch` 的 `.json`，且 `installedApk(pkg)`（`applicationInfo.publicSourceDir`）可读，就先走差分；失败自动回退整包。
  普通 App 能读别的 App 的 `base.apk`（权限 644、目录 775），不需要 `debuggable` / `run-as`。
- 状态行会显示「差分完成：复用 N 块，实际下载 X（整包 Y）」，安装完成后仍保留这句话。
- **坑（花了不少时间）**：`XxHash64` 的五个常量在 Java 里如果写成十进制负数极易抄错 —— P1 曾写成 `-7046029254386353131L`
  （正确是 `-7046029254386353125L`，差 6），表现为「切块边界全对、hash 全不匹配、命中 0」。**一律写十六进制字面量**：
  `0x9E3779B185EBCA87L / 0xC2B2AE3D27D4EB4FL / 0x165667B19E3779F9L / 0x85EBCA77C2B2AE63L / 0x27D4EB2F165667C5L`。
  自检办法：`xxh64("")=ef46db3751d8e999`、`xxh64("a")=d24ec4f1a98c6e5b`、`xxh64("abc")=44bc2cf5ad770999`。
- 实测（2026-11-24，宋潮不夜城 v35→v36，整包 720,181,102 字节）：本地复用 **10447 块**，只下载 **23,279,708 字节（22.2 MB，约 3.2%）**，22 秒完成合成，sha256 通过并正常安装到 v36；榆次县衙 v1→v2（400 MB）同样成功。

### 6.8.2 安装包更新助手自更新（点「关于」一键升级）

- 版本号在 `devupdate-app/AndroidManifest.xml` 的 `android:versionCode` / `versionName`，**每次改代码都要 +1**，否则设备看不出有新版。
- `build.sh` 末尾用 manifest 版本号 + APK 大小 + sha256 生成 `devupdate-app/devupdate.json`。
- APK、清单、源码都进仓库（`.gitignore` 里 `*.apk` 对 `devupdate-app/DevUpdate.apk` 开了例外），设备走 **jsDelivr** 拉：
  `https://cdn.jsdelivr.net/gh/qq398768650/sygg-adb-tool@main/devupdate-app/`
- 发新版流程：改版本号 → `bash devupdate-app/build.sh` → `git add -f devupdate-app/DevUpdate.apk devupdate-app/devupdate.json` → commit/push → 刷缓存
  `curl "https://purge.jsdelivr.net/gh/qq398768650/sygg-adb-tool@main/devupdate-app/devupdate.json"`（APK 同理）。不发 launcher 版本也行。
- App 侧：启动 `autoCheckUpdate(false)` 静默查 → 有新版本**直接后台下载**（不弹框）→ 下完「关于」变成「发现新版本 vX」+ 红色「新版本」气泡（`res/layout/item_menu.xml` 的 `it_badge`）→ 点一下直接 `installSelf()`。
- 下载：断点续传（`Range` + 206）、多镜像兜底、sha256 校验、`readTimeout` 120s；状态存在 `self_update/DevUpdate.apk`。
- **镜像坑**：现场这台设备的网络把 `gh-proxy.com` DNS 到 127.0.0.1（连不上），`raw.githubusercontent.com` 也不通，只有 jsDelivr 稳，所以 jsDelivr 排第一。
- **D-pad 坑**：系统安装框默认焦点在「取消」，必须**按右键**再按确认才是「安装」，否则回调 `INSTALL_FAILED_ABORTED: User rejected permissions`（status=3）。
- `release.ps1` 发版时也会把 APK/json 传到 Release 资产（兜底镜像 `releases/latest/download/`）。

### 6.8.3 一键授权（装完自动补权限）

设备不是 Device Owner（DO 被业务包占着），`PackageInstaller` 装完的包**运行时权限全是未授予**，
而设备只有遥控器方向键，逐个点系统权限弹框很难操作。做法是让 App 用「本地 adbd」的身份去授权：

- `AdbLocal.java`：极简 ADB 客户端，socket 连 `127.0.0.1:5555` → 发 `CNXN`（`host::\0`）→ `OPEN shell:命令` → 收 `WRTE`。
  设备 `ro.adb.secure` 未设置（不认证），不需要密钥；收到 `AUTH` 包就返回 `null` 表示不可用。
- `Grants.java`：`scenicPackages()` 用 `pm list packages` 取 `com.hsy.*`；`grant(ctx, pkg)` 读 `PackageInfo.requestedPermissions`，
  只对 `PROTECTION_DANGEROUS` 的权限拼 `pm grant`，再补
  `appops set … MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES / SYSTEM_ALERT_WINDOW / POST_NOTIFICATION allow`。
- 入口：主菜单「一键授权」（`MENU_GRANT`，`MainActivity.oneKeyGrant()` → 后台线程 → 结果弹框「已授权 N/M 个应用」）；
  `UpdateActivity` 在 `PackageInstaller.STATUS_SUCCESS` 后自动对刚装的包跑一次，状态行追加「权限已自动授予」。
- **前提**：设备 5555 有 adbd 在听。`ensure_local_adb_port()`（`web/server.py`，在 `install_one` / `plain_install_one`
  安装成功后调用）写 `persist.adb.tcp.port=5555`，重启后自动生效；要立刻生效就在设备卡片点「无线」（`adb tcpip 5555`）。
- **坑**：`AdbLocal.available()` 是网络操作，**不能在主线程调**（`NetworkOnMainThreadException` 直接把 App 崩掉），
  探测要放后台线程再回 UI 提示。
- 实测（DR301V010320250726001506）：`pm revoke` 掉西递的 CAMERA / RECORD_AUDIO / 位置后点「一键授权」→
  「已授权 2 / 2 个应用」，`dumpsys package com.hsy.xidi` 里四个权限全部 `granted=true`。
- 列表排序（`sortItems()`）：**已安装的按 `PackageInfo.lastUpdateTime` 倒序排最前**（刚装完的排第一），未安装的按「可更新 → 无发布」再按内置顺序；
  本机版本高于后台（例如沈阳故宫本机 v54 / 后台 v41）显示「⬆ 本机版本更新」，不再误标「已最新」。
- ⚠️ **安全**：`persist.adb.tcp.port=5555` + `ro.adb.secure` 不认证 = 同网段任何人都能 `adb connect` 拿到 shell
  （可装应用、读存储）。现场设备必须放在可信内网；要暴露到外网就得关掉这个属性，或用 iptables 只放行 `lo`。

---

### 6.9 单独设置 Device Owner（不重装）

设备卡片里应用下拉旁边的**绿色锁按钮** = 把下拉里选中的包设为 Device Owner（App 内静默更新的前提）。

- 路由：`POST /api/set_device_owner {serial|serials, pkg, apk?}` → `set_device_owner_pkg()`，返回 `{ok, pkg, total, ok_count, results{serial:{ok, admin, owner, error, log[]}}}`。
- admin 组件来源（`device_admin_component()`）：本地 APK（传了 `apk` 时）→ 设备的 base.apk（`pm path` + `exec-out unzip -p <apk> AndroidManifest.xml`，由 `adb_manifest_bytes()` + `parse_axml_device_admin()` 处理，**不整包拉回电脑**）→ 兜底 `<pkg>.ApkDeviceAdmin`。
- 一台设备只能有一个 DO：已被别的包占用时直接返回错误，提示先点卡片上的「解锁」（`clear_device_owner()` 会硬重启）。
- **坑**：部分景区包是从同一模板复制的，manifest 里 receiver 类名还写着 `com.hsy.sygg.ApkDeviceAdmin`。云冈石窟 `com.hsy.ygsk` 就是这样 —— 解析出来不是本包前缀**是正常的**，`dpm set-device-owner com.hsy.ygsk/com.hsy.sygg.ApkDeviceAdmin` 依然有效，别当成解析 bug 去"修"。

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
| 更新后服务没回来 | 更新器没拉起 exe，看项目目录 `.update.log`；或 `_PYI_*` 变量没清（见 §5.3） |
| 检查更新看不到新版本 | 镜像缓存（见 §5.6） |
| 提示「发现新版本 vX（当前 vX）」 | 某个镜像缓存了旧清单；`fetch_remote_version()` 已改为取多镜像最高版本（1.0.3 起） |
| 装完没有读写权限 | APK 未声明权限，或 `MANAGE_EXTERNAL_STORAGE` 未 allow |
| 装的是旧 APK | 下拉框选错 / 同名文件被覆盖 |
| 普通安装没推 fsa | 普通安装**本来就不推** fsa，用「并行安装」 |
