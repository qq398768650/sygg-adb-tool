package com.hsy.devupdate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static class Item {
        final int id;
        final String title;
        final String sub;
        boolean badge = false;   // 标题右边显示「新版本」红色气泡
        Item(int id, String title, String sub) {
            this.id = id;
            this.title = title;
            this.sub = sub;
        }
        Item badge() {
            this.badge = true;
            return this;
        }
    }

    private static final int MENU_WIFI = 1;
    private static final int MENU_UPDATE = 2;
    private static final int MENU_ABOUT = 3;
    private static final int MENU_HOME = 4;
    private static final int MENU_GRANT = 5;
    private static final int REQ_LOC = 21;
    private static final int REQ_HOME = 22;
    private static final int SELF_REQ = 91;
    private static final String ACTION_SELF_RESULT = "com.hsy.devupdate.SELF_INSTALL_RESULT";

    private final List<Item> items = new ArrayList<>();
    private ListView menu;
    private TextView status;
    private MenuAdapter adapter;

    // 自更新状态：查到的版本号 / 下载好的包 / 是否正在检查下载
    private volatile boolean updateReady = false;
    private volatile boolean checking = false;
    private volatile File readyApk = null;
    private volatile int newVc = 0;
    private volatile String newVn = "";
    private boolean autoChecked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        menu = findViewById(R.id.menu);

        adapter = new MenuAdapter();
        menu.setAdapter(adapter);
        menu.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onMenu(items.get(position));
            }
        });

        // 读当前 WiFi 名称在 Android 10+ 需要定位权限，进主页就先要一次（只弹一次，用户按确认即可）
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
        }
        refreshMenu();

        // 启动就查一次自己的新版本，有就直接在后台下，下载完「关于」会变红提示
        autoCheckUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从 WiFi 页回来或网络变化后，副标题要跟着变
        refreshMenu();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(selfInstallReceiver, new IntentFilter(ACTION_SELF_RESULT));
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(selfInstallReceiver); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        refreshMenu();
    }

    private void refreshMenu() {
        items.clear();
        items.add(new Item(MENU_WIFI, "连接 WiFi", wifiSub()));
        items.add(new Item(MENU_UPDATE, "检查更新", "从发布后台获取最新安装包"));
        items.add(new Item(MENU_GRANT, "一键授权", grantSub()));
        items.add(new Item(MENU_HOME, "默认桌面", homeSub()));
        if (updateReady) {
            items.add(new Item(MENU_ABOUT, "发现新版本 v" + newVn, "下载完成，点这里安装").badge());
        } else if (checking) {
            items.add(new Item(MENU_ABOUT, "关于", "正在检查 / 下载新版本…"));
        } else {
            items.add(new Item(MENU_ABOUT, "关于", "v" + myVersionName() + " · 设备：" + android.os.Build.MODEL));
        }
        adapter.notifyDataSetChanged();
        if (menu.getChildCount() > 0) menu.requestFocus();
    }

    /** 已经连上 WiFi 就把名字显示出来，没连才提示去扫热点。 */
    private String wifiSub() {
        boolean onWifi = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                onWifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        } catch (Exception ignored) {
        }

        String ssid = null;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                String s = info.getSSID();
                if (s != null) {
                    if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
                        s = s.substring(1, s.length() - 1);
                    }
                    s = s.trim();
                    // 没定位权限时系统返回 <unknown ssid> / 0x
                    if (!s.isEmpty() && !s.contains("unknown") && !"0x".equals(s)) ssid = s;
                }
            }
        } catch (Exception ignored) {
        }

        if (ssid != null && (onWifi || ssid.length() > 0)) return "已连接 " + ssid;
        if (onWifi) return "已连接 WiFi（看不到名称，检查定位权限）";
        return "扫描附近热点并输入密码";
    }

    /** 现在是默认桌面吗（RoleManager.ROLE_HOME，Android 10+）。 */
    private boolean isHome() {
        try {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
        } catch (Exception e) {
            return false;
        }
    }

    private String homeSub() {        if (isHome()) return "当前就是默认桌面，开机直接进入本程序";
        return "业务程序打不开时，把本程序设为默认桌面";
    }

    /** 弹系统确认框请求 HOME 角色（遥控器方向键 + 确认就能点）。 */
    private void requestHome() {
        if (isHome()) {
            toast("已经是默认桌面了");
            return;
        }
        try {
            RoleManager rm = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                toast("本机不支持切换默认桌面");
                return;
            }
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
        } catch (Exception e) {
            toast("打开设置失败：" + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME) {
            refreshMenu();
            toast(isHome() ? "已设为默认桌面，开机直接进本程序" : "没有切换（可以再点一次）");
        }
    }

    /** 一键授权：给所有 com.hsy.* 的业务包补上权限 */
    private String grantSub() {
        return "给所有已安装的业务应用授予相机、麦克风、位置等权限";
    }

    private void oneKeyGrant() {
        new AlertDialog.Builder(this)
                .setTitle("一键授权")
                .setMessage("给所有已安装的业务应用授予相机、麦克风、位置、存储等全部权限？")
                .setPositiveButton("开始授权", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doGrantAll(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doGrantAll() {
        toast("正在授权，请稍等…");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean owner = Grants.isDeviceOwner(MainActivity.this);
                if (!owner && !AdbLocal.available()) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("一键授权")
                                    .setMessage("本机既不是 Device Owner，调试端口（127.0.0.1:5555）也没开。\n\n"
                                            + "两条路选一条：\n"
                                            + "① 用电脑的「沈阳故宫批量安装工具」点应用行的绿色锁按钮，把本程序设为 Device Owner（一次到位，以后不用电脑）；\n"
                                            + "② 用安装工具连一次本设备（会自动开启调试端口）。")
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    });
                    return;
                }
                final List<String> pkgs = Grants.scenicPackages(MainActivity.this);
                final Grants.Result r = Grants.grantAll(MainActivity.this, pkgs);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        String msg;
                        if (r.total == 0) {
                            msg = "没有找到已安装的业务应用。";
                        } else {
                            msg = "已授权 " + r.granted + " / " + r.total + " 个应用。";
                            if (!r.failed.isEmpty()) msg += "\n失败：" + TextUtils.join("、", r.failed);
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("授权完成")
                                .setMessage(msg)
                                .setPositiveButton("好", null)
                                .show();
                        refreshMenu();
                    }
                });
            }
        }).start();
    }

    private void onMenu(Item it) {
        switch (it.id) {
            case MENU_WIFI:
                startActivity(new Intent(this, WifiActivity.class));
                break;
            case MENU_UPDATE:
                startActivity(new Intent(this, UpdateActivity.class));
                break;
            case MENU_HOME:
                requestHome();
                break;
            case MENU_GRANT:
                oneKeyGrant();
                break;
            case MENU_ABOUT:
                if (updateReady && readyApk != null && readyApk.exists()) {
                    installSelf(readyApk);
                } else {
                    autoCheckUpdate(true);
                }
                break;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        if (status != null) status.setText(s);
    }

    // ================= 关于 / 自更新 =================

    /** 当前安装的版本号，取不到返回 0 */
    private int myVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String myVersionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private static String human(long n) {
        if (n <= 0) return "";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0);
    }

    /**
     * 检查自己有没有新版本。automatic=true 是启动时静默查（失败不打扰），
     * 手动点「关于」用 force=true 会提示结果。
     * 查到新版就直接在后台下，不弹框；下完「关于」变成「发现新版本」+ 红气泡，点一下安装。
     */
    private void autoCheckUpdate(final boolean force) {
        if (checking) {
            if (force) toast("正在检查 / 下载新版本，请稍等…");
            return;
        }
        if (!force && (autoChecked || updateReady)) return;
        autoChecked = true;
        checking = true;
        if (force) toast("正在检查最新版本…");
        refreshMenu();
        new Thread(new Runnable() {
            @Override public void run() {
                final SelfUpdate.Info info = SelfUpdate.fetch(7000);
                android.util.Log.i("SelfUpdate", "检查结果 " + (info == null ? "null" : ("v" + info.versionCode))
                        + " / 本机 " + myVersionCode());
                if (info == null) {
                    checking = false;
                    if (force) toastMain("检查失败：连不上更新服务器，稍后再试");
                    refreshUi();
                    return;
                }
                if (info.versionCode <= myVersionCode()) {
                    checking = false;
                    if (force) toastMain(getString(R.string.app_name) + " v" + myVersionName() + " 已是最新版本");
                    refreshUi();
                    return;
                }
                newVc = info.versionCode;
                newVn = info.versionName;
                toastMain("发现新版本 v" + info.versionName + "，正在后台下载…");
                refreshUi();
                downloadNow(info);
            }
        }).start();
    }

    /** 后台把包下下来，成功后置 updateReady，菜单项变成可点安装 */
    private void downloadNow(final SelfUpdate.Info info) {
        File dir = new File(getExternalFilesDir(null), "self_update");
        if (!dir.exists() && !dir.mkdirs()) {
            checking = false;
            toastMain("下载失败：建不了临时目录");
            refreshUi();
            return;
        }
        final File out = new File(dir, SelfUpdate.APK_NAME);
        String lastErr = "连不上更新服务器";
        // 镜像各自缓存不同、速度也差很多，逐个试；断线后还能带着已下的部分续传
        for (String base : SelfUpdate.BASES) {
            for (int attempt = 0; attempt < 3; attempt++) {
                String err = download(base + SelfUpdate.APK_NAME, out, info);
                if (err == null) {
                    readyApk = out;
                    updateReady = true;
                    checking = false;
                    toastMain("新版本 v" + newVn + " 已下载好，点「关于」即可安装");
                    refreshUi();
                    return;
                }
                lastErr = err;
                if (err.startsWith("大小不对") || err.startsWith("校验")) {
                    out.delete();   // 不是这个版本，换镜像重来
                    break;
                }
                try { Thread.sleep(800); } catch (Exception ignored) { }
            }
        }
        checking = false;
        toastMain("下载失败：" + lastErr + "（已下载的部分会保留，下次接着下）");
        refreshUi();
    }

    private void refreshUi() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                refreshMenu();
            }
        });
    }

    private void toastMain(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                toast(s);
            }
        });
    }

    /** 下载到文件，成功返回 null，失败返回原因。支持断点续传（已下的部分不丢） */
    private String download(String urlStr, File out, SelfUpdate.Info info) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        long have = out.exists() ? out.length() : 0;
        if (info.size > 0 && have == info.size) {
            return null;   // 之前已经下完了
        }
        if (info.size > 0 && have > info.size) {
            out.delete();
            have = 0;
        }
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            // 现场网络慢，卡一下别立刻断；实在断了下次续传
            c.setReadTimeout(120000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            boolean append = false;
            if (code == 206) {
                append = true;
            } else if (code == 200) {
                have = 0;      // 服务器不支持续传，从头下
            } else if (code == 416 && info.size > 0) {
                out.delete();
                return "大小不对（服务器拒绝续传），重新下载";
            } else {
                return "服务器返回 " + code;
            }
            in = c.getInputStream();
            fos = new FileOutputStream(out, append);
            byte[] buf = new byte[65536];
            int n;
            long total = have;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            if (info.size > 0 && total != info.size) {
                return "大小不对（" + total + " / " + info.size + "），下载被截断";
            }
            if (info.sha256 != null && !info.sha256.isEmpty()) {
                String sha = sha256(out);
                if (!info.sha256.equalsIgnoreCase(sha)) {
                    out.delete();
                    return "校验不通过（不是这个版本的包）";
                }
            }
            return null;
        } catch (Exception e) {
            // 网络中断：保留已下载部分，下次 Range 续传
            return e.getClass().getSimpleName() + "：" + e.getMessage();
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private static String sha256(File f) {
        InputStream is = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            is = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) { }
        }
    }

    /** 用 PackageInstaller 装自己：不是 Device Owner，系统会弹确认框，遥控器按「安装」 */
    private void installSelf(File apk) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int sid = pi.createSession(params);
            session = pi.openSession(sid);
            FileInputStream fis = new FileInputStream(apk);
            OutputStream os = session.openWrite("self_update", 0, apk.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            fis.close();
            os.flush();
            os.close();
            session.commit(PendingIntent.getBroadcast(this, SELF_REQ,
                    new Intent(ACTION_SELF_RESULT).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            toast("已提交安装，请用遥控器在弹框里按「安装」");
        } catch (Exception e) {
            toast("安装失败：" + e.getClass().getSimpleName() + "：" + e.getMessage());
        } finally {
            try { if (session != null) session.close(); } catch (Exception ignored) { }
        }
    }

    private final BroadcastReceiver selfInstallReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent conf = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (conf != null) {
                    conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(conf);
                    } catch (Exception e) {
                        toast("打不开安装确认框：" + e);
                    }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                toast("安装完成");
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast("安装未完成（status=" + st + "）" + (msg == null ? "" : "：" + msg));
            }
        }
    };

    private class MenuAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }
        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_menu, parent, false);
            }
            Item it = items.get(i);
            ((TextView) v.findViewById(R.id.it_title)).setText(it.title);
            ((TextView) v.findViewById(R.id.it_sub)).setText(it.sub);
            v.findViewById(R.id.it_badge).setVisibility(it.badge ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
