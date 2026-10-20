# -*- coding: utf-8 -*-
"""在线更新器（取代原来的 updater.ps1）。

工作方式
--------
网页点「检查更新」→ 主程序把自己复制到 %TEMP% → 以 ``--update`` 启动副本 → 主进程退出。
副本不占用项目目录里的 launcher.exe，因此可以直接覆盖它，替换完再拉起新版。

也可以手工跑：``python web/updater.py --root <项目根>``

退出码
------
0  成功，或已是最新
1  部分文件下载/校验失败
2  主程序仍被占用，未能替换（新流程下不应出现）
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

REPO = "qq398768650/sygg-adb-tool"
BRANCH = "main"

# 多个下载源，逐个尝试（GitHub 直连在国内常被墙，用镜像兜底）
# 注意：不要加回 gcore.jsdelivr.net —— 它缓存过极旧的清单，曾导致客户端降级
MIRRORS = [
    "https://ghfast.top/https://raw.githubusercontent.com/%s/%s" % (REPO, BRANCH),
    "https://gh-proxy.com/https://raw.githubusercontent.com/%s/%s" % (REPO, BRANCH),
    "https://cdn.jsdelivr.net/gh/%s@%s" % (REPO, BRANCH),
    "https://raw.githubusercontent.com/%s/%s" % (REPO, BRANCH),
]

UA = "sygg-updater/2"
CHUNK = 128 * 1024
TMP_PREFIX = "sygg_updater_"


# ---------- 小工具 ----------
def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for buf in iter(lambda: f.read(1 << 20), b""):
            h.update(buf)
    return h.hexdigest()


def ver_tuple(v):
    """语义化版本 -> (主, 次, 修订)。非语义化（如旧的时间戳版本号）一律 0.0.0，
    避免某个镜像缓存了旧清单时被误判成「最高版本」把客户端降级。"""
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)", str(v or "").strip().lstrip("vV"))
    if not m:
        return (0, 0, 0)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def fmt_size(n):
    if n >= 1048576:
        return "%.1fMB" % (n / 1048576.0)
    if n >= 1024:
        return "%.0fKB" % (n / 1024.0)
    return "%d B" % n


class Progress(object):
    """进度同时写 .update.log（控制台/排查用）和 .update.progress.json（网页轮询用）。"""

    def __init__(self, root):
        self.root = root
        self.log_path = os.path.join(root, ".update.log")
        self.prog_path = os.path.join(root, ".update.progress.json")
        self.index = 0
        self.count = 0
        try:
            os.remove(self.log_path)
        except OSError:
            pass

    def log(self, msg):
        try:
            with open(self.log_path, "a", encoding="utf-8") as f:
                f.write(msg + "\n")
        except OSError:
            pass
        try:
            sys.stdout.write(msg + "\n")
            sys.stdout.flush()
        except Exception:
            pass

    def state(self, phase, msg, file="", done=0, size=0, pct=None, index=None, count=None):
        if index is None:
            index = self.index
        if count is None:
            count = self.count
        if pct is None:
            pct = int(min(100, done * 100 // size)) if size > 0 else 0
        if phase == "done":
            overall = 100
        elif count > 0:
            overall = int(((index - 1) + pct / 100.0) * 100 / count)
            overall = max(0, min(100, overall))
        else:
            overall = 0
        obj = {"phase": phase, "msg": msg, "file": file, "index": index, "count": count,
               "done": done, "size": size, "pct": pct, "overall": overall,
               "ts": int(time.time())}
        # 原子写：先写 .tmp 再 replace，避免前端读到写了一半的 JSON
        tmp = self.prog_path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(obj, f, ensure_ascii=False)
            os.replace(tmp, self.prog_path)
        except OSError:
            pass


# ---------- 下载 ----------
def _open(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    return urllib.request.urlopen(req, timeout=timeout)


def download(rel, dest, want, prog, max_time=180):
    """依次尝试各镜像把 rel 下载到 dest；给了 want 时要求 MD5 命中才采用。"""
    # 文件名可能含中文（如 启动.bat），URL 路径必须转义
    rel_esc = urllib.parse.quote(rel, safe="/")
    for i, base in enumerate(MIRRORS, 1):
        url = "%s/%s?t=%d" % (base, rel_esc, int(time.time()))
        try:
            resp = _open(url)
            size = int(resp.headers.get("Content-Length") or 0)
            done = 0
            last = 0.0
            with open(dest, "wb") as f:
                while True:
                    buf = resp.read(CHUNK)
                    if not buf:
                        break
                    f.write(buf)
                    done += len(buf)
                    now = time.time()
                    if now - last >= 0.15:
                        last = now
                        prog.log("  [%d/%d] %s %d%% %s / %s"
                                 % (prog.index, prog.count, rel,
                                    (done * 100 // size) if size else 0,
                                    fmt_size(done), fmt_size(size) if size else "?"))
                        prog.state("download", "下载 " + rel, rel, done, size)
            try:
                resp.close()
            except Exception:
                pass
            if done <= 0:
                raise IOError("下载内容为空")
            if want and md5_file(dest) != want:
                prog.log("  [校验] %s 来自镜像 %d 的内容不一致，换下一个镜像" % (rel, i))
                os.remove(dest)
                continue
            prog.state("download", "下载 " + rel, rel, done, size or done, 100)
            return True
        except Exception as e:
            prog.log("  [镜像%d] %s 失败：%s" % (i, rel, e))
            try:
                os.remove(dest)
            except OSError:
                pass
    return False


# ---------- 替换 / 重启 ----------
def replace_file(tmp, target):
    """把 tmp 覆盖到 target。父进程已退出，通常一次就成；留重试兜底。"""
    for _ in range(120):
        try:
            os.replace(tmp, target)
            return True
        except OSError:
            time.sleep(0.5)
    return False


def launch_main(root):
    """拉起新版 launcher.exe（脱离本进程）。"""
    exe = os.path.join(root, "launcher.exe")
    if not os.path.isfile(exe):
        return False
    # 必须剔除 _PYI_* —— 否则 PyInstaller bootloader 会误以为自己是子进程而直接退出
    env = {k: v for k, v in os.environ.items()
           if not k.upper().startswith("_PYI") and k.upper() != "_MEIPASS2"}
    try:
        subprocess.Popen([exe], cwd=root, env=env, close_fds=True,
                         creationflags=0x00000008 | 0x08000000)  # DETACHED_PROCESS | CREATE_NO_WINDOW
        return True
    except Exception:
        return False


def cleanup_old_copies():
    """删掉 %TEMP% 里上次留下的更新器副本（当前这个删不掉，留给下次）。"""
    d = os.path.dirname(os.path.abspath(sys.executable if getattr(sys, "frozen", False) else __file__))
    cur = os.path.basename(sys.executable if getattr(sys, "frozen", False) else __file__)
    try:
        for name in os.listdir(d):
            if name.startswith(TMP_PREFIX) and name != cur:
                try:
                    os.remove(os.path.join(d, name))
                except OSError:
                    pass
    except OSError:
        pass


# ---------- 主流程 ----------
def run_update(root, restart=True):
    root = os.path.abspath(root)
    prog = Progress(root)
    local_ver_path = os.path.join(root, "version.json")
    ts = int(time.time())

    prog.log("[update] 检查最新版本...")
    prog.state("checking", "检查远端版本")

    # 取各镜像中版本号最高的 version.json（避免某个镜像缓存旧版本导致误判）
    remote = None
    for i, base in enumerate(MIRRORS, 1):
        prog.state("checking", "获取远端版本（镜像 %d/%d）" % (i, len(MIRRORS)))
        try:
            with _open("%s/version.json?t=%d" % (base, ts), timeout=10) as r:
                raw = r.read()
            obj = json.loads(raw.decode("utf-8-sig"))
            if not obj.get("version"):
                continue
            if remote is None or ver_tuple(obj["version"]) > ver_tuple(remote["version"]):
                remote = obj
        except Exception:
            continue

    if remote is None:
        prog.log("[update] 无法获取远端版本（网络不可用），跳过更新。")
        prog.state("offline", "网络不可用，跳过更新", "", 0, 0, 100)
        return 0

    local_version = ""
    try:
        with open(local_ver_path, "r", encoding="utf-8-sig") as f:
            local_version = json.load(f).get("version") or ""
    except Exception:
        pass

    prog.log("[update] 本地 %s / 远端 %s" % (local_version or "(无)", remote["version"]))

    # 本地不旧于远端则跳过，绝不把本地更新版本降级
    if local_version and ver_tuple(remote["version"]) <= ver_tuple(local_version):
        prog.log("[update] 已是最新版本 %s" % local_version)
        prog.state("uptodate", "已是最新版本 %s" % local_version, "", 0, 0, 100)
        return 0

    files = [str(k) for k in (remote.get("files") or {})]
    # 主程序放最后：万一中途失败，至少其他文件是齐的，不会出现半新半旧的组合
    files.sort(key=lambda n: (n.lower() == "launcher.exe", n))
    prog.count = len(files)

    failed = []
    changed = False
    for idx, rel in enumerate(files, 1):
        prog.index = idx
        want = str((remote.get("files") or {}).get(rel, "")).lower()
        target = os.path.join(root, rel.replace("/", os.sep))
        if os.path.isfile(target) and md5_file(target) == want:
            continue
        if not os.path.isfile(target) and not want:
            continue
        changed = True
        parent = os.path.dirname(target)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent, exist_ok=True)
        prog.log("  [下载] %s（%d/%d）" % (rel, idx, len(files)))
        prog.state("download", "下载 " + rel, rel, 0, 0, 0)
        tmp = target + ".new"
        if not download(rel, tmp, want, prog):
            prog.log("  [失败] %s 下载失败或各镜像内容不一致" % rel)
            try:
                os.remove(tmp)
            except OSError:
                pass
            failed.append(rel)
            continue
        if replace_file(tmp, target):
            prog.log("  [完成] %s 已更新" % rel)
        else:
            prog.log("  [占用] %s 无法替换（仍被占用）" % rel)
            try:
                os.remove(tmp)
            except OSError:
                pass
            failed.append(rel)

    if failed:
        prog.log("[update] 部分文件更新失败：%s" % ", ".join(failed))
        prog.state("error", "部分文件更新失败，请稍后重试", "", 0, 0, 0)
        # 调用方（主程序）已经退出，必须把服务拉回来，否则用户界面直接没了
        if restart:
            launch_main(root)
        return 1

    # 全部文件均到位后才写 version.json，避免版本号虚高导致下次误判「已是最新」
    try:
        with open(local_ver_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(remote, f, ensure_ascii=False, indent=4)
    except OSError as e:
        prog.log("[update] 写入 version.json 失败：%s" % e)

    if changed:
        prog.log("[update] 更新完成。")
    else:
        prog.log("[update] 已是最新版本 %s" % remote["version"])

    if restart:
        # 主程序已经把 launcher.exe 让出来了，不管有没有变化都得把服务拉回去
        prog.state("restart", "更新完成，正在重启" if changed else "已是最新，正在重启")
        launch_main(root)
    else:
        prog.state("done", "更新完成" if changed else "已是最新版本 %s" % remote["version"],
                   "", 0, 0, 100)
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    restart = True
    if "--root" in argv:
        i = argv.index("--root")
        if i + 1 < len(argv):
            root = argv[i + 1]
    if "--no-restart" in argv:
        restart = False
    cleanup_old_copies()
    try:
        return run_update(root, restart=restart)
    except Exception as e:
        try:
            with open(os.path.join(root, ".update.log"), "a", encoding="utf-8") as f:
                f.write("[update] 未捕获异常：%r\n" % (e,))
        except OSError:
            pass
        return 1


if __name__ == "__main__":
    sys.exit(main())
