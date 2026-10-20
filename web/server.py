# -*- coding: utf-8 -*-
"""
批量 ADB 安装工具 (Web 版)
Python 后端: HTTP 服务 + 真并行 adb 安装 + 实时状态

运行:  python server.py   (或双击 启动.bat)
前端:  http://127.0.0.1:8765/
"""
import os
import io
import sys
import re
import time
import json
import shlex
import shutil
import struct
import zipfile
import urllib.parse
import urllib.request
import hashlib
import subprocess
import tempfile
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if getattr(sys, "frozen", False):
    # PyInstaller 打包后：exe 所在目录 = 项目根目录（APK 在此）
    _EXE_DIR = os.path.dirname(sys.executable)
    _WEB = os.path.join(_EXE_DIR, "web")
    # 优先用 exe 同级的 web 目录（可写、可更新）；不存在则回退到打包内置
    BASE_DIR = _WEB if os.path.isdir(_WEB) else os.path.join(getattr(sys, "_MEIPASS", _EXE_DIR), "web")
    PROJECT_DIR = _EXE_DIR
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    PROJECT_DIR = os.path.dirname(BASE_DIR)

HOST = "127.0.0.1"
PORT = 8765

# ---------- 版本 / 在线更新 ----------
VERSION_FILE = os.path.join(PROJECT_DIR, "version.json")
UPDATE_REPO = "qq398768650/sygg-adb-tool"
UPDATE_BRANCH = "main"
# 多个下载源，逐个尝试（GitHub 直连在国内常被墙，用镜像兜底）
UPDATE_MIRRORS = [
    "https://ghfast.top/https://raw.githubusercontent.com/%s/%s" % (UPDATE_REPO, UPDATE_BRANCH),
    "https://gh-proxy.com/https://raw.githubusercontent.com/%s/%s" % (UPDATE_REPO, UPDATE_BRANCH),
    "https://cdn.jsdelivr.net/gh/%s@%s" % (UPDATE_REPO, UPDATE_BRANCH),
    "https://raw.githubusercontent.com/%s/%s" % (UPDATE_REPO, UPDATE_BRANCH),
]


def _md5_file(path):
    h = hashlib.md5()
    try:
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    except Exception:
        return ""


def read_local_version():
    try:
        with open(VERSION_FILE, "r", encoding="utf-8-sig") as f:
            d = json.load(f)
            return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def ver_tuple(v):
    """把 'v0.1.5' / '0.1.5' 这类语义化版本转成可比较的元组 (0,1,5)。
    只认「主.次.修订」；其它（例如旧的时间戳版本号）一律当作 (0,0,0)，
    避免某个镜像缓存了旧版 version.json 时被误判成最高版本。"""
    m = re.match(r"^v?(\d+)\.(\d+)\.(\d+)", str(v).strip())
    if not m:
        return (0, 0, 0)
    return tuple(int(x) for x in m.groups())


def fetch_remote_version(timeout=20):
    """用 Windows 自带 curl.exe 依次尝试各镜像拉取远端 version.json。"""
    import tempfile
    tmp = os.path.join(tempfile.gettempdir(), "sygg_adb_version.json")
    curl = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "curl.exe")
    if not os.path.isfile(curl):
        curl = "curl.exe"
    last_err = "网络不可用"
    ts = int(time.time())   # 时间戳绕过镜像缓存（刚发布时镜像可能还缓存着旧版）
    for base in UPDATE_MIRRORS:
        try:
            os.remove(tmp)
        except OSError:
            pass
        try:
            subprocess.run([curl, "-fsSL", "--connect-timeout", "5", "--max-time", str(timeout),
                            "-o", tmp, base + "/version.json?t=%d" % ts],
                           capture_output=True, timeout=timeout + 10)
        except Exception as e:
            last_err = str(e)
            continue
        if os.path.isfile(tmp) and os.path.getsize(tmp) > 0:
            try:
                with open(tmp, "r", encoding="utf-8-sig") as f:
                    return json.load(f)
            except Exception as e:
                last_err = str(e)
        else:
            last_err = "镜像无响应"
    raise RuntimeError("无法获取远端版本：%s" % last_err)


# ---------- 在线更新任务（后台跑 updater.ps1，前端轮询进度） ----------
UPDATE_PROGRESS_FILE = os.path.join(PROJECT_DIR, ".update.progress.json")
UPDATE_LOG_FILE = os.path.join(PROJECT_DIR, ".update.log")
UPDATE_STATE = {"running": False, "done": True, "ok": False, "rc": None,
                "deferred": False, "error": "", "started": 0.0}


def _read_update_progress():
    """读取 updater.ps1 写出的进度 JSON（不存在/写了一半时返回 None）。"""
    try:
        with open(UPDATE_PROGRESS_FILE, "r", encoding="utf-8-sig") as f:
            return json.load(f)
    except Exception:
        return None


def _read_update_log(limit=6000):
    try:
        with open(UPDATE_LOG_FILE, "r", encoding="utf-8-sig", errors="replace") as f:
            return f.read()[-limit:]
    except Exception:
        return ""


def _update_worker():
    script = os.path.join(PROJECT_DIR, "updater.ps1")
    rc = -1
    err = ""
    try:
        p = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script],
            cwd=PROJECT_DIR, capture_output=True, timeout=1800,
        )
        rc = p.returncode
    except Exception as e:
        err = str(e)
    with LOCK:
        UPDATE_STATE.update({"running": False, "done": True, "rc": rc,
                             "ok": rc == 0, "deferred": rc == 2, "error": err})


def start_update():
    """后台启动 updater.ps1；返回 False 表示已有任务在跑。

    updater.ps1 的退出码约定：0=成功/已最新，1=部分失败，2=主程序已下载但需重启才生效。
    """
    script = os.path.join(PROJECT_DIR, "updater.ps1")
    with LOCK:
        if UPDATE_STATE["running"]:
            return False
        if not os.path.isfile(script):
            UPDATE_STATE.update({"running": False, "done": True, "ok": False, "rc": -1,
                                 "deferred": False, "error": "未找到 updater.ps1（请在项目根目录运行）"})
            return True
        UPDATE_STATE.update({"running": True, "done": False, "ok": False, "rc": None,
                             "deferred": False, "error": "", "started": time.time()})
    for p in (UPDATE_PROGRESS_FILE, UPDATE_LOG_FILE):
        try:
            os.remove(p)
        except OSError:
            pass
    threading.Thread(target=_update_worker, daemon=True).start()
    return True

# ---------- APK 包名 / 启动 Activity 解析（纯 Python 解析二进制 AndroidManifest.xml，不依赖 aapt） ----------
def _axml_strings(data, base):
    """解析 AXML 字符串池 chunk，返回字符串列表；失败返回 None。"""
    t, hs, _sz = struct.unpack_from("<HHI", data, base)
    if t != 0x0001:
        return None
    str_count, _style_count, flags, strings_start, _styles_start = \
        struct.unpack_from("<IIIII", data, base + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % str_count, data, base + hs)
    out = []
    for off in offsets:
        p = base + strings_start + off
        try:
            if utf8:
                n = data[p]
                if n & 0x80:
                    n = ((n & 0x7F) << 8) | data[p + 1]
                    p += 2
                else:
                    p += 1
                m = data[p]                      # 第二个长度 = UTF-8 字节数
                if m & 0x80:
                    m = ((m & 0x7F) << 8) | data[p + 1]
                    p += 2
                else:
                    p += 1
                out.append(data[p:p + m].decode("utf-8", "replace"))
            else:
                n = struct.unpack_from("<H", data, p)[0]
                if n & 0x8000:
                    n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p + 2)[0]
                    p += 4
                else:
                    p += 2
                out.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
        except Exception:
            out.append("")
    return out


def _axml_element(data, pos, strings):
    """解析一个 START_ELEMENT chunk，返回 (元素名, {属性名: 值})。
    注：元素 chunk 的 headerSize 通常为 16（Chunk头 8 + lineNumber 4 + comment 4），
    attributeStart 是相对 attrExt 结构（即 pos + headerSize）的偏移。"""
    _t, hs, _sz = struct.unpack_from("<HHI", data, pos)
    name_idx = struct.unpack_from("<I", data, pos + 20)[0]
    attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + 24)
    name = strings[name_idx] if name_idx < len(strings) else ""
    attrs = {}
    apos = pos + hs + attr_start
    for _ in range(attr_count):
        a_ns, a_name, a_raw = struct.unpack_from("<III", data, apos)
        _v_size, _v_res0, v_type, v_data = struct.unpack_from("<HBBI", data, apos + 12)
        key = strings[a_name] if a_name < len(strings) else ""
        if a_raw != 0xFFFFFFFF and a_raw < len(strings):
            val = strings[a_raw]
        elif v_type == 0x03 and v_data < len(strings):
            val = strings[v_data]
        elif v_type == 0x10:
            val = str(v_data)
        elif v_type == 0x12:
            val = "true" if v_data else "false"
        else:
            val = ""
        attrs[key] = val
        apos += attr_size
    return name, attrs


def parse_apk_manifest(apk_path):
    """从 APK 里读出 (包名, 启动 Activity)。失败返回 (None, None)。
    处理 activity / activity-alias，识别带 MAIN + LAUNCHER 的那个。"""
    try:
        with zipfile.ZipFile(apk_path) as z:
            data = z.read("AndroidManifest.xml")
    except Exception:
        return None, None
    if len(data) < 8:
        return None, None
    try:
        strings = None
        pkg = None
        launcher = None
        alias_act = None
        stack = []
        pos = 8
        while pos + 8 <= len(data):
            t, _hs, sz = struct.unpack_from("<HHI", data, pos)
            if sz <= 0:
                break
            if t == 0x0001:
                strings = _axml_strings(data, pos)
            elif t == 0x0102 and strings:
                name, attrs = _axml_element(data, pos, strings)
                node = {"name": name, "attrs": attrs, "main": False,
                        "cat_launcher": False, "has_launcher": False}
                if name == "action" and attrs.get("name") == "android.intent.action.MAIN" and stack:
                    stack[-1]["main"] = True
                elif name == "category" and attrs.get("name") == "android.intent.category.LAUNCHER" and stack:
                    stack[-1]["cat_launcher"] = True
                stack.append(node)
            elif t == 0x0103 and stack:
                node = stack.pop()
                if node["name"] == "intent-filter" and node["main"] and node["cat_launcher"] and stack:
                    stack[-1]["has_launcher"] = True
                elif node["name"] == "manifest":
                    pkg = node["attrs"].get("package") or pkg
                elif node["name"] == "activity" and node["has_launcher"] and not launcher:
                    launcher = node["attrs"].get("name")
                elif node["name"] == "activity-alias" and node["has_launcher"] and not alias_act:
                    alias_act = node["attrs"].get("targetActivity") or node["attrs"].get("name")
            pos += sz
        act = launcher or alias_act
        if act and pkg and act.startswith("."):
            act = pkg + act
        return pkg, act
    except Exception:
        return None, None


def parse_apk_permissions(apk_path):
    """从 APK 的 AndroidManifest.xml 读取声明的权限短名集合（如 READ_PHONE_STATE）。
    用于只授予应用真正声明的权限，避免对未声明权限 pm grant 触发 SecurityException。
    解析失败返回 None（调用方退回逐个尝试）。"""
    try:
        with zipfile.ZipFile(apk_path) as z:
            data = z.read("AndroidManifest.xml")
    except Exception:
        return None
    if len(data) < 8:
        return None
    try:
        strings = None
        perms = set()
        pos = 8
        while pos + 8 <= len(data):
            t, _hs, sz = struct.unpack_from("<HHI", data, pos)
            if sz <= 0:
                break
            if t == 0x0001:
                strings = _axml_strings(data, pos)
            elif t == 0x0102 and strings:
                name, attrs = _axml_element(data, pos, strings)
                if name in ("uses-permission", "uses-permission-sdk-23"):
                    p = attrs.get("name") or ""
                    if p.startswith("android.permission."):
                        perms.add(p[len("android.permission."):])
            pos += sz
        return perms
    except Exception:
        return None


# ---------- adb 定位 ----------
def find_adb():
    p = shutil.which("adb")
    if p:
        return p
    cands = [
        os.path.join(PROJECT_DIR, "platform-tools", "adb.exe"),
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        os.path.expandvars(r"%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\platform-tools\adb.exe",
        r"C:\Android\Sdk\platform-tools\adb.exe",
        r"D:\Android\Sdk\platform-tools\adb.exe",
        r"C:\Program Files\Android\platform-tools\adb.exe",
    ]
    for c in cands:
        if os.path.exists(c):
            return c
    return "adb"

ADB = find_adb()


def find_scrcpy():
    """查找 scrcpy.exe：项目内 scrcpy/ 优先（保证分发即用），其次 PATH 与常见安装位置。"""
    cands = [
        os.path.join(PROJECT_DIR, "scrcpy", "scrcpy.exe"),
        os.path.join(PROJECT_DIR, "scrcpy.exe"),
    ]
    for c in cands:
        if os.path.exists(c):
            return c
    p = shutil.which("scrcpy")
    if p:
        return p
    for c in [
        os.path.expandvars(r"%LOCALAPPDATA%\scrcpy\scrcpy.exe"),
        r"C:\scrcpy\scrcpy.exe",
        r"D:\scrcpy\scrcpy.exe",
        r"C:\Program Files\scrcpy\scrcpy.exe",
    ]:
        if os.path.exists(c):
            return c
    return ""


SCRCPY = find_scrcpy()


def launch_scrcpy(serial):
    """为指定设备启动 scrcpy 投屏窗口（独立进程，不阻塞网页）。"""
    exe = SCRCPY or find_scrcpy()
    if not exe:
        return False, "未找到 scrcpy.exe（可放到项目根目录 scrcpy\\ 下，或安装到 C:\\scrcpy）"
    try:
        env = dict(os.environ)
        env["ADB"] = ADB          # 复用本工具的 adb，避免两套 adb server 抢 5037 端口
        subprocess.Popen(
            [exe, "-s", serial, "--window-title", "投屏 - " + serial],
            cwd=os.path.dirname(exe) or None,
            env=env,
            creationflags=0x00000008,   # DETACHED_PROCESS：关掉网页也不影响投屏
        )
        return True, ""
    except Exception as e:
        return False, str(e)

# ---------- 全局状态 ----------
# status[serial] = {"serial":..., "state": "idle|running|done|error",
#                   "steps": [...], "logs": [...], "result": "OK/FAIL/RUNNING",
#                   "ok":n, "fail":n, "skip":n}
STATUS = {}
LOCK = threading.Lock()

MACMAP_FILE = os.path.join(BASE_DIR, "macmap.json")
MACMAP_LOCK = threading.Lock()   # 保护 macmap.json 读-改-写的原子性，防止并发丢记录

PREFS_FILE = os.path.join(BASE_DIR, "prefs.json")
PREFS_LOCK = threading.Lock()    # 上次填写的 WiFi 等界面配置，跨重启保留


def load_prefs():
    """读取上次保存的界面配置（wifi_ssid / wifi_pass），失败返回 {}。"""
    try:
        with open(PREFS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_prefs(wifi_ssid, wifi_pass):
    """记录本次填写的 WiFi，下次打开页面自动回填（不再还原成默认值）。"""
    with PREFS_LOCK:
        cur = load_prefs()
        cur["wifi_ssid"] = wifi_ssid
        cur["wifi_pass"] = wifi_pass
        try:
            with open(PREFS_FILE, "w", encoding="utf-8") as f:
                json.dump(cur, f, ensure_ascii=False, indent=2)
            return True
        except Exception:
            return False

# 持久化对照表: {serial: {"mac":..., "android_id":..., "device_id":...}}，跨重启保留
def load_macmap():
    try:
        with open(MACMAP_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except Exception:
        return {}

def save_macmap():
    """累加式落盘：读回文件 -> 合并 STATUS -> 写回（绝不删除已有的任何记录）"""
    with MACMAP_LOCK:
        merged = dict(load_macmap())
        for s, rec in STATUS.items():
            di = (rec or {}).get("dev_info") or {}
            if not (di.get("mac") or di.get("android_id") or di.get("device_id")):
                continue
            cur = merged.setdefault(s, {})
            if di.get("mac"):
                cur["mac"] = di["mac"]
            if di.get("android_id"):
                cur["android_id"] = di["android_id"]
            if di.get("device_id"):
                cur["device_id"] = di["device_id"]
        try:
            with open(MACMAP_FILE, "w", encoding="utf-8") as f:
                json.dump(merged, f, ensure_ascii=False, indent=2)
        except Exception:
            pass


def log(serial, line):
    with LOCK:
        st = STATUS.setdefault(serial, {"serial": serial, "state": "running",
                                        "steps": [], "logs": [], "result": "RUNNING",
                                        "ok": 0, "fail": 0, "skip": 0})
        st["logs"].append(line)
        if len(st["logs"]) > 800:
            st["logs"] = st["logs"][-800:]


def step(serial, name, ok):
    with LOCK:
        st = STATUS.setdefault(serial, {"serial": serial, "state": "running",
                                        "steps": [], "logs": [], "result": "RUNNING",
                                        "ok": 0, "fail": 0, "skip": 0})
        tag = "OK" if ok else "FAIL"
        st["steps"].append((name, tag))
        if ok:
            st["ok"] += 1
        else:
            st["fail"] += 1


def skip(serial, name):
    with LOCK:
        st = STATUS.setdefault(serial, {"serial": serial, "state": "running",
                                        "steps": [], "logs": [], "result": "RUNNING",
                                        "ok": 0, "fail": 0, "skip": 0})
        st["steps"].append((name, "SKIP"))
        st["skip"] += 1


def adb_shell(serial, args, timeout=60):
    """执行 adb -s <serial> <args>，返回 (returncode, stdout)。
    以二进制捕获并用 utf-8 容错解码，避免 logcat 等含任意字节的输出触发编码崩溃。
    对关机/断连时常见的 "Broken pipe"、"Connection reset" 等瞬时连接错误：
    自动重试一次，且过滤掉这类脏输出，避免干扰上层判断。"""
    for attempt in range(2):
        try:
            r = subprocess.run([ADB, "-s", serial] + args,
                               capture_output=True, timeout=timeout)
            out = (r.stdout or b"") + (r.stderr or b"")
            rc = r.returncode
            text = out.decode("utf-8", errors="replace")
            # 瞬时连接错误：过滤脏输出并按失败处理，必要时重试
            if any(k in text.lower() for k in ("broken pipe", "connection reset",
                                               "device offline", "closed", "no devices")):
                if attempt == 0:
                    time.sleep(1)
                    continue
                return rc, ""
            return rc, text
        except Exception as e:
            if attempt == 0:
                time.sleep(1)
                continue
            return -1, str(e)
    return -1, ""


# 运行时权限（pm grant 授予）：存储读写 / 媒体读取 / 相机录音定位 / 蓝牙 / 通知 / 电话状态
RUNTIME_PERMS = [
    "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
    "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
    "CAMERA", "RECORD_AUDIO",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
    "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT",
    "POST_NOTIFICATIONS", "READ_PHONE_STATE",
]

# 特殊权限（appops set 授予）：pm grant 授不了，需 appops。
# Android 11+ 读写 /sdcard 公共目录靠 MANAGE_EXTERNAL_STORAGE（所有文件访问）。
APPOP_PERMS = [
    "MANAGE_EXTERNAL_STORAGE",
    "SYSTEM_ALERT_WINDOW",
    "POST_NOTIFICATION",
]


# pm grant 失败时这些关键字表示"应用未声明/非运行时权限"，属于正常跳过而非错误
_PERM_SKIP_MARKERS = (
    "unknown permission", "not declared", "has not requested", "not requested",
    "not a changeable permission", "does not have", "no such permission",
    "is not a runtime permission", "not a runtime permission",
)


def grant_permissions(serial, pkg, declared=None):
    """静默授予权限：pm grant 运行时权限 -> appops 特殊权限 -> 电池优化白名单。

    declared 为 APK 声明的权限短名集合（None 表示未知，退回逐个尝试）；
    只对声明的权限执行 pm grant，避免未声明权限触发 SecurityException。
    返回 (ok, granted, unknown, failed)；仅运行时权限的失败计入 ok（appops 失败只记 WARN）。
    """
    granted, unknown, failed = [], [], []
    perms = RUNTIME_PERMS
    if declared is not None:
        perms = [p for p in RUNTIME_PERMS if p in declared]
    for p in perms:
        perm = f"android.permission.{p}"
        rc, out = adb_shell(serial, ["shell", "pm", "grant", pkg, perm], timeout=30)
        if rc == 0:
            granted.append(p)
            log(serial, f"         [OK] {p}")
        else:
            low = (out or "").lower()
            if any(m in low for m in _PERM_SKIP_MARKERS):
                unknown.append(p)
                log(serial, f"         [SKIP] {p}: 应用未声明/非运行时权限，跳过")
            else:
                failed.append(p)
                log(serial, f"         [FAIL] {p}: {(out or '')[:200].strip()}")
    log(serial, f"       Permissions: 成功 {len(granted)}，跳过 {len(unknown)}，失败 {len(failed)}.")

    # appops 特殊权限（读写 /sdcard 的 MANAGE_EXTERNAL_STORAGE 在此）
    for op in APPOP_PERMS:
        rc, out = adb_shell(serial, ["shell", "appops", "set", pkg, op, "allow"], timeout=30)
        if rc == 0:
            log(serial, f"         [OK] appops {op}")
        else:
            log(serial, f"         [WARN] appops {op}: {(out or '')[:200].strip()}")
    rc, out = adb_shell(serial, ["shell", "appops", "get", pkg, "MANAGE_EXTERNAL_STORAGE"], timeout=30)
    log(serial, f"       MANAGE_EXTERNAL_STORAGE = {(out or '').strip()[:120]}")

    # 电池优化白名单（避免被系统杀后台）
    rc, out = adb_shell(serial, ["shell", "dumpsys", "deviceidle", "whitelist", f"+{pkg}"], timeout=30)
    log(serial, f"       [{'OK' if rc == 0 else 'WARN'}] 电池优化白名单 {pkg}")
    return (not failed), granted, unknown, failed


FSA_IN_APK = "assets/aikit/fsa/"
# 设备端只接受 .txt：混入其他格式会导致 App 读取异常，导入/解包/推送三处都要过滤
FSA_EXT = ".txt"
# 本地 fsa 属于哪个 APK 的记录（防止把上一个 APK 遗留的 fsa 误当成当前 APK 的）
FSA_META = os.path.join(PROJECT_DIR, ".fsa_meta.json")


def is_fsa_file(name):
    """.txt 才是有效 fsa 数据；.meta 是本地标记文件，单独忽略。"""
    return (name or "").lower().endswith(FSA_EXT)


def fsa_bad_files(fsa_dir):
    """列出目录里的非 .txt 文件（相对路径），用于拦截/告警。"""
    bad = []
    for root, _dirs, fns in os.walk(fsa_dir):
        for fn in fns:
            if fn.lower().endswith(".meta"):
                continue
            if not is_fsa_file(fn):
                bad.append(os.path.relpath(os.path.join(root, fn), fsa_dir).replace("\\", "/"))
    return bad


def read_fsa_meta():
    try:
        with open(FSA_META, encoding="utf-8") as f:
            d = json.load(f)
            return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def write_fsa_meta(apk_name):
    try:
        with open(FSA_META, "w", encoding="utf-8") as f:
            json.dump({"apk": os.path.basename(apk_name or ""), "ts": time.time()},
                      f, ensure_ascii=False)
    except Exception:
        pass



def fsa_in_apk_report(apk_path):
    """扫描 APK 内置 fsa：返回 (有效 txt 数, 非 txt 文件名列表)；读不到返回 (None, [])。"""
    try:
        with zipfile.ZipFile(apk_path) as z:
            names = [n for n in z.namelist()
                     if n.startswith(FSA_IN_APK) and not n.endswith("/")]
        txt = [n for n in names if is_fsa_file(n)]
        bad = [n[len(FSA_IN_APK):] for n in names if not is_fsa_file(n)]
        return len(txt), bad
    except Exception:
        return None, []


def extract_fsa_from_apk(apk_path):
    """把 APK 内的 assets/aikit/fsa/ 解包到临时目录，返回解包后的 fsa 目录；没有则 None。
    只解 .txt：其他格式设备读不了，解出来也只会把设备目录弄脏。"""
    try:
        with zipfile.ZipFile(apk_path) as z:
            names = [n for n in z.namelist()
                     if n.startswith(FSA_IN_APK) and not n.endswith("/")]
            bad = [n[len(FSA_IN_APK):] for n in names if not is_fsa_file(n)]
            if bad:
                print("[fsa] APK 内置 fsa 含 %d 个非 txt 文件，已跳过：%s"
                      % (len(bad), ", ".join(bad[:5])))
            names = [n for n in names if is_fsa_file(n)]
            if not names:
                return None
            tag = os.path.splitext(os.path.basename(apk_path))[0]
            dest_root = os.path.join(PROJECT_DIR, ".fsa_from_apk", tag)
            fsa_dir = os.path.join(dest_root, "fsa")
            shutil.rmtree(dest_root, ignore_errors=True)   # 清掉上次解包，避免旧文件残留
            os.makedirs(fsa_dir, exist_ok=True)
            for n in names:
                rel = n[len(FSA_IN_APK):]
                dest = os.path.join(fsa_dir, *rel.split("/"))
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with z.open(n) as src, open(dest, "wb") as dst:
                    shutil.copyfileobj(src, dst)
            return fsa_dir
    except Exception as e:
        print("[fsa] 解包 APK 失败：%s" % e)
        return None


def push_fsa_dir(serial, fsa_src, log, step):
    """先确认并清空设备上的 fsa 目录（防污染/残留），再注入，最后校验文件数。
    设备只认 .txt：源目录混入其他格式时先过滤到临时目录（目录名保持 fsa）。"""
    dev_targets = ["/sdcard/aikit/esr/fsa", "/sdcard/aikit/fsa"]
    tmp_root = None
    try:
        bad = fsa_bad_files(fsa_src)
        if bad:
            log(serial, f"       [fsa] 源目录含 {len(bad)} 个非 txt 文件，已过滤：{', '.join(bad[:5])}")
            tmp_root = tempfile.mkdtemp(prefix="fsa_txt_")
            filtered = os.path.join(tmp_root, "fsa")
            txt_n = 0
            for root, _dirs, fns in os.walk(fsa_src):
                for fn in fns:
                    if not is_fsa_file(fn):
                        continue
                    src_p = os.path.join(root, fn)
                    rel = os.path.relpath(src_p, fsa_src)
                    dst_p = os.path.join(filtered, rel)
                    os.makedirs(os.path.dirname(dst_p), exist_ok=True)
                    shutil.copy2(src_p, dst_p)
                    txt_n += 1
            if not txt_n:
                log(serial, "       [fsa] 源目录里没有 .txt 文件，跳过注入")
                step(serial, "fsapush", False)
                return False
            fsa_src = filtered
        # ---- 1) 检查设备现有 fsa 目录是否被污染/残留 ----
        for d in dev_targets:
            rc, out = adb_shell(serial, ["shell", "ls", "-A", d], timeout=30)
            n = 0
            if rc == 0:
                n = len([x for x in (out or "").splitlines() if x.strip()])
            if n:
                log(serial, f"       [fsa] 设备目录 {d} 现有 {n} 项（旧数据/污染），清空")
            adb_shell(serial, ["shell", "rm", "-rf", d], timeout=30)
        # ---- 2) 注入 ----
        adb_shell(serial, ["shell", "mkdir", "-p", "/sdcard/aikit/esr"], timeout=30)
        rc, out = adb_shell(serial, ["push", fsa_src, "/sdcard/aikit/esr/"], timeout=300)
        if rc != 0:
            log(serial, f"       fsa push failed: {(out or '')[:300]}")
            step(serial, "fsapush", False)
            return False
        # ---- 3) 校验：设备上的文件数必须与源一致 ----
        local_n = 0
        for _root, _dirs, fns in os.walk(fsa_src):
            local_n += len(fns)
        rc, out = adb_shell(serial, ["shell", "find", "/sdcard/aikit/esr/fsa", "-type", "f"], timeout=60)
        dev_n = -1
        if rc == 0:
            dev_n = len([x for x in (out or "").splitlines() if x.strip()])
        if dev_n >= 0 and dev_n != local_n:
            log(serial, f"       [fsa] 校验不一致：设备 {dev_n} 个文件 / 源 {local_n} 个，可能未清干净")
            step(serial, "fsapush", False)
            return False
        log(serial, f"       fsa pushed to /sdcard/aikit/esr/fsa.  {local_n} files (verified)")
        step(serial, "fsapush", True)
        return True
    except Exception as e:
        log(serial, f"       fsa push error: {e}")
        step(serial, "fsapush", False)
        return False
    finally:
        if tmp_root:
            shutil.rmtree(tmp_root, ignore_errors=True)


def install_one(serial, apk, pkg, act, wifi_ssid, wifi_pass):
    # 流程严格对齐 install.bat（已验证能跑通）：
    # install -> root -> 按需开WiFi/蓝牙 -> 连WiFi -> grant(静默) ->
    # launch(清notLaunched) -> set-home(等12s持久化) -> reboot(不等回线)
    base = "           "
    # ---- 自动识别 APK 包名 / 启动 Activity（识别成功则以 APK 为准） ----
    try:
        det_pkg, det_act = parse_apk_manifest(apk)
    except Exception:
        det_pkg, det_act = None, None
    declared_perms = parse_apk_permissions(apk)
    pkg_diff = bool(det_pkg and det_pkg != pkg)
    if det_pkg:
        pkg = det_pkg
    if det_act:
        act = det_act
    log(serial, "=" * 50)
    log(serial, f" Device {serial}  started {time.strftime('%Y-%m-%d %H:%M:%S')}")
    log(serial, f" APK     : {os.path.basename(apk)}")
    log(serial, f" Package : {pkg} / {act}")
    if pkg_diff:
        log(serial, "       [Auto] 包名以 APK 内识别结果为准（已忽略输入值）")
    log(serial, f" WiFi    : {wifi_ssid}")
    log(serial, "=" * 50)

    # 采集该设备 MAC / ANDROID_ID（设备此刻在线），存到状态记录，即使之后离线也保留
    try:
        info = collect_device_info(serial)
        with LOCK:
            st = STATUS.setdefault(serial, {"serial": serial, "state": "running",
                                            "steps": [], "logs": [], "result": "RUNNING",
                                            "ok": 0, "fail": 0, "skip": 0})
            st["dev_info"] = info
        if info.get("mac"):
            log(serial, f" MAC : {info['mac']}")
            log(serial, f" ANDROID_ID : {info['android_id']}")
        save_macmap()  # 落盘，跨重启保留
    except Exception:
        pass

    try:
        # ---- [0] uninstall old package (query by current APK's package name) ----
        log(serial, f"  [0/6] Checking old package: {pkg} ...")
        rc, out = adb_shell(serial, ["shell", "pm", "path", pkg], timeout=30)
        if rc == 0 and (out or "").strip().startswith("package:"):
            log(serial, f"       Old package found - uninstalling {pkg} ...")
            rc2, out2 = adb_shell(serial, ["uninstall", pkg], timeout=120)
            if rc2 == 0 and "Success" in (out2 or ""):
                log(serial, f"       Old package uninstalled.  {base}")
                step(serial, "uninstall_old", True)
            else:
                log(serial, f"       Uninstall failed: {(out2 or '')[:300]}")
                step(serial, "uninstall_old", False)
        else:
            log(serial, "       No old package on device. Skip uninstall.")
            skip(serial, "uninstall_old")

        # ---- [1] install ----
        log(serial, "  [1/5] Installing APK...")
        rc, out = adb_shell(serial, ["install", "-r", "-g", apk], timeout=600)
        if rc != 0:
            log(serial, "       -g 安装失败，改用普通模式重试...")
            rc, out = adb_shell(serial, ["install", "-r", apk], timeout=600)
        if rc == 0:
            log(serial, f"       Install succeeded.  {base}")
            step(serial, "install", True)
        else:
            log(serial, "       Install failed.")
            log(serial, out[:2000])
            step(serial, "install", False)
            finish(serial)
            return

        # ---- [1.5] push fsa 数据文件夹到 /sdcard/aikit/esr/fsa ----
        # 优先用 APK 内置的 assets/aikit/fsa；没有再用本地 fsa（手动上传）；都没有则提示手动上传
        log(serial, "       Pushing fsa folder...")
        _apk_txt, _apk_bad = fsa_in_apk_report(apk)
        if _apk_bad:
            log(serial, f"       [WARN] APK 内置 fsa 含 {len(_apk_bad)} 个非 txt 文件，已忽略（设备只接受 txt）："
                        f"{', '.join(_apk_bad[:5])}")
        fsa_src = extract_fsa_from_apk(apk)
        fsa_from = "APK 内置 assets/aikit/fsa"
        if not fsa_src:
            local_fsa = os.path.join(PROJECT_DIR, "fsa")
            meta = read_fsa_meta()
            if os.path.isdir(local_fsa) and meta.get("apk") == os.path.basename(apk):
                fsa_src = local_fsa
                fsa_from = "本地 fsa（本次为该 APK 手动上传）"
        if fsa_src:
            log(serial, f"       fsa 来源：{fsa_from}")
            push_fsa_dir(serial, fsa_src, log, step)
        else:
            log(serial, "       [WARN] APK 内未找到 assets/aikit/fsa。")
            _meta = read_fsa_meta()
            if os.path.isdir(os.path.join(PROJECT_DIR, "fsa")) and _meta.get("apk"):
                log(serial, f"       [WARN] 本地 fsa 属于「{_meta['apk']}」，不是当前 APK 的，已忽略（可能是上个 APK 遗留）。")
            log(serial, "       [WARN] 请在页面「② fsa 数据包」为当前 APK 手动上传后再装，本次跳过 fsa 推送。")
            skip(serial, "fsapush")

        # ---- [2] root ----
        log(serial, "  [2/6] Getting root...")
        adb_shell(serial, ["root"], timeout=30)
        adb_shell(serial, ["wait-for-device"], timeout=60)
        time.sleep(2)
        log(serial, f"       Root done.  {base}")
        step(serial, "root", True)

        # ---- enable Wi-Fi / Bluetooth only if they are OFF ----
        log(serial, "       Checking Wi-Fi / Bluetooth state...")

        # Wi-Fi
        rc, out = adb_shell(serial, ["shell", "settings", "get", "global", "wifi_on"], timeout=30)
        if out.strip() != "1":
            log(serial, "       Wi-Fi is off - enabling...")
            adb_shell(serial, ["shell", "svc", "wifi", "enable"], timeout=30)
            step(serial, "wifi_enable", True)
        else:
            log(serial, "       Wi-Fi already on.")
            step(serial, "wifi_enable", True)

        # Bluetooth（真实验证：enable 后轮询 bluetooth_on）
        rc, out = adb_shell(serial, ["shell", "settings", "get", "global", "bluetooth_on"], timeout=30)
        if out.strip() != "1":
            log(serial, "       Bluetooth is off - enabling...")
            adb_shell(serial, ["shell", "svc", "bluetooth", "enable"], timeout=30)
            bt_ok = False
            for attempt in range(1, 5):
                time.sleep(2)
                rc, out = adb_shell(serial, ["shell", "settings", "get", "global", "bluetooth_on"], timeout=30)
                if out.strip() == "1":
                    bt_ok = True
                    log(serial, f"       Bluetooth enabled (attempt {attempt}).")
                    break
                log(serial, f"       attempt {attempt}: bluetooth still off...")
            if bt_ok:
                step(serial, "bluetooth_enable", True)
            else:
                log(serial, "       [WARN] Failed to enable Bluetooth.")
                step(serial, "bluetooth_enable", False)
        else:
            log(serial, "       Bluetooth already on.")
            step(serial, "bluetooth_enable", True)

        # ---- connect to configured Wi-Fi (skip when SSID empty) ----
        if wifi_ssid:
            log(serial, f"       Connecting Wi-Fi: {wifi_ssid}...")
            if wifi_pass:
                adb_shell(serial, ["shell", "cmd", "wifi", "connect-network",
                                   wifi_ssid, "wpa2", wifi_pass], timeout=60)
            else:
                adb_shell(serial, ["shell", "cmd", "wifi", "connect-network",
                                   wifi_ssid, "open"], timeout=60)
            # 真实验证：轮询 cmd wifi status，确认连接到目标 SSID
            log(serial, "       Waiting for Wi-Fi association...")
            connected = False
            for attempt in range(1, 6):
                time.sleep(3)
                rc, out = adb_shell(serial, ["shell", "cmd", "wifi", "status"], timeout=30)
                text = out or ""
                if "wifi is connected" in text.lower() and wifi_ssid.lower() in text.lower():
                    connected = True
                    log(serial, f"       WiFi connected: {wifi_ssid} (attempt {attempt}).")
                    break
                log(serial, f"       attempt {attempt}: not connected yet...")
            if connected:
                step(serial, "wifi_connect", True)
            else:
                log(serial, f"       [WARN] WiFi NOT connected to {wifi_ssid}.")
                log(serial, (out or "")[:800])
                step(serial, "wifi_connect", False)
        else:
            log(serial, "       No Wi-Fi configured - skipping connection.")
            skip(serial, "wifi_connect")

        # ---- fallback: always save HSY_VR hotspot, regardless of selected SSID ----
        fallback_ssid = "HSY_VR"
        fallback_pass = "123456789"
        if wifi_ssid and wifi_ssid.strip().lower() != fallback_ssid.lower():
            log(serial, f"       [Fallback] Saving hotspot {fallback_ssid} as backup...")
            adb_shell(serial, ["shell", "cmd", "wifi", "connect-network",
                               fallback_ssid, "wpa2", fallback_pass], timeout=60)
            # 校验兜底热点是否连上（当前选的热点在场时，兜底只保存不抢占连接）
            rc, out = adb_shell(serial, ["shell", "cmd", "wifi", "status"], timeout=30)
            text = (out or "").lower()
            if "wifi is connected" in text and fallback_ssid.lower() in text:
                log(serial, f"       [Fallback] connected to {fallback_ssid}.")
            else:
                log(serial, f"       [Fallback] {fallback_ssid} saved (not currently in range).")
            step(serial, "wifi_fallback", True)
        else:
            # 当前选的就是 HSY_VR，或未配 WiFi——前者已写、后者跳过
            skip(serial, "wifi_fallback")

        # ---- grant FIRST (before app launch, no permission popup) ----
        log(serial, "       Granting permissions...")
        result_ok, _g, _u, _f = grant_permissions(serial, pkg, declared_perms)
        step(serial, "grant", result_ok)

        # ---- launch first (clears notLaunched; needed before set-home persists) ----
        log(serial, "       Launching app (first run, may take a while)...")
        adb_shell(serial, ["shell", "am", "start", "-n", f"{pkg}/{act}"], timeout=30)
        time.sleep(8)
        step(serial, "launch", True)

        # ---- capture Unity SystemInfo.deviceUniqueIdentifier (logcat heartbeat deviceId) ----
        log(serial, "       Capturing Unity deviceId (logcat heartbeat)...")
        device_id = collect_device_id(serial, pkg, act, wait=25)
        if device_id:
            log(serial, f"       deviceUniqueIdentifier = {device_id}")
            with LOCK:
                st = STATUS[serial]
                st.setdefault("dev_info", {})["device_id"] = device_id
            save_macmap()
        else:
            log(serial, "       [WARN] 未捕获到 Unity deviceId（应用可能未打印 heartbeat）")

        # ---- set home (only persists after app has been launched) ----
        log(serial, "  [3/6] Setting as default home...")
        rc, out = adb_shell(serial, ["shell", "pm", "set-home-activity",
                                     f"{pkg}/{act}"], timeout=30)
        ok = "success" in out.lower() or rc == 0
        log(serial, "       Waiting for preference to flush to disk...")
        time.sleep(12)
        log(serial, f"       Home role set and persisted.  {base}")
        step(serial, "home", ok)

        # ---- reboot (issue, do NOT wait) ----
        log(serial, "  [4/6] Rebooting...")
        try:
            subprocess.run([ADB, "-s", serial, "reboot"],
                           capture_output=True, timeout=15)
        except Exception as e:
            log(serial, f"       [WARN] reboot cmd error: {e}")
        log(serial, "       Reboot issued (done; not waiting for device return).")
        step(serial, "reboot", True)

        # ---- summary ----
        with LOCK:
            st = STATUS[serial]
            okc, failc, skipc = st["ok"], st["fail"], st["skip"]
        log(serial, "=" * 50)
        log(serial, f"  SUMMARY  OK={okc}  FAIL={failc}  SKIP={skipc}")
        log(serial, "=" * 50)
        log(serial, f"RESULT={'OK' if failc == 0 else 'FAIL'}")
        finish(serial)
    except Exception as e:
        log(serial, f"[ERROR] {e}")
        finish(serial)


def plain_install_one(serial, apk, grant_perms=False):
    """普通安装：执行 adb install -r；可选在安装后授予运行时权限。

    与 install_one 的区别：不卸载旧包、不推送 fsa、不 root、不开关 WiFi/蓝牙、
    不连热点、不启动应用、不设默认桌面、不重启。
    grant_perms=True 时安装成功后仅执行 pm grant 授予运行时权限（不设 home）。
    """
    with LOCK:
        STATUS[serial] = {"serial": serial, "state": "running", "steps": [],
                          "logs": [], "result": "RUNNING",
                          "ok": 0, "fail": 0, "skip": 0, "dev_info": {}}
    log(serial, "=" * 50)
    log(serial, " [普通安装] 仅 adb install -r，不卸载旧包 / 不推 fsa / 不 root /")
    if grant_perms:
        log(serial, "            不连 WiFi / 安装后授予运行时权限 / 不设桌面 / 不重启")
    else:
        log(serial, "            不连 WiFi / 不授权限 / 不设桌面 / 不重启")
    log(serial, f" Device  : {serial}")
    log(serial, f" APK     : {os.path.basename(apk)}")
    log(serial, "=" * 50)
    try:
        inst_args = ["install", "-r", "-g", apk] if grant_perms else ["install", "-r", apk]
        rc, out = adb_shell(serial, inst_args, timeout=600)
        if rc != 0 and grant_perms:
            log(serial, "       -g 安装失败，改用普通模式重试...")
            rc, out = adb_shell(serial, ["install", "-r", apk], timeout=600)
        text = (out or "").strip()
        for ln in text.splitlines():
            ln = ln.strip()
            if ln:
                log(serial, "       " + ln)
        if rc == 0 and "success" in text.lower():
            log(serial, "       [普通安装] Install succeeded.")
            step(serial, "plain_install", True)
            if grant_perms:
                det_pkg, _det_act = parse_apk_manifest(apk)
                if det_pkg:
                    declared_perms = parse_apk_permissions(apk)
                    log(serial, f"       Granting permissions to {det_pkg} ...")
                    result_ok, _g, _u, _f = grant_permissions(serial, det_pkg, declared_perms)
                    step(serial, "grant", result_ok)
                else:
                    log(serial, "       [WARN] 未能从 APK 识别包名，跳过授权。")
                    step(serial, "grant", False)
        else:
            log(serial, "       [普通安装] Install failed.")
            if not text:
                log(serial, "       (adb 无输出，设备可能已离线)")
            step(serial, "plain_install", False)
    except Exception as e:
        log(serial, f"[ERROR] {e}")
        step(serial, "plain_install", False)
    with LOCK:
        st = STATUS.get(serial, {})
        okc, failc = st.get("ok", 0), st.get("fail", 0)
    log(serial, "=" * 50)
    tail = "普通安装 + 授权" if grant_perms else "普通安装，无其他设置"
    log(serial, f"  SUMMARY  OK={okc}  FAIL={failc}  ({tail})")
    log(serial, "=" * 50)
    finish(serial)


def uninstall_one(serial, pkg):
    """卸载指定设备的应用，写入 STATUS 供前端轮询。"""
    try:
        with LOCK:
            STATUS[serial] = {"serial": serial, "state": "running", "steps": [],
                              "logs": [], "result": "RUNNING",
                              "ok": 0, "fail": 0, "skip": 0, "dev_info": {}}
        def step(serial, name, ok):
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["steps"].append({"name": name, "ok": ok})
        def log(serial, msg):
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["logs"].append(msg)
        log(serial, f"=== 卸载 {pkg} ===")
        rc, out = adb_shell(serial, ["uninstall", pkg])
        out = (out or "").strip()
        log(serial, f"uninstall rc={rc} out={out!r}")
        if rc == 0:
            step(serial, "uninstall", True)
            log(serial, "RESULT=OK")
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["ok"] = 1
                    STATUS[serial]["result"] = "OK"
        else:
            step(serial, "uninstall", False)
            log(serial, f"RESULT=FAIL ({out})")
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["fail"] = 1
                    STATUS[serial]["result"] = "FAIL"
        finish(serial)
    except Exception as e:
        log(serial, f"[ERROR] {e}")
        finish(serial)


# ---------- 已安装应用列表 ----------
_APK_PKG_CACHE = {}


def local_apk_packages():
    """扫描项目目录下的 APK，返回 {包名: APK 文件名}，用于在应用列表里显示可读名称。"""
    result = {}
    try:
        names = [f for f in os.listdir(PROJECT_DIR) if f.lower().endswith(".apk")]
    except Exception:
        return result
    for fn in names:
        path = os.path.join(PROJECT_DIR, fn)
        try:
            st = os.stat(path)
            key = (path, st.st_size, int(st.st_mtime))
            if key not in _APK_PKG_CACHE:
                pkg_name, _act = parse_apk_manifest(path)
                _APK_PKG_CACHE[key] = pkg_name or ""
            pkg = _APK_PKG_CACHE.get(key) or ""
        except Exception:
            pkg = ""
        if pkg:
            result.setdefault(pkg, fn)
    return result


def list_installed_apps(serial):
    """列出设备上已安装的第三方应用（pm list packages -3），供前端显示与逐个卸载。"""
    if serial not in list_devices():
        return {"serial": serial, "apps": [], "error": f"设备 {serial} 不在线"}
    rc, out = adb_shell(serial, ["shell", "pm", "list", "packages", "-3"], timeout=60)
    if rc != 0:
        return {"serial": serial, "apps": [],
                "error": (((out or "").strip() or "adb 执行失败")[:300])}
    local = local_apk_packages()
    apps = []
    for line in (out or "").splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        pkg = line[len("package:"):].strip()
        if not pkg:
            continue
        apps.append({"pkg": pkg, "apk": local.get(pkg, "")})
    apps.sort(key=lambda a: a["pkg"].lower())
    return {"serial": serial, "apps": apps, "error": ""}


# ---------- logcat 查看 ----------
# 只允许常规 logcat 过滤器表达式（tag:级别、*:W、tag:v *:S 等）
LOGCAT_SPEC_OK = re.compile(r"^[A-Za-z0-9_.*:()\s,+\-]+$")


def dump_logcat(serial, lines=600, spec=""):
    """抓取指定设备 logcat 缓冲区尾部若干行（-d 快照，读完即退出，不长期占用设备）。
    spec 为 logcat 过滤器表达式（可选），如 "*:W" 只看警告及以上。"""
    if serial not in list_devices():
        return {"serial": serial, "online": False, "lines": [], "error": "设备不在线",
                "note": ""}
    args = ["logcat", "-d", "-v", "time", "-t", str(lines)]
    if spec:
        args.append(spec)
    rows, rc = [], -1
    # 失败重试一次：其它工具的 adb（不同版本）抢占 5037 端口时会重启 adb server，
    # 首条命令容易瞬时失败，重试一次即可恢复。
    for attempt in range(2):
        rc, out = adb_shell(serial, args, timeout=30)
        rows = (out or "").splitlines()
        if rc == 0 or rows:
            break
        time.sleep(1)
    if rows:
        return {"serial": serial, "online": True, "lines": rows, "error": "", "note": ""}
    if rc != 0:
        return {"serial": serial, "online": True, "lines": [], "note": "",
                "error": f"logcat 读取失败 (rc={rc})"}
    return {"serial": serial, "online": True, "lines": [], "error": "",
            "note": "缓冲区为空：可能刚被清空、buffer 太小已滚动覆盖，或 logd 被系统限流"} 


def clear_logcat(serial):
    """清空设备 logcat 缓冲区（便于清屏后复现现场问题）。"""
    if serial not in list_devices():
        return {"serial": serial, "online": False, "ok": False,
                "error": "设备不在线"}
    rc, out = adb_shell(serial, ["logcat", "-c"], timeout=20)
    return {"serial": serial, "online": True, "ok": rc == 0,
            "error": (out or "").strip()[:300]}


def _decode_adb(data):
    """adb 输出解码：优先 UTF-8，失败再按 GBK 解（中文 Windows 路径/中文输出常见）。"""
    if not data:
        return ""
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("gbk", errors="replace")


# adb 全局命令（作用于 adb server 或所有设备），带了 -s 会报错，故忽略所选设备
ADB_GLOBAL_CMDS = {"devices", "version", "start-server", "kill-server", "connect",
                   "disconnect", "get-state", "wait-for-device", "reconnect", "pair"}


def adb_run(serial, args, timeout=30, max_chars=50000):
    """执行任意 adb 子命令（供网页上的 ADB 终端使用），返回 {rc, out, cmd, note}。

    - args 以列表传给 subprocess，**不经过 shell**，因此不存在命令注入面；
    - serial 为空时不加 -s（供 devices / version 这类全局命令）；
    - 只剔除 **adb 全局选项位置**的 -s/--serial（子命令自带的 `-s` 必须保留，
      例如 `shell pm list packages -s` 是「只列系统应用」，语义不同）；
    - `logcat` 未带 -d 时自动补 -d（快照模式），否则会一直挂着直到超时；
    - 超时由 subprocess 终止子进程，返回 timeout=True 与已收到的输出。
    """
    args = [str(a) for a in args if str(a) != ""]
    out_args, skip, stripped, seen_sub = [], False, False, False
    for a in args:
        if skip:
            skip = False
            stripped = True
            continue
        if not seen_sub and a in ("-s", "--serial"):
            skip = True
            continue
        if not a.startswith("-"):
            seen_sub = True
        out_args.append(a)
    args = out_args

    notes = []
    if stripped:
        notes.append("已忽略命令里的 -s/--serial，统一使用上方下拉所选的设备")
    if not args:
        return {"rc": -1, "out": "", "error": "命令为空"}
    if args[0] in ADB_GLOBAL_CMDS and serial:
        notes.append("该命令为 adb 全局命令，未使用所选设备（未加 -s）")
        serial = ""
    if args[0] == "logcat" and "-d" not in args:
        args = ["logcat", "-d"] + args[1:]
        notes.append("已自动补 -d（快照模式）避免常驻阻塞，持续观察请用设备卡片上的日志面板")

    cmd = [ADB] + (["-s", serial] if serial else []) + args
    shown = "adb " + " ".join((["-s", serial] if serial else []) + args)
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        out = _decode_adb((e.stdout or b"") + (e.stderr or b""))
        notes.append(f"命令超过 {timeout}s 未结束，已终止（交互式/常驻命令请用专用按钮或日志面板）")
        return {"rc": -1, "out": out[:max_chars], "timeout": True,
                "truncated": len(out) > max_chars, "cmd": shown,
                "note": "；".join(notes)}
    except Exception as e:
        return {"rc": -1, "out": "", "error": f"执行失败：{e}", "cmd": shown}

    out = _decode_adb((r.stdout or b"") + (r.stderr or b""))
    truncated = len(out) > max_chars
    if truncated:
        notes.append(f"输出过长，仅显示前 {max_chars} 字符")
    return {"rc": r.returncode, "out": out[:max_chars], "truncated": truncated,
            "cmd": shown, "note": "；".join(notes)}


def poweroff_one(serial):
    """主动关机：adb reboot -p（真关机），结果写入 STATUS。"""
    try:
        with LOCK:
            STATUS[serial] = {"serial": serial, "state": "running", "steps": [],
                              "logs": [], "result": "RUNNING",
                              "ok": 0, "fail": 0, "skip": 0, "dev_info": {}}
        def step(serial, name, ok):
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["steps"].append({"name": name, "ok": ok})
        def log(serial, msg):
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["logs"].append(msg)
        log(serial, "=== 主动关机 (reboot -p) ===")
        try:
            subprocess.run([ADB, "-s", serial, "reboot", "-p"],
                           capture_output=True, timeout=20)
            step(serial, "poweroff", True)
            log(serial, "RESULT=OK")
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["ok"] = 1
                    STATUS[serial]["result"] = "OK"
        except Exception as e:
            step(serial, "poweroff", False)
            log(serial, f"RESULT=FAIL ({e})")
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["fail"] = 1
                    STATUS[serial]["result"] = "FAIL"
        finish(serial)
    except Exception as e:
        log(serial, f"[ERROR] {e}")
        finish(serial)


def reboot_one(serial):
    """重启指定设备：adb reboot，结果写入 STATUS。"""
    try:
        with LOCK:
            STATUS[serial] = {"serial": serial, "state": "running", "steps": [],
                              "logs": [], "result": "RUNNING",
                              "ok": 0, "fail": 0, "skip": 0, "dev_info": {}}
        def step(serial, name, ok):
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["steps"].append({"name": name, "ok": ok})
        def log(serial, msg):
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["logs"].append(msg)
        log(serial, "=== 重启设备 (reboot) ===")
        try:
            r = subprocess.run([ADB, "-s", serial, "reboot"],
                               capture_output=True, timeout=20)
            out = ((r.stdout or b"") + (r.stderr or b"")).decode("utf-8", errors="replace").strip()
            if out:
                log(serial, f"adb reboot: {out}")
            low = out.lower()
            if r.returncode != 0 or "error" in low or "not found" in low or "offline" in low:
                raise RuntimeError(out or f"adb rc={r.returncode}")
            step(serial, "reboot", True)
            log(serial, "RESULT=OK")
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["ok"] = 1
                    STATUS[serial]["result"] = "OK"
        except Exception as e:
            step(serial, "reboot", False)
            log(serial, f"RESULT=FAIL ({e})")
            with LOCK:
                if serial in STATUS:
                    STATUS[serial]["fail"] = 1
                    STATUS[serial]["result"] = "FAIL"
        finish(serial)
    except Exception as e:
        log(serial, f"[ERROR] {e}")
        finish(serial)


def finish(serial):
    with LOCK:
        st = STATUS.get(serial, {})
        result = "FAIL"
        if st.get("fail", 0) == 0 and st.get("state") != "error":
            result = "OK"
        st["state"] = "done"
        st["result"] = result


# ---------- HTTP handler ----------
# 应用默认配置（配置在网页上直接填写，不再读取 app.config 文件）
DEFAULTS = {"PKG": "com.hsy.sygg", "ACT": "com.unity3d.player.UnityPlayerActivity"}


def resolve_apk(data):
    """从请求体解析 APK 路径（相对名拼到项目目录），返回 (绝对路径, 错误信息)。"""
    apk = (data.get("apk") or "").strip()
    if not apk:
        return "", "APK not selected"
    if not os.path.isabs(apk):
        apk = os.path.join(PROJECT_DIR, apk)
    if not os.path.exists(apk):
        return "", f"APK not found: {apk}"
    if not apk.lower().endswith(".apk"):
        return "", "Selected file is not an .apk"
    return apk, ""


def list_devices_detail():
    """返回 [(serial, state)]，state 为 adb 报的 device / unauthorized / offline 等。

    list_devices() 只留可用设备；这里把未授权、离线的也带出来，前端才能提示
    「已插上但没在手机上点允许」，而不是看起来像根本没插设备。
    """
    out = []
    try:
        r = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=30)
        for line in (r.stdout or "").splitlines():
            line = line.strip()
            if not line or line.startswith("List") or line.startswith("*"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                out.append((parts[0], parts[1]))
    except Exception:
        pass
    return out


def list_devices():
    return [serial for serial, state in list_devices_detail() if state == "device"]


def get_battery(serial):
    """读单台设备电量，返回 {serial, level, charging, ok, error}。

    优先 `dumpsys battery`（能同时拿到充放电状态），失败再退到 sysfs capacity。
    level 取不到时为 None（前端显示“未知”，不猜 0）。
    """
    info = {"serial": serial, "level": None, "charging": False, "ok": False, "error": ""}
    scale = 100
    try:
        rc, out = adb_shell(serial, ["shell", "dumpsys", "battery"], timeout=15)
        text = out or ""
        raw = None
        if rc == 0 and "level" in text:
            for ln in text.splitlines():
                k, _, v = ln.strip().partition(":")
                k, v = k.strip().lower(), v.strip()
                if k == "level":
                    try:
                        raw = int(float(v))
                    except Exception:
                        pass
                elif k == "scale":
                    try:
                        scale = int(float(v)) or 100
                    except Exception:
                        scale = 100
                elif k == "status":
                    if v in ("2", "5"):            # 2=charging 5=full
                        info["charging"] = True
                elif k in ("ac powered", "usb powered", "wireless powered"):
                    if v.lower() == "true":
                        info["charging"] = True
        if raw is not None:
            lvl = raw * 100 // scale if scale > 0 else raw
            info["level"] = max(0, min(100, lvl))
    except Exception as e:
        info["error"] = str(e)

    if info["level"] is None:
        # 兜底：直接读 sysfs
        for node in ("/sys/class/power_supply/battery/capacity",
                     "/sys/class/power_supply/BAT0/capacity"):
            try:
                rc, out = adb_shell(serial, ["shell", "cat", node], timeout=10)
                first = (out or "").strip().splitlines()[0].strip() if (out or "").strip() else ""
                if first.isdigit():
                    info["level"] = max(0, min(100, int(first)))
                    info["error"] = ""
                    break
            except Exception:
                pass
        if info["level"] is None and not info["error"]:
            info["error"] = "读取不到电量（dumpsys 与 sysfs 均无数据）"

    info["ok"] = info["level"] is not None
    return info


def all_batteries(serials=None):
    """并行读多台设备电量，返回 {serial: info}。"""
    devs = list(serials) if serials else list_devices()
    result, guard = {}, threading.Lock()

    def work(s):
        b = get_battery(s)
        with guard:
            result[s] = b

    threads = [threading.Thread(target=work, args=(s,), daemon=True) for s in devs]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=25)
    for s in devs:
        result.setdefault(s, {"serial": s, "level": None, "charging": False,
                              "ok": False, "error": "读取超时"})
    return result


def check_one_wifi(serial):
    """检测单台设备 WiFi 状态，返回 dict"""
    info = {"serial": serial, "wifi_on": False, "connected": False,
            "ssid": "", "rssi": None, "speed": ""}
    try:
        rc, out = adb_shell(serial, ["shell", "cmd", "wifi", "status"], timeout=30)
        text = out or ""
        info["wifi_on"] = "wifi is enabled" in text.lower()
        if "wifi is connected" in text.lower():
            info["connected"] = True
        # 提取 SSID（兼容带引号/不带引号两种格式）
        import re
        m = re.search(r'WifiInfo:\s*SSID:\s*"?([^",\s]+)"?', text)
        if m:
            info["ssid"] = m.group(1)
        # 提取 RSSI
        m = re.search(r'RSSI:\s*(-?\d+)', text)
        if m:
            info["rssi"] = int(m.group(1))
        # 提取 link speed
        m = re.search(r'Link speed:\s*(\d+)Mbps', text)
        if m:
            info["speed"] = m.group(1) + " Mbps"
    except Exception as e:
        info["error"] = str(e)
    return info


def scan_wifi(serial):
    """让单台设备扫描附近热点，返回去重排序后的 SSID 列表。
    优先用 cmd wifi list-scan-results；失败/为空则回退 dumpsys wifi。
    扫描前先确保 WiFi 开关开启。"""
    ssids = []
    try:
        # 扫描前先确保 WiFi 开启
        rc, st = adb_shell(serial, ["shell", "settings", "get", "global", "wifi_on"], timeout=30)
        if (st or "").strip() != "1":
            adb_shell(serial, ["shell", "svc", "wifi", "enable"], timeout=30)
            time.sleep(2)
        adb_shell(serial, ["shell", "cmd", "wifi", "start-scan"], timeout=20)
        time.sleep(3)
        rc, out = adb_shell(serial, ["shell", "cmd", "wifi", "list-scan-results"], timeout=20)
        # 列格式：BSSID  Frequency  RSSI  Age  SSID  Flags
        # SSID 是第一个以 '[' 开头的 Flags 列之前的 token（BSSID/频率/信号/时长 之后的）
        for line in (out or "").splitlines():
            line = line.strip()
            if not line:
                continue
            tok = line.split()
            flag_idx = None
            for i, t in enumerate(tok):
                if t.startswith("["):
                    flag_idx = i
                    break
            if flag_idx is None or flag_idx <= 4:
                continue  # 无 Flags 或 SSID 为空（隐藏热点）
            ssid = " ".join(tok[4:flag_idx]).strip()
            if ssid:
                ssids.append(ssid)
    except Exception:
        pass
    if not ssids:
        try:
            rc, out = adb_shell(serial, ["shell", "dumpsys", "wifi"], timeout=30)
            import re
            for m in re.finditer(r'SSID:\s*"?([^",\s]+)"?', out or ""):
                s = m.group(1)
                if s and s not in ("<none>", "<unknown ssid>"):
                    ssids.append(s)
        except Exception:
            pass
    seen, result = set(), []
    for s in ssids:
        if s and s not in seen:
            seen.add(s)
            result.append(s)
    return sorted(result)


# ---------------------------------------------------------------- 蓝牙
_BT_MAC_RE = re.compile(r'^([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\b\s*(.*)$')
_UI_BOUNDS_RE = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def _parse_bt_devices(text):
    """从 dumpsys bluetooth_manager 输出解析蓝牙设备（已配对 / 已连接）。
    返回 [{'mac','name','bonded','connected'}]，按出现顺序去重。"""
    devs, order, section = {}, [], ""
    for raw in (text or "").splitlines():
        line = raw.strip()
        low = line.lower()
        if line.endswith(":"):
            if "bonded" in low:
                section = "bonded"
            elif "connected" in low:
                section = "connected"
            else:
                section = ""
            continue
        m = _BT_MAC_RE.match(line)
        if not m or section not in ("bonded", "connected"):
            continue
        mac = m.group(1).upper()
        name = m.group(2).split("[")[0].strip() or "(未知设备)"
        if mac not in devs:
            devs[mac] = {"mac": mac, "name": name, "bonded": False, "connected": False}
            order.append(mac)
        else:
            if devs[mac]["name"] == "(未知设备)" and name != "(未知设备)":
                devs[mac]["name"] = name
        if section == "bonded":
            devs[mac]["bonded"] = True
        else:
            devs[mac]["connected"] = True
    return [devs[m] for m in order]


def scan_bluetooth(serial):
    """读取单台设备蓝牙：已配对设备 + （若系统正在发现）附近设备。
    纯读取，不改变设备界面。"""
    try:
        rc, st = adb_shell(serial, ["shell", "settings", "get", "global", "bluetooth_on"], timeout=30)
        if (st or "").strip() != "1":
            adb_shell(serial, ["shell", "svc", "bluetooth", "enable"], timeout=30)
            time.sleep(2)
        rc, out = adb_shell(serial, ["shell", "dumpsys", "bluetooth_manager"], timeout=30)
        return {"devices": _parse_bt_devices(out or ""), "error": ""}
    except Exception as e:
        return {"devices": [], "error": str(e)}


def _dump_ui_nodes(serial):
    """uiautomator dump 当前界面，返回 [{'text','x','y'}]（x,y 为控件中心坐标）。"""
    adb_shell(serial, ["shell", "uiautomator", "dump", "/data/local/tmp/_bt_ui.xml"], timeout=30)
    rc, xml = adb_shell(serial, ["exec-out", "cat", "/data/local/tmp/_bt_ui.xml"], timeout=30)
    if not xml or "<node" not in xml:
        return []
    nodes = []
    for tag in re.findall(r'<node\b[^>]*?>', xml):
        m = re.search(r'text="([^"]*)"', tag)
        text = (m.group(1) if m else "").strip()
        if not text:
            continue
        b = _UI_BOUNDS_RE.search(tag)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        nodes.append({"text": text, "x": (x1 + x2) // 2, "y": (y1 + y2) // 2})
    return nodes


def _find_ui_node(nodes, contains=None, texts=None):
    """找界面节点：text 含 contains（不区分大小写）；或 text 精确命中 texts 之一。
    比较前去掉空白，兼容部分 ROM 给中文按钮插空格（如「配 对」）的情况。"""
    def norm(s):
        return re.sub(r'\s+', '', s or "")
    if contains:
        c = norm(contains).lower()
        for n in nodes:
            if c in norm(n["text"]).lower():
                return n
    if texts:
        ts = {norm(t) for t in texts}
        for n in nodes:
            if norm(n["text"]) in ts:
                return n
    return None


def pair_bluetooth(serial, target):
    """UI 自动化配对蓝牙设备（主要针对蓝牙耳机）。

    流程：确保蓝牙开启 → 亮屏解锁 → 打开系统蓝牙设置（该页会自动扫描）→
    轮询界面找到目标设备并点击 → 如出现配对确认框则点确认 → 校验是否已配对。
    返回 {ok, steps, error}。注意：依赖系统界面文案，不同 ROM 可能需调整。
    """
    steps = []

    def note(msg):
        steps.append(msg)
        log(serial, "       " + msg)

    target = (target or "").strip()
    if not target:
        return {"ok": False, "steps": steps, "error": "未填写目标设备名称"}

    # 1) 确保蓝牙开启
    rc, st = adb_shell(serial, ["shell", "settings", "get", "global", "bluetooth_on"], timeout=30)
    if (st or "").strip() != "1":
        adb_shell(serial, ["shell", "svc", "bluetooth", "enable"], timeout=30)
        time.sleep(2)
    note("蓝牙已开启")

    # 2) 亮屏 + 解锁（UI 自动化需要屏幕可用）
    adb_shell(serial, ["shell", "input", "keyevent", "KEYCODE_WAKEUP"], timeout=15)
    adb_shell(serial, ["shell", "wm", "dismiss-keyguard"], timeout=15)
    time.sleep(1)

    # 3) 打开系统蓝牙设置页（该页会自动开始扫描附近设备）
    adb_shell(serial, ["shell", "am", "start", "-a", "android.settings.BLUETOOTH_SETTINGS"], timeout=30)
    time.sleep(3)
    note("已打开系统蓝牙设置")

    # 4) 轮询查找目标设备并点击
    tapped = False
    for attempt in range(6):
        nodes = _dump_ui_nodes(serial)
        if not nodes:
            note(f"第{attempt + 1}次：界面读取失败，重试…")
            time.sleep(2)
            continue
        hit = _find_ui_node(nodes, contains=target)
        if not hit:
            note(f"第{attempt + 1}次：暂未发现「{target}」，继续等待扫描…")
            time.sleep(2)
            continue
        adb_shell(serial, ["shell", "input", "tap", str(hit["x"]), str(hit["y"])], timeout=20)
        note(f"已点击「{hit['text']}」")
        tapped = True
        time.sleep(2)
        break
    if not tapped:
        return {"ok": False, "steps": steps,
                "error": f"未在蓝牙设置里找到「{target}」（请确认耳机已进入配对模式）"}

    # 5) 处理配对确认弹窗（不同 ROM 文案不同）
    for _ in range(3):
        nodes = _dump_ui_nodes(serial)
        btn = _find_ui_node(nodes, texts=("配对", "PAIR", "Pair", "确定", "OK", "连接", "Connect"))
        if btn:
            adb_shell(serial, ["shell", "input", "tap", str(btn["x"]), str(btn["y"])], timeout=20)
            note(f"点击确认「{btn['text']}」")
            time.sleep(2)
            break
        time.sleep(2)

    # 6) 校验是否已配对
    rc, out = adb_shell(serial, ["shell", "dumpsys", "bluetooth_manager"], timeout=30)
    devs = _parse_bt_devices(out or "")
    tl = target.lower()
    ok = any(tl in d["name"].lower() and d["bonded"] for d in devs)
    note("配对成功" if ok else "未能确认配对结果（设备可能仍需手动确认）")
    return {"ok": ok, "steps": steps, "error": "" if ok else "未确认配对成功"}


def check_all_wifi():
    """并行检测所有在线设备 WiFi，返回 [(device, list)]"""
    devs = list_devices()
    if not devs:
        return [], []
    results = {}
    ts = []
    for d in devs:
        t = threading.Thread(target=lambda s=d: results.__setitem__(s, check_one_wifi(s)),
                             daemon=True)
        t.start()
        ts.append(t)
    for t in ts:
        t.join(timeout=60)
    ls = [results.get(d) for d in devs]
    return devs, ls


def collect_device_info(serial):
    """收集单台设备 MAC + ANDROID_ID，返回 dict"""
    info = {"serial": serial, "mac": "", "android_id": ""}
    try:
        rc, out = adb_shell(serial, ["shell", "ip", "addr", "show", "wlan0"], timeout=20)
        for line in (out or "").splitlines():
            if "link/ether" in line:
                parts = line.split()
                if len(parts) >= 2:
                    info["mac"] = parts[1].lower()
                break
        rc, out = adb_shell(serial, ["shell", "settings", "get", "secure", "android_id"], timeout=20)
        info["android_id"] = (out or "").strip()
    except Exception as e:
        info["error"] = str(e)
    return info


def collect_device_id(serial, pkg, act, wait=20):
    """重启当前 Unity 应用（force-stop + am start，非重启设备），从 logcat 里
    [Heartbeat] 的 deviceId 提取 SystemInfo.deviceUniqueIdentifier（32 位 hex）。
    返回字符串，失败返回 ''。
    """
    pat = re.compile(r'"deviceId"\s*:\s*"([0-9a-fA-F]{32})"')
    found = ""
    try:
        adb_shell(serial, ["logcat", "-c"], timeout=10)     # 清空缓冲
        # 先停掉应用，再重新启动，强制重新发送 heartbeat（不重启设备）
        adb_shell(serial, ["shell", "am", "force-stop", pkg], timeout=30)
        time.sleep(2)
        adb_shell(serial, ["shell", "am", "start", "-n", f"{pkg}/{act}"], timeout=30)
        deadline = time.time() + wait
        while time.time() < deadline:
            rc, out = adb_shell(serial, ["logcat", "-d"], timeout=15)
            m = pat.search(out or "")
            if m:
                found = m.group(1).lower()
                break
            time.sleep(1)
    except Exception:
        pass
    return found


def collect_all_device_info():
    """并行收集所有在线设备的 MAC + ANDROID_ID"""
    devs = list_devices()
    if not devs:
        return [], []
    results = {}
    ts = []
    for d in devs:
        t = threading.Thread(target=lambda s=d: results.__setitem__(s, collect_device_info(s)),
                             daemon=True)
        t.start()
        ts.append(t)
    for t in ts:
        t.join(timeout=60)
    ls = [results.get(d) for d in devs]
    return devs, ls


def collect_all_device_id(serials=None, wait=30, pkg=None, act=None):
    """并行抓取所有（或指定）在线设备的 Unity deviceId，返回 {serial: device_id}"""
    pkg = pkg or DEFAULTS["PKG"]
    act = act or DEFAULTS["ACT"]
    if serials is None:
        serials = list_devices()
    if not serials:
        return {}
    results = {}
    ts = []
    for s in serials:
        t = threading.Thread(target=lambda ss=s: results.__setitem__(ss, collect_device_id(ss, pkg, act, wait=wait)),
                             daemon=True)
        t.start()
        ts.append(t)
    for t in ts:
        t.join(timeout=wait + 20)
    return results


def persist_device_id(serial, device_id):
    """把 device_id 写入持久化对照表（只增不减），并同步 STATUS 的 dev_info（不改 state）"""
    if not device_id:
        return
    with MACMAP_LOCK:
        fl = load_macmap()
        cur = fl.setdefault(serial, {})
        cur["device_id"] = device_id
        try:
            with open(MACMAP_FILE, "w", encoding="utf-8") as f:
                json.dump(fl, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
    with LOCK:
        if serial in STATUS and isinstance(STATUS[serial], dict):
            STATUS[serial].setdefault("dev_info", {})["device_id"] = device_id


def delete_macmap(serial):
    """从持久化对照表删除指定 serial 的记录，并清掉内存里对应 dev_info"""
    with MACMAP_LOCK:
        data = load_macmap()
        if serial in data:
            del data[serial]
        try:
            with open(MACMAP_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
    with LOCK:
        if serial in STATUS and isinstance(STATUS[serial], dict):
            STATUS[serial].pop("dev_info", None)


def reconnect_wifi(serial, ssid, wifi_pass):
    """单台设备重连 WiFi：确保开启 -> connect-network -> 验证，返回结果"""
    result = {"serial": serial, "ok": False, "connected": False,
              "ssid": ssid, "rssi": None, "speed": "", "steps": []}

    def st(title, ok, detail=""):
        result["steps"].append({"name": title, "ok": ok, "detail": detail})

    try:
        # 1. 确保 WiFi 开启
        rc, out = adb_shell(serial, ["shell", "settings", "get", "global", "wifi_on"], timeout=30)
        if out.strip() != "1":
            adb_shell(serial, ["shell", "svc", "wifi", "enable"], timeout=30)
            time.sleep(2)
            st("enable_wifi", True)

        # 2. 连接
        if not ssid:
            result["steps"].append({"name": "ssid_empty", "ok": False, "detail": "未填 SSID"})
            return result
        if wifi_pass:
            rc, out = adb_shell(serial, ["shell", "cmd", "wifi", "connect-network",
                                         ssid, "wpa2", wifi_pass], timeout=60)
        else:
            rc, out = adb_shell(serial, ["shell", "cmd", "wifi", "connect-network",
                                         ssid, "open"], timeout=60)
        st("connect", True, (out or "").strip())
        if "connection failed" in (out or "").lower():
            result["steps"].append({"name": "connect", "ok": False, "detail": (out or "").strip()})
        # 3. 轮询验证连接
        connected = False
        for attempt in range(1, 6):
            time.sleep(3)
            info = check_one_wifi(serial)
            if info["connected"] and ssid.lower() in (info.get("ssid") or "").lower():
                connected = True
                result.update(connected=True, rssi=info["rssi"], speed=info["speed"],
                              ssid=info["ssid"], ok=True)
                st("verify", True, f"第{attempt}次确认已连接 {ssid}")
                break
        if not connected:
            st("verify", False, f"未能确认连接到 {ssid}")
            last = check_one_wifi(serial)
            result["connected"] = last["connected"]
            result["ssid"] = last["ssid"]
    except Exception as e:
        st("error", False, str(e))
    return result


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload, ctype="application/json; charset=utf-8"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            path = "/index.html"
        if path == "/api/devices":
            detail = list_devices_detail()
            self._send(200, {
                "devices": [s for s, st in detail if st == "device"],
                "pending": [{"serial": s, "state": st} for s, st in detail if st != "device"],
            })
            return
        if path == "/api/battery":
            self._send(200, {"batteries": all_batteries()})
            return
        if path == "/api/status":
            with LOCK:
                data = []
                for ser, st in sorted(STATUS.items()):
                    cp = dict(st)
                    cp["logs"] = list(st["logs"])
                    cp["steps"] = list(st["steps"])
                    data.append(cp)
            self._send(200, {"devices": data})
            return
        if path == "/api/config":
            self._send(200, dict(DEFAULTS))
            return
        if path == "/api/version":
            lv = read_local_version()
            self._send(200, {"version": lv.get("version", ""), "has_file": bool(lv)})
            return
        if path == "/api/check_update":
            local = read_local_version().get("version", "")
            try:
                remote = fetch_remote_version()
            except Exception as e:
                self._send(200, {"ok": False, "local": local, "error": str(e)})
                return
            rv = remote.get("version", "")
            changed = []
            for rel, want in (remote.get("files", {}) or {}).items():
                fp = os.path.join(PROJECT_DIR, rel.replace("/", os.sep))
                if _md5_file(fp).lower() != str(want).lower():
                    changed.append(rel)
            has_update = (ver_tuple(rv) > ver_tuple(local)) or bool(changed)
            self._send(200, {"ok": True, "local": local, "remote": rv,
                             "has_update": has_update,
                             "changed": changed, "notes": remote.get("notes", "")})
            return
        if path == "/api/update_status":
            with LOCK:
                st = dict(UPDATE_STATE)
            st["progress"] = _read_update_progress()
            st["log"] = _read_update_log()
            st["version"] = read_local_version().get("version", "")
            self._send(200, st)
            return
        if path == "/api/apks":
            apks = [f for f in os.listdir(PROJECT_DIR)
                    if f.lower().endswith(".apk")]
            apks = sorted(apks, key=str.lower)
            self._send(200, {"apks": apks})
            return
        if path == "/api/apk_info":
            # 解析指定 APK 的包名与启动 Activity（相对项目目录，或不带路径时按目录内文件名）
            qs = self.path.split("?", 1)[1] if "?" in self.path else ""
            apk_name = ""
            for kv in qs.split("&"):
                if kv.startswith("apk="):
                    apk_name = urllib.parse.unquote(kv[4:])
                    break
            apk_path = os.path.basename(apk_name.strip())
            if not apk_path:
                self._send(400, {"error": "apk required"})
                return
            full = os.path.join(PROJECT_DIR, apk_path)
            if not os.path.isfile(full):
                self._send(404, {"error": f"APK not found: {apk_path}"})
                return
            pkg, act = parse_apk_manifest(full)
            self._send(200, {"apk": apk_path, "pkg": pkg or "", "act": act or ""})
            return
        if path == "/api/apps":
            # 列出指定设备已安装的第三方应用
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            serial = (qs.get("serial") or [""])[0].strip()
            if not serial:
                self._send(400, {"error": "serial required"})
                return
            self._send(200, list_installed_apps(serial))
            return
        if path == "/api/logcat":
            # 查看单台设备的 logcat 尾部（快照）。lines=尾部行数，spec=logcat 过滤器
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            serial = (qs.get("serial") or [""])[0].strip()
            if not serial:
                self._send(400, {"error": "serial required"})
                return
            try:
                lines = int((qs.get("lines") or ["600"])[0])
            except ValueError:
                lines = 600
            lines = max(50, min(lines, 3000))
            spec = (qs.get("spec") or [""])[0].strip()
            # 过滤表达式校验：拒绝含 - 开头（避免被 adb 当成选项）与非法字符
            if spec and (spec.startswith("-") or not LOGCAT_SPEC_OK.match(spec)):
                self._send(400, {"error": "invalid spec"})
                return
            self._send(200, dump_logcat(serial, lines, spec))
            return
        if path == "/api/fsa_status":
            fsa_dir = os.path.join(PROJECT_DIR, "fsa")
            files, bad = [], []
            if os.path.isdir(fsa_dir):
                for root, _dirs, fnames in os.walk(fsa_dir):
                    for fn in fnames:
                        if fn.lower().endswith(".meta"):
                            continue
                        rel = os.path.relpath(
                            os.path.join(root, fn), fsa_dir).replace("\\", "/")
                        (files if is_fsa_file(fn) else bad).append(rel)
            # 顺便报告当前选中 APK 是否内置 assets/aikit/fsa
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            apk_name = os.path.basename((q.get("apk") or [""])[0])
            apk_has_fsa, apk_fsa_count, apk_fsa_bad = None, 0, []
            if apk_name:
                apk_path = os.path.join(PROJECT_DIR, apk_name)
                if os.path.isfile(apk_path):
                    n, ab = fsa_in_apk_report(apk_path)
                    if n is not None:
                        apk_has_fsa, apk_fsa_count, apk_fsa_bad = n > 0, n, ab
            self._send(200, {"exists": os.path.isdir(fsa_dir),
                             "count": len(files), "files": files[:200],
                             "bad_count": len(bad), "bad_files": bad[:50],
                             "apk": apk_name,
                             "apk_has_fsa": apk_has_fsa,
                             "apk_fsa_count": apk_fsa_count,
                             "apk_fsa_bad": apk_fsa_bad[:50],
                             "local_apk": read_fsa_meta().get("apk", ""),
                             "local_for_apk": bool(apk_name and read_fsa_meta().get("apk") == apk_name)})
            return
        if path == "/api/prefs":
            # 上次填写的界面配置（WiFi 名称/密码），供前端自动回填
            self._send(200, load_prefs())
            return
        if path == "/api/wificheck":
            devs, results = check_all_wifi()
            summary = {"total": len(results),
                       "connected": sum(1 for r in results if r and r.get("connected")),
                       "wifi_off": sum(1 for r in results if r and not r.get("wifi_on"))}
            self._send(200, {"devices": devs, "wifi": results, "summary": summary})
            return
        if path == "/api/wifi_scan":
            # 用第一台在线设备扫描附近热点
            devs = list_devices()
            if not devs:
                self._send(200, {"ssids": [], "error": "no device"})
                return
            serial = (self.path.split("?serial=", 1)[1].split("&")[0]
                      if "serial=" in self.path else devs[0])
            ssids = scan_wifi(serial)
            self._send(200, {"serial": serial, "ssids": ssids})
            return
        if path == "/api/bt_scan":
            # 用第一台在线设备读取蓝牙设备（已配对/在线）
            devs = list_devices()
            if not devs:
                self._send(200, {"devices": [], "error": "no device"})
                return
            serial = (self.path.split("?serial=", 1)[1].split("&")[0]
                      if "serial=" in self.path else devs[0])
            result = scan_bluetooth(serial)
            self._send(200, {"serial": serial, **result})
            return
        if path == "/api/macmap":
            # auto=0 时只读文件历史记录，不自动采集/补全/落盘（删除记录后不会被重新加回）
            qs = self.path.split("?", 1)[1] if "?" in self.path else ""
            auto_collect = "auto=0" not in qs
            filemap = load_macmap()
            if not auto_collect:
                merged = {}
                order = []
                for s, di in filemap.items():
                    merged[s] = {"serial": s, "mac": di.get("mac", ""),
                                 "android_id": di.get("android_id", ""),
                                 "device_id": di.get("device_id", "")}
                    order.append(s)
                info_list = [merged[s] for s in order]
                self._send(200, {"devices": order, "info": info_list})
                return
            online, onl_info_list = collect_all_device_info()
            onl_info = dict(zip(online, onl_info_list))
            with LOCK:
                known = dict(STATUS)
            merged = {}   # serial -> info
            order = []
            # 1) 持久化文件里的历史记录（跨重启、含离线设备）
            for s, di in filemap.items():
                merged[s] = {"serial": s, "mac": di.get("mac", ""),
                             "android_id": di.get("android_id", ""),
                             "device_id": di.get("device_id", "")}
                order.append(s)
            # 2) 本次会话 STATUS 覆盖（含 /api/devicewifi 等收集到的）
            for s, rec in known.items():
                di = (rec or {}).get("dev_info") or {}
                if not (di.get("mac") or di.get("android_id") or di.get("device_id")):
                    continue
                if s not in merged:
                    merged[s] = {"serial": s, "mac": "", "android_id": "", "device_id": ""}
                    order.append(s)
                if di.get("mac"):
                    merged[s]["mac"] = di["mac"]
                if di.get("android_id"):
                    merged[s]["android_id"] = di["android_id"]
                if di.get("device_id"):
                    merged[s]["device_id"] = di["device_id"]
            # 3) 当前在线设备实时补全（含未装过的新设备）
            for s in online:
                if s not in merged:
                    merged[s] = onl_info.get(s) or {"serial": s, "mac": "", "android_id": "",
                                                    "device_id": ""}
                    order.append(s)
                else:
                    di = onl_info.get(s) or {}
                    if di.get("mac"):
                        merged[s]["mac"] = di["mac"]
                    if di.get("android_id"):
                        merged[s]["android_id"] = di["android_id"]
            # 把本次在线/已采集到的值 patch 进持久化文件（只增不减，防止并发丢记录）
            try:
                with MACMAP_LOCK:
                    fl = load_macmap()
                    for s, mi in merged.items():
                        if not (mi.get("mac") or mi.get("android_id") or mi.get("device_id")):
                            continue
                        cur = fl.setdefault(s, {})
                        if mi.get("mac"):
                            cur["mac"] = mi["mac"]
                        if mi.get("android_id"):
                            cur["android_id"] = mi["android_id"]
                        if mi.get("device_id"):
                            cur["device_id"] = mi["device_id"]
                    with open(MACMAP_FILE, "w", encoding="utf-8") as f:
                        json.dump(fl, f, ensure_ascii=False, indent=2)
            except Exception:
                pass
            info_list = [merged[s] for s in order]
            self._send(200, {"devices": order, "info": info_list})
            return
        # static files
        fname = os.path.normpath(os.path.join(BASE_DIR, path.lstrip("/")))
        if os.path.isfile(fname):
            if fname.endswith(".html"):
                ctype = "text/html; charset=utf-8"
            elif fname.endswith(".js"):
                ctype = "application/javascript; charset=utf-8"
            elif fname.endswith(".css"):
                ctype = "text/css; charset=utf-8"
            else:
                ctype = "application/octet-stream"
            try:
                with open(fname, "rb") as f:
                    self._send(200, f.read(), ctype)
                return
            except Exception:
                pass
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path.split("?")[0] == "/api/update":
            if not start_update():
                self._send(200, {"ok": False, "running": True, "error": "更新正在进行中"})
                return
            self._send(200, {"ok": True, "started": True})
            return
        if self.path.split("?")[0] == "/api/restart":
            # 重启主程序：本进程退出，由后台助手等 launcher.exe 替换完成后重新拉起
            exe = os.path.join(PROJECT_DIR, "launcher.exe")
            if not os.path.isfile(exe):
                self._send(400, {"ok": False, "error": "launcher.exe 不存在（命令行方式运行无需重启）"})
                return
            new = os.path.join(PROJECT_DIR, "launcher.exe.new")
            upd = os.path.join(PROJECT_DIR, "updater.ps1")
            helper = os.path.join(PROJECT_DIR, "_restart_helper.ps1")
            log = os.path.join(PROJECT_DIR, "_restart_helper.log")
            def _q(p):
                return p.replace("'", "''")
            script = (
                "$ErrorActionPreference = 'SilentlyContinue'\r\n"
                "$log = '%s'\r\n"
                "\"start\" | Out-File -LiteralPath $log -Encoding utf8\r\n"
                "Start-Sleep -Seconds 1\r\n"
                "Stop-Process -Name launcher -Force\r\n"
                "\"killed\" | Out-File -LiteralPath $log -Append -Encoding utf8\r\n"
                "$new = '%s'\r\n"
                "for ($i=0; $i -lt 120; $i++) { if (-not (Test-Path -LiteralPath $new)) { break }; Start-Sleep -Milliseconds 500 }\r\n"
                "\"waited\" | Out-File -LiteralPath $log -Append -Encoding utf8\r\n"
                "Start-Sleep -Milliseconds 600\r\n"
                "$upd = '%s'\r\n"
                "if (Test-Path -LiteralPath $upd) { & powershell -NoProfile -ExecutionPolicy Bypass -File $upd *>> $log }\r\n"
                "\"updater rc=$LASTEXITCODE\" | Out-File -LiteralPath $log -Append -Encoding utf8\r\n"
                "try { Start-Process -FilePath '%s' -WindowStyle Hidden -ErrorAction Stop; \"started\" | Out-File -LiteralPath $log -Append -Encoding utf8 }\r\n"
                "catch { \"ERR: $_\" | Out-File -LiteralPath $log -Append -Encoding utf8 }\r\n"
                "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force\r\n"
            ) % (_q(log), _q(new), _q(upd), _q(exe))
            try:
                with open(helper, "w", encoding="utf-8-sig") as f:
                    f.write(script)
                # 用 cmd start 拉起，进程完全脱离本进程；直接 Popen 的子进程会随本进程退出一起被杀。
                # 必须清掉 PyInstaller 的 _PYI_* 环境变量，否则助手再启动 launcher.exe 时，
                # bootloader 会误以为自己是被父进程拉起的子进程而直接退出（表现为「重启后没反应」）。
                env = {k: v for k, v in os.environ.items()
                       if not k.upper().startswith("_PYI") and k.upper() != "_MEIPASS2"}
                subprocess.Popen(
                    ["cmd", "/c", "start", "", "/min", "powershell",
                     "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", helper],
                    creationflags=0x08000000,
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    env=env,
                )
            except Exception as e:
                self._send(500, {"ok": False, "error": str(e)})
                return
            self._send(200, {"ok": True})
            threading.Timer(2.0, lambda: os._exit(0)).start()
            return
        if self.path.split("?")[0] == "/api/upload_apk":
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length <= 0:
                self._send(400, {"error": "empty body"})
                return
            if length > 1_600_000_000:   # 约 1.5GB 上限
                self._send(400, {"error": "file too large"})
                return
            data = self.rfile.read(length)
            name = urllib.parse.unquote(self.path.split("name=", 1)[1]) if "name=" in self.path else "upload.apk"
            fname = os.path.basename(name).strip()
            if not fname or fname.lower() == "blob":
                fname = "upload.apk"
            if not fname.lower().endswith(".apk"):
                fname += ".apk"
            dest = os.path.join(PROJECT_DIR, fname)
            try:
                with open(dest, "wb") as f:
                    f.write(data)
            except Exception as e:
                self._send(400, {"error": str(e)})
                return
            self._send(200, {"apk": fname})
            return
        if self.path.split("?")[0] == "/api/upload_fsa":
            # 上传 fsa.zip，解压覆盖到项目目录 fsa/（安装时自动推送到设备）
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length <= 0:
                self._send(400, {"error": "empty body"})
                return
            if length > 500_000_000:   # 约 500MB 上限
                self._send(400, {"error": "file too large"})
                return
            data = self.rfile.read(length)
            fsa_dir = os.path.join(PROJECT_DIR, "fsa")
            try:
                zf = zipfile.ZipFile(io.BytesIO(data))
                names = [n for n in zf.namelist()
                         if not n.endswith("/") and not n.lower().endswith(".meta")]
                if not names:
                    self._send(400, {"error": "zip is empty"})
                    return
                skipped = [n.replace("\\", "/") for n in names if not is_fsa_file(n)]
                names = [n for n in names if is_fsa_file(n)]
                if not names:
                    self._send(400, {"error": "zip 内没有 .txt 文件（设备只接受 txt，其他格式会读取异常）",
                                     "skipped_count": len(skipped)})
                    return
                # 整个 zip 只包含一个顶层 fsa/ 目录时剥掉该前缀
                prefix = ""
                if all(n.replace("\\", "/").split("/", 1)[0] == "fsa"
                       for n in zf.namelist()):
                    prefix = "fsa/"
                if os.path.isdir(fsa_dir):
                    shutil.rmtree(fsa_dir)
                os.makedirs(fsa_dir, exist_ok=True)
                count = 0
                for n in names:
                    rel = n.replace("\\", "/")
                    if prefix and rel.startswith(prefix):
                        rel = rel[len(prefix):]
                    if not rel or rel.startswith("/") or ".." in rel.split("/"):
                        continue  # 跳过非法路径，防 zip 穿越目录
                    dest = os.path.join(fsa_dir, *rel.split("/"))
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with zf.open(n) as src, open(dest, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    count += 1
                _q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                write_fsa_meta((_q.get("apk") or [""])[0])
                self._send(200, {"ok": True, "files": count,
                                 "skipped_count": len(skipped), "skipped": skipped[:50]})
            except Exception as e:
                self._send(400, {"error": str(e)})
            return
        if self.path.split("?")[0] == "/api/upload_fsa_file":
            # 文件夹直传：逐个文件上传，?rel=相对路径；首个文件带 first=1 时清空旧 fsa
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length <= 0:
                self._send(400, {"error": "empty body"})
                return
            if length > 500_000_000:
                self._send(400, {"error": "file too large"})
                return
            data = self.rfile.read(length)
            qs = urllib.parse.parse_qs(self.path.split("?", 1)[1]) if "?" in self.path else {}
            rel = (qs.get("rel", [""])[0]).replace("\\", "/").lstrip("/")
            first = qs.get("first", ["0"])[0] == "1"
            fsa_dir = os.path.join(PROJECT_DIR, "fsa")
            if not rel or ".." in rel.split("/"):
                self._send(400, {"error": "invalid rel path"})
                return
            if rel.lower().endswith(".meta") or not is_fsa_file(rel):
                self._send(200, {"ok": True, "rel": rel, "skipped": True})
                return
            try:
                if first and os.path.isdir(fsa_dir):
                    shutil.rmtree(fsa_dir)
                dest = os.path.join(fsa_dir, *rel.split("/"))
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as f:
                    f.write(data)
                if first:
                    write_fsa_meta((qs.get("apk", [""])[0]))
                self._send(200, {"ok": True, "rel": rel})
            except Exception as e:
                self._send(400, {"error": str(e)})
            return
        if self.path.split("?")[0] == "/api/bt_pair":
            # 蓝牙配对（UI 自动化）：打开系统蓝牙设置、点选目标设备完成配对
            data = self._read_body()
            target = (data.get("target") or "").strip()
            serial = (data.get("serial") or "").strip()
            devs = list_devices()
            if not devs:
                self._send(200, {"ok": False, "steps": [], "error": "no device"})
                return
            if not serial or serial not in devs:
                serial = devs[0]
            if not target:
                self._send(400, {"ok": False, "steps": [], "error": "target required"})
                return
            result = pair_bluetooth(serial, target)
            result["serial"] = serial
            self._send(200, result)
            return
        if self.path.split("?")[0] == "/api/adb":
            # ADB 终端：执行任意 adb 子命令（命令不带 adb 前缀，由后端以列表传参，无 shell 注入面）
            data = self._read_body()
            serial = (data.get("serial") or "").strip()
            cmd = (data.get("cmd") or "").strip()
            if not cmd:
                self._send(400, {"error": "cmd required"})
                return
            try:
                args = shlex.split(cmd, posix=True)
            except ValueError as e:
                self._send(400, {"error": f"命令解析失败：{e}"})
                return
            if serial and serial not in list_devices():
                self._send(200, {"rc": -1, "out": "",
                                 "error": f"设备未连接：{serial}（请先点「刷新设备」）"})
                return
            try:
                tmo = int(data.get("timeout") or 30)
            except (TypeError, ValueError):
                tmo = 30
            tmo = max(5, min(tmo, 120))
            self._send(200, adb_run(serial, args, tmo))
            return
        if self.path.split("?")[0] == "/api/logcat_clear":
            data = self._read_body()
            serial = (data.get("serial") or "").strip()
            if not serial:
                self._send(400, {"error": "serial required"})
                return
            self._send(200, clear_logcat(serial))
            return
        if self.path.split("?")[0] == "/api/prefs":
            data = self._read_body()
            ssid = (data.get("wifi_ssid") or "").strip()
            wifi_pass = data.get("wifi_pass")
            wifi_pass = "" if wifi_pass is None else str(wifi_pass)
            ok = save_prefs(ssid, wifi_pass)
            self._send(200, {"ok": ok})
            return
        if self.path.split("?")[0] == "/api/reboot":
            data = self._read_body()
            serials = data.get("serials") or []
            if not serials:
                serials = list_devices()
            for s in serials:
                threading.Thread(target=reboot_one, args=(s,)).start()
            self._send(200, {"ok": True, "started": len(serials)})
            return
        if self.path.split("?")[0] == "/api/scrcpy":
            data = self._read_body()
            serial = (data.get("serial") or "").strip()
            if not serial:
                self._send(400, {"ok": False, "error": "缺少 serial"})
                return
            ok, err = launch_scrcpy(serial)
            self._send(200, {"ok": ok, "error": err})
            return
        if self.path.split("?")[0] == "/api/poweroff":
            data = self._read_body()
            serials = data.get("serials") or []
            if not serials:
                serials = list_devices()
            for s in serials:
                threading.Thread(target=poweroff_one, args=(s,)).start()
            self._send(200, {"ok": True, "started": len(serials)})
            return
        if self.path.split("?")[0] == "/api/uninstall":
            data = self._read_body()
            serials = data.get("serials") or []
            pkg = (data.get("pkg") or DEFAULTS["PKG"]).strip()
            if not serials:
                serials = list_devices()
            for s in serials:
                threading.Thread(target=uninstall_one, args=(s, pkg)).start()
            self._send(200, {"ok": True, "started": len(serials)})
            return
        if self.path.split("?")[0] == "/api/install":
            data = self._read_body()
            wifissid = (data.get("wifi_ssid") or "").strip()
            wifipass = (data.get("wifi_pass") or "").strip()
            pkg = (data.get("pkg") or DEFAULTS["PKG"]).strip()
            act = (data.get("act") or DEFAULTS["ACT"]).strip()
            serials = data.get("serials") or []
            apk, err = resolve_apk(data)
            if err:
                self._send(400, {"error": err})
                return
            if not serials:
                serials = list_devices()
            for s in serials:
                with LOCK:
                    STATUS[s] = {"serial": s, "state": "running", "steps": [],
                                 "logs": [], "result": "RUNNING",
                                 "ok": 0, "fail": 0, "skip": 0, "dev_info": {}}
                t = threading.Thread(target=install_one,
                                     args=(s, apk, pkg, act, wifissid, wifipass),
                                     daemon=True)
                t.start()
            self._send(200, {"started": len(serials), "serials": serials})
            return
        if self.path.split("?")[0] == "/api/plain_install":
            data = self._read_body()
            serials = data.get("serials") or []
            grant_perms = bool(data.get("grant_perms", False))
            apk, err = resolve_apk(data)
            if err:
                self._send(400, {"error": err})
                return
            if not serials:
                serials = list_devices()
            for s in serials:
                with LOCK:
                    STATUS[s] = {"serial": s, "state": "running", "steps": [],
                                 "logs": [], "result": "RUNNING",
                                 "ok": 0, "fail": 0, "skip": 0, "dev_info": {}}
                t = threading.Thread(target=plain_install_one, args=(s, apk, grant_perms), daemon=True)
                t.start()
            self._send(200, {"started": len(serials), "serials": serials})
            return
        if self.path.split("?")[0] == "/api/devicewifi":
            data = self._read_body()
            ser = (data.get("serial") or "").strip()
            ssid = (data.get("ssid") or "").strip()
            wpass = (data.get("pass") or "").strip()
            if not ser:
                self._send(400, {"error": "serial required"})
                return
            res = reconnect_wifi(ser, ssid, wpass)
            self._send(200, res)
            return
        if self.path.split("?")[0] == "/api/deviceid":
            data = self._read_body()
            ser = (data.get("serial") or "").strip()
            pkg = (data.get("pkg") or "").strip()
            act = (data.get("act") or "").strip()
            if not ser:
                self._send(400, {"error": "serial required"})
                return
            pkg = pkg or DEFAULTS["PKG"]
            act = act or DEFAULTS["ACT"]
            did = collect_device_id(ser, pkg, act, wait=30)
            persist_device_id(ser, did)
            self._send(200, {"serial": ser, "device_id": did,
                             "ok": bool(did)})
            return
        if self.path.split("?")[0] == "/api/deviceid_all":
            data = self._read_body()
            pkg = (data.get("pkg") or "").strip()
            act = (data.get("act") or "").strip()
            pkg = pkg or DEFAULTS["PKG"]
            act = act or DEFAULTS["ACT"]
            serials = (data.get("serials") or None)
            shutdown = bool(data.get("shutdown"))
            results = collect_all_device_id(serials, wait=30, pkg=pkg, act=act)
            ok = sum(1 for v in results.values() if v)
            fail = sum(1 for v in results.values() if not v)
            # 全部落盘
            for s, did in results.items():
                persist_device_id(s, did)
            # 勾选「抓完自动关机」则对抓取成功的设备执行关机（真关机，非重启）
            shutdown_list = []
            if shutdown:
                for s, did in results.items():
                    if did:
                        try:
                            subprocess.run([ADB, "-s", s, "reboot", "-p"],
                                           capture_output=True, timeout=15)
                            shutdown_list.append(s)
                        except Exception:
                            pass
            info = [{"serial": s, "device_id": results.get(s, ""),
                     "ok": bool(results.get(s)),
                     "shutdown": s in shutdown_list}
                    for s in results]
            self._send(200, {"results": info, "total": len(results), "ok": ok,
                             "fail": fail, "shutdown": shutdown_list})
            return
        if self.path.split("?")[0] == "/api/macmap_delete":
            data = self._read_body()
            ser = (data.get("serial") or "").strip()
            if not ser:
                self._send(400, {"error": "serial required"})
                return
            delete_macmap(ser)
            self._send(200, {"ok": True})
            return
        self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass


def _free_port(port):
    """若端口已被占用，结束占用它的进程（通常是本工具的旧实例），让新实例直接接管。"""
    try:
        out = subprocess.run(["netstat", "-ano", "-p", "tcp"],
                             capture_output=True, timeout=10).stdout.decode("gbk", "replace")
    except Exception:
        return
    pids = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 4 and parts[0].upper() == "TCP" and parts[3].upper() == "LISTENING":
            if parts[1].endswith(":%d" % port):
                pids.add(parts[-1])
    for pid in pids:
        if pid and pid != "0":
            try:
                subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True, timeout=10)
            except Exception:
                pass
    if pids:
        time.sleep(0.6)


def _hide_console():
    """隐藏控制台黑窗（仅在打包成 launcher.exe 时调用；命令行调试不受影响）。"""
    try:
        import ctypes
        hwnd = ctypes.windll.kernel32.GetConsoleWindow()
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 0)  # SW_HIDE
    except Exception:
        pass


def main():
    _free_port(PORT)
    try:
        server = ThreadingHTTPServer((HOST, PORT), Handler)
    except OSError:
        time.sleep(0.8)
        _free_port(PORT)
        try:
            server = ThreadingHTTPServer((HOST, PORT), Handler)
        except OSError as e:
            if getattr(sys, "frozen", False):
                try:
                    import ctypes
                    ctypes.windll.user32.MessageBoxW(0, "无法占用端口 %d：%s" % (PORT, e),
                                                     "启动失败", 0x10)
                except Exception:
                    pass
            raise
    print(f"ADB      : {ADB}")
    print(f"Server   : http://{HOST}:{PORT}/")
    threading.Timer(0.8, lambda: webbrowser.open(f"http://{HOST}:{PORT}/")).start()
    if getattr(sys, "frozen", False):
        _hide_console()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
