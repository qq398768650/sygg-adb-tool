package com.hsy.devupdate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 从景区发布后台（cdn2）拉各项目最新版，下载并安装。 */
public class UpdateActivity extends Activity {

    private static final String CDN = "http://cdn2.vr.hoseavr.com/arasset/";
    private static final String ACTION_INSTALL =
            "com.hsy.devupdate.INSTALL_RESULT";

    // 与 Unity 工程 Assets/Editor/ScenicProjectMap.json 保持一致（新增景区要同步这里，否则列表里看不到）
    /** 内置兜底清单：网络拉不到 projects.json 时用它。新增景区请改 devupdate-app/projects.json，不用动这里。 */
    private static final String[][] DEFAULT_PROJECTS = {
            {"shenyang_palace", "沈阳故宫"},
            {"huangcheng", "皇城相府"},
            {"niangziguan", "娘子关"},
            {"yun_gang_shi_ku", "云冈石窟"},
            {"song_chao_bu_ye_cheng", "宋潮不夜城"},
            {"qiao_jia_da_yuan", "乔家大院"},
            {"anhui_xidi", "安徽西递"},
            {"jie_zhou_guan_di_miao", "解州关帝庙"},
            {"zhang_bi_gu_bao", "张壁古堡"},
            {"yu_ci_xian_ya", "榆次县衙"},
            {"qufu_san_kong", "曲阜三孔"},
            {"qinghuiyuan", "顺德清晖园"},
            {"shenxi", "山西"},
            {"ying_xian_mu_ta", "应县木塔"},
    };

    /**
     * 景区清单的来源（按顺序试）：发布后台根目录的 projects.json 优先，
     * 其次是我们自己仓库里的那份（jsDelivr）。都拿不到就用内置清单。
     */
    private static final String[] PROJECT_MIRRORS = {
            "http://cdn2.vr.hoseavr.com/arasset/projects.json",
            "https://cdn.jsdelivr.net/gh/qq398768650/sygg-adb-tool@main/devupdate-app/projects.json",
    };

    static class Proj {
        String key;
        String name;
        int idx;
        int latestVc = -1;
        int localVc = -1;
        String pkg = "";
        String versionName = "";
        String notes = "";
        /** 最新版本的包体大小（字节），后台没给就是 0 */
        long size = 0;
        /** 本机这个包的最后更新时间（毫秒），未安装是 0；用来把最近装过的排最前 */
        long updatedAt = 0;
        boolean loading = true;
        String error = "";
        boolean busy = false;
        /** 下载进度百分比（0~100），只用于列表里显示「下载中 45%」 */
        int pct = 0;
        /** 当前状态的一句话（差分合成中 / 排队安装 …） */
        String note = "";
    }

    private final List<Proj> items = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView list;
    private TextView status;
    private ProgressBar progress;
    private UpAdapter adapter;
    private boolean downloading = false;
    /** 活跃下载任务数：大于 0 时列表刷新要缓一缓，避免把正在下载的行状态冲掉 */
    private int dlCount = 0;
    /** 下载完等待安装的任务（一次只弹一个系统安装框） */
    private final java.util.ArrayDeque<Job> jobs = new java.util.ArrayDeque<Job>();
    private boolean installing = false;
    /** 界面是否还活着：下载线程跑完时界面可能已经关了，这时不再弹安装框（下次点会走缓存直接装） */
    private volatile boolean alive = true;
    /** 当前正在安装的那个任务（安装回调回来时用它清状态） */
    private Job current = null;

    /** 一个待安装任务：下载好的 APK + 对应的项目 */
    static class Job {
        final File apk;
        final Proj p;
        Job(File apk, Proj p) { this.apk = apk; this.p = p; }
    }
    /** 本次下载方式的一句话结论（差分复用 N 块 / 整包 X），安装完成后仍显示给用户看 */
    private volatile String readyInfo = "";
    /** 本次正在安装的包名，安装成功后用它补授权 */
    private volatile String installingPkg = "";
    /** 本次装的是不是新应用（设备上原本没有），只有新装才需要补授权 */
    private volatile boolean installingIsNew = false;

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // 没有 Device Owner，系统把「是否安装」交给用户确认，这里必须自己弹出
                Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(confirm);
                        setStatus(readyInfo.isEmpty() ? "请在弹框里按「安装」"
                                : readyInfo + " · 请在弹框里按「安装」");
                    } catch (Exception e) {
                        setStatus("打不开安装确认框：" + e);
                    }
                } else {
                    setStatus("安装未完成：系统要求确认，但没拿到确认界面");
                }
                return;
            }
            if (st == PackageInstaller.STATUS_SUCCESS) {
                final String base = (readyInfo.isEmpty() ? "" : readyInfo + " · ") + "安装完成";
                setStatus(base);
                final String gp = installingPkg;
                final boolean isNew = installingIsNew;
                if (isNew && gp != null && !gp.isEmpty()) {
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final boolean ok = Grants.grant(UpdateActivity.this, gp);
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    setStatus(base + (ok ? " · 权限已自动授予"
                                            : " · 权限未授予（调试端口没开，可在主页点「一键授权」）"));
                                }
                            });
                        }
                    }).start();
                } else if (!isNew) {
                    // 覆盖安装：系统保留原有授权，不用再授一遍
                    setStatus(base + " · 权限沿用原有授权");
                }
                installing = false;
                if (current != null) { current.p.note = ""; current = null; }
                loadAll();
                ui.post(new Runnable() {
                    @Override public void run() { pumpInstall(); }
                });
            } else if (st != -999) {
                installing = false;
                if (current != null) { current.p.note = ""; current = null; }
                setStatus("安装未完成（status=" + st + "）" + (msg == null ? "" : "：" + msg));
                pumpInstall();
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_update);
        list = findViewById(R.id.up_list);
        status = findViewById(R.id.up_status);
        progress = findViewById(R.id.up_progress);
        adapter = new UpAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onPick(items.get(position));
            }
        });
        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadAll(); }
        });
        registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALL));
        buildItems(DEFAULT_PROJECTS);
        loadAll();
        loadProjects();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installReceiver); } catch (Exception ignored) {}
        // 用 shutdown 而不是 shutdownNow：正在下载的线程要让它把文件写完，
        // 否则用户返回主界面就等于把 1GB 的下载白扔了（下次还得从头来）。
        alive = false;
        pool.shutdown();
        super.onDestroy();
    }

    private void buildItems(String[][] src) {
        items.clear();
        int i = 0;
        for (String[] p : src) {
            Proj pr = new Proj();
            pr.key = p[0];
            pr.name = p[1];
            pr.idx = i++;
            items.add(pr);
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * 从网络拉景区清单：新景区加进后台/仓库的 projects.json 就能出现，不用重装 APK。
     * 拉不到就继续用内置清单，不影响使用。
     */
    private void loadProjects() {
        pool.execute(new Runnable() {
            @Override public void run() {
                for (String u : PROJECT_MIRRORS) {
                    String url = u + (u.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
                    JSONObject o = getJson(url);
                    if (o == null) continue;
                    JSONArray a = o.optJSONArray("projects");
                    if (a == null || a.length() == 0) continue;
                    final List<String[]> got = new ArrayList<String[]>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject it = a.optJSONObject(i);
                        if (it == null) continue;
                        String k = it.optString("key", "").trim();
                        String n = it.optString("name", "").trim();
                        if (!k.isEmpty() && !n.isEmpty()) got.add(new String[]{k, n});
                    }
                    if (got.isEmpty()) continue;
                    ui.post(new Runnable() {
                        @Override public void run() { applyProjects(got); }
                    });
                    return;
                }
            }
        });
    }

    /** 网络清单和当前列表不一样就重建并重新检查版本。 */
    private void applyProjects(List<String[]> got) {
        boolean same = got.size() == items.size();
        if (same) {
            for (int i = 0; i < got.size(); i++) {
                if (!got.get(i)[0].equals(items.get(i).key)) { same = false; break; }
            }
        }
        if (same) return;
        buildItems(got.toArray(new String[got.size()][]));
        loadAll();
        toast("景区清单已更新（" + got.size() + " 个）");
    }

    private void loadAll() {
        if (downloading) return;
        setStatus("正在连接发布后台…");
        progress.setVisibility(View.GONE);
        for (Proj p : items) {
            p.loading = true;
            p.error = "";
            p.latestVc = -1;
            p.size = 0;
            p.updatedAt = 0;
            pool.execute(new LoadTask(p));
        }
        adapter.notifyDataSetChanged();
    }

    private class LoadTask implements Runnable {
        final Proj p;
        LoadTask(Proj p) { this.p = p; }
        @Override public void run() {
            try {
                JSONObject idx = getJson(CDN + p.key + "/index.json");
                if (idx == null) throw new RuntimeException("后台暂无发布记录");
                int cur = idx.optInt("currentVersionCode", 0);
                JSONObject best = null;
                JSONArray arr = idx.optJSONArray("versions");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject v = arr.optJSONObject(i);
                        if (v == null) continue;
                        if (v.optInt("versionCode") == cur) { best = v; break; }
                    }
                }
                if (best == null) throw new RuntimeException("清单里没有当前版本");
                p.latestVc = cur;
                p.pkg = best.optString("packageName", "");
                p.versionName = best.optString("versionName", "");
                p.notes = best.optString("notes", "");
                p.size = best.optLong("totalBytes", 0);
                if (p.size < 1024 * 1024) {
                    // index.json 里的 totalBytes 有的项目写错（747 B / 904 B 这种），以 meta.json 的 size 为准
                    p.size = 0;
                    JSONObject meta = getJson(CDN + p.key + "/v" + cur + "/meta.json");
                    if (meta != null) p.size = meta.optLong("size", 0);
                }
                p.localVc = installedVersion(p.pkg);
                p.updatedAt = installedAt(p.pkg);
                p.error = "";
            } catch (Exception e) {
                p.error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                p.loading = false;
                ui.post(new Runnable() {
                    @Override public void run() {
                        adapter.notifyDataSetChanged();
                        updateSummary();
                    }
                });
            }
        }
    }

    private void updateSummary() {
        if (downloading) return;
        int pending = 0, done = 0, updatable = 0;
        for (Proj p : items) {
            if (p.loading) pending++;
            else {
                done++;
                if (p.error.isEmpty() && p.latestVc > 0 && p.localVc < p.latestVc) updatable++;
            }
        }
        if (pending > 0) setStatus("正在检查… 已完成 " + done + "/" + items.size());
        else {
            sortItems();
            adapter.notifyDataSetChanged();
            setStatus("检查完成 · " + updatable + " 个可以更新（共 " + items.size() + " 个项目）");
        }
    }

    /** 已安装的按「最后更新时间」倒序排最前（刚装完的排第一），未安装的按可更新优先。 */
    private void sortItems() {
        Collections.sort(items, new Comparator<Proj>() {
            @Override public int compare(Proj a, Proj b) {
                boolean ia = a.localVc >= 0, ib = b.localVc >= 0;
                if (ia && ib) {
                    if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt ? -1 : 1;
                } else if (ia != ib) {
                    return ia ? -1 : 1;
                }
                int ra = rank(a), rb = rank(b);
                return ra != rb ? ra - rb : a.idx - b.idx;
            }
        });
    }

    private int rank(Proj p) {
        if (!p.error.isEmpty() || p.latestVc <= 0) return 2;
        return p.localVc < p.latestVc ? 0 : 1;
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    /**
     * 刷新下载区：允许多个项目同时下载，状态行列出每个任务，进度条显示平均进度。
     * 只在主线程调用。
     */
    private void refreshDl() {
        int n = 0, sum = 0;
        StringBuilder sb = new StringBuilder();
        for (Proj x : items) {
            if (!x.busy) continue;
            n++;
            sum += x.pct;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(x.name).append(' ').append(x.pct).append('%');
            if (x.note != null && !x.note.isEmpty()) sb.append('（').append(x.note).append('）');
        }
        adapter.notifyDataSetChanged();
        if (n == 0) return;
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(sum / n);
        setStatus("正在下载 " + n + " 个：" + sb);
    }

    private void onPick(Proj p) {
        if (p.busy) { toast("这个已经在下载了，等它下完"); return; }
        if (p.loading) { toast("还在检查这个项目"); return; }
        if (!p.error.isEmpty()) { toast("检查失败：" + p.error); return; }
        if (p.localVc >= p.latestVc) {
            // 已经是最新：问一下要不要直接打开它（现场想看效果时不用再点遥控器找图标）
            final Proj target = p;
            new AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setMessage("本机已是最新 v" + p.localVc + "，要现在打开它吗？")
                    .setPositiveButton("打开", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) { openApp(target); }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        download(p);
    }

    private void download(final Proj p) {
        dlCount++;
        downloading = true;
        p.busy = true;
        p.pct = 0;
        p.note = "";
        refreshDl();
        pool.execute(new Runnable() {
            @Override public void run() {
                File out = null;
                try {
                    JSONObject meta = getJson(CDN + p.key + "/v" + p.latestVc + "/meta.json");
                    if (meta == null) throw new RuntimeException("读不到 meta.json");
                    String apkName = "";
                    long apkSize = 0;
                    String patchName = "";
                    JSONArray files = meta.optJSONArray("files");
                    if (files != null) {
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject f = files.optJSONObject(i);
                            if (f == null) continue;
                            String nm = f.optString("name", "");
                            String low = nm.toLowerCase();
                            if (low.endsWith(".apk") && apkName.isEmpty()) {
                                apkName = nm;
                                apkSize = f.optLong("size", 0);
                            } else if (low.endsWith(".json") && low.contains("patch")) {
                                patchName = nm;
                            }
                        }
                    }
                    if (apkName.isEmpty()) throw new RuntimeException("meta.json 里没有 APK");
                    String url = CDN + p.key + "/v" + p.latestVc + "/" + apkName;
                    File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    out = new File(dir, apkName);
                    if (out.exists() && apkSize > 0 && out.length() == apkSize) {
                        // 上次已经下好/合成好（比如用户取消了安装），直接用，不重复下载
                        readyInfo = "用上次下载好的安装包 " + human(apkSize);
                    } else {
                        boolean patched = false;
                        File oldApk = installedApk(p.pkg);
                        android.util.Log.i("DevUpdate", "更新 " + p.key + " patch=" + patchName
                                + " old=" + (oldApk == null ? "null" : oldApk.getAbsolutePath()));
                        if (!patchName.isEmpty() && oldApk != null) {
                            ui.post(new Runnable() {
                                @Override public void run() { setStatus("正在差分更新（只下变化的块）…"); }
                            });
                            PatchBuilder.Result pr = PatchBuilder.build(
                                    oldApk,
                                    CDN + p.key + "/v" + p.latestVc + "/" + patchName,
                                    url, out,
                                    new PatchBuilder.Progress() {
                                        @Override public void onProgress(int pct, String note) {
                                            final int v = Math.max(0, Math.min(100, pct));
                                            final String s = note;
                                            p.pct = v;
                                            p.note = s;
                                            ui.post(new Runnable() {
                                                @Override public void run() { refreshDl(); }
                                            });
                                        }
                                    });
                            patched = pr.ok;
                            if (patched) {
                                final String info = "差分完成：复用 " + pr.localBlocks
                                        + " 块，实际下载 " + human(pr.downloaded)
                                        + "（整包 " + human(pr.newSize) + "）";
                                readyInfo = info;
                                ui.post(new Runnable() {
                                    @Override public void run() { setStatus(info); }
                                });
                            } else {
                                final String why = pr.error;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        setStatus("差分不可用（" + why + "），改为下载整包…");
                                    }
                                });
                            }
                        } else if (!patchName.isEmpty()) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    setStatus("读不到本地旧包，直接下载整包…");
                                }
                            });
                        }
                        if (!patched) {
                            downloadFile(url, out, apkSize, p);
                            readyInfo = "整包下载 " + human(apkSize);
                        }
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            progress.setProgress(100);
                            setStatus("下载完成，正在安装…");
                        }
                    });
                    final File fin = out;
                    ui.post(new Runnable() {
                        @Override public void run() { enqueueInstall(fin, p); }
                    });
                } catch (Exception e) {
                    final String msg = (e.getMessage() == null ? e.toString() : e.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() {
                            setStatus("下载失败：" + msg);
                            toast("下载失败：" + msg);
                        }
                    });
                } finally {
                    p.busy = false;
                    p.note = "";
                    dlCount--;
                    if (dlCount < 0) dlCount = 0;
                    if (dlCount == 0) downloading = false;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            refreshDl();   // 还有别的任务在跑就继续显示汇总
                            if (dlCount == 0) progress.setVisibility(View.GONE);
                        }
                    });
                }
            }
        });
    }

    /** 启动已安装的 App（用 PackageManager 找 LAUNCHER 入口）。 */
    private void openApp(Proj p) {
        if (p.pkg == null || p.pkg.isEmpty()) { toast("不知道包名，打不开"); return; }
        Intent i = getPackageManager().getLaunchIntentForPackage(p.pkg);
        if (i == null) { toast("没找到启动入口：" + p.pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("打不开：" + e);
        }
    }

    /** 已安装包的 APK 路径（差分要用它当「旧包」）。读不到返回 null。 */
    private File installedApk(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            if (pi == null || pi.applicationInfo == null) return null;
            String path = pi.applicationInfo.publicSourceDir;
            if (path == null || path.isEmpty()) path = pi.applicationInfo.sourceDir;
            if (path == null || path.isEmpty()) return null;
            File f = new File(path);
            return f.exists() && f.canRead() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 下载整包；已有部分文件时用 Range 续传，别让中断一次就白下一遍。 */
    private void downloadFile(String urlStr, File out, long total, final Proj p) throws Exception {
        long have = out.exists() ? out.length() : 0;
        if (total > 0 && have >= total) return;           // 已经下完了
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        boolean append;
        if (code == 206) {
            append = true;                                // 服务器认了断点
        } else if (code == 200) {
            append = false; have = 0;                     // 从头给（服务器不支持 Range）
        } else if (code == 416) {
            return;                                       // 本地已经完整
        } else {
            throw new RuntimeException("HTTP " + code);
        }
        long len = c.getContentLengthLong();
        if (len <= 0) len = total > 0 ? total : have;
        final long totalLen = total > 0 ? total : (have + len);
        InputStream is = c.getInputStream();
        FileOutputStream os = new FileOutputStream(out, append);
        byte[] buf = new byte[131072];
        long got = have;
        int n;
        int lastPct = -1;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
            got += n;
            if (totalLen > 0) {
                final int pct = (int) (got * 100 / totalLen);
                if (pct != lastPct) {
                    lastPct = pct;
                    p.pct = pct;
                    ui.post(new Runnable() {
                        @Override public void run() { refreshDl(); }
                    });
                }
            }
        }
        os.flush();
        os.close();
        is.close();
        c.disconnect();
        if (total > 0 && out.length() != total) {
            throw new RuntimeException("下载不完整（" + out.length() + "/" + total + "），再点一次会从断点继续");
        }
    }

    /** 下载完的任务排进安装队列——系统一次只弹一个安装确认框，串着来才不会互相顶掉。 */
    private void enqueueInstall(File apk, Proj p) {
        synchronized (jobs) { jobs.add(new Job(apk, p)); }
        p.note = "排队安装";
        adapter.notifyDataSetChanged();
        pumpInstall();
    }

    /** 取队列里的下一个任务开始安装；安装回调回来后会再调一次。 */
    private void pumpInstall() {
        if (!alive || installing) return;
        Job j;
        synchronized (jobs) { j = jobs.poll(); }
        if (j == null) return;
        installing = true;
        current = j;
        installingPkg = j.p.pkg;
        installingIsNew = j.p.localVc < 0;
        j.p.note = "安装中";
        adapter.notifyDataSetChanged();
        try {
            installApk(j.apk, j.p.pkg);
        } catch (Exception e) {
            installing = false;
            j.p.note = "";
            current = null;
            setStatus("安装失败（" + j.p.name + "）：" + e.getMessage());
            pumpInstall();
        }
    }

    private void installApk(File apk, String pkg) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (pkg != null && !pkg.isEmpty()) sp.setAppPackageName(pkg);
        int sid = pi.createSession(sp);
        PackageInstaller.Session session = pi.openSession(sid);
        try {
            OutputStream os = session.openWrite("base.apk", 0, apk.length());
            FileInputStream is = new FileInputStream(apk);
            byte[] buf = new byte[131072];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            session.fsync(os);
            os.close();
            is.close();
        } finally {
            session.close();
        }
        Intent cb = new Intent(ACTION_INSTALL).setPackage(getPackageName());
        PendingIntent pi2 = PendingIntent.getBroadcast(this, sid, cb,
                PendingIntent.FLAG_UPDATE_CURRENT);
        session.commit(pi2.getIntentSender());
        ui.post(new Runnable() {
            @Override public void run() {
                setStatus("已提交安装，请用遥控器按「安装」确认");
            }
        });
    }

    private int installedVersion(String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 本机这个包的最后更新时间（毫秒），未安装返回 0 */
    private long installedAt(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        try {
            return getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private JSONObject getJson(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new JSONObject(bos.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String human(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format("%.2f GB", b / 1073741824.0);
        if (b >= 1024L * 1024) return String.format("%.1f MB", b / 1048576.0);
        if (b >= 1024) return String.format("%.0f KB", b / 1024.0);
        return b + " B";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class UpAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(UpdateActivity.this).inflate(R.layout.item_menu, parent, false);
            }
            Proj p = items.get(i);
            TextView t = v.findViewById(R.id.it_title);
            TextView sub = v.findViewById(R.id.it_sub);
            t.setText(p.name);
            if (p.loading) {
                sub.setText("检查中…");
            } else if (!p.error.isEmpty()) {
                sub.setText("— " + p.error);
            } else {
                String local = p.localVc < 0 ? "未安装" : ("本机 v" + p.localVc);
                String latest = "最新 v" + p.latestVc + (p.versionName.isEmpty() ? "" : " (" + p.versionName + ")");
                String size = p.size > 0 ? " · " + human(p.size) : "";
                if (p.localVc > p.latestVc) {
                    sub.setText(local + " · 后台最高 v" + p.latestVc + size + "   ⬆ 本机版本更新");
                } else if (p.localVc == p.latestVc) {
                    sub.setText(local + " · " + latest + size + "   ✅ 已最新");
                } else {
                    sub.setText(local + " → " + latest + size + "   ⬆ 可更新");
                }
                if (p.busy) {
                    sub.setText(local + " → " + latest + size + "   下载中 " + p.pct + "%"
                            + (p.note == null || p.note.isEmpty() ? "" : "（" + p.note + "）"));
                } else if (p.note != null && !p.note.isEmpty()) {
                    sub.setText(local + " → " + latest + size + "   " + p.note);
                }
            }
            return v;
        }
    }
}
