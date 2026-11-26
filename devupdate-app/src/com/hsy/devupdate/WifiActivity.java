package com.hsy.devupdate;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 扫描附近热点，选一个后输入密码连接。 */
public class WifiActivity extends Activity {

    private static final int REQ_LOCATION = 11;
    private static final int REQ_PASS = 12;
    private static final int REQ_SCAN = 13;

    private WifiManager wifi;
    private final List<ScanResult> items = new ArrayList<>();
    private final Map<String, ScanResult> best = new LinkedHashMap<>();
    private ListView lv;
    private TextView status;
    private WifiAdapter adapter;

    private boolean receiverOn = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scanTimeout;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                boolean ok = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true);
                onScanDone(ok);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_wifi);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lv = findViewById(R.id.wifi_list);
        status = findViewById(R.id.wifi_status);
        adapter = new WifiAdapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                pick(items.get(position));
            }
        });
        findViewById(R.id.rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        // 二维码里带着 SSID 和密码，扫一下就自动连，不用一个字一个字按遥控器
        findViewById(R.id.scan_qr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(WifiActivity.this, ScanActivity.class), REQ_SCAN);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        startScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_LOCATION) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                startScan();
            } else {
                status.setText("没有定位权限，扫不到 WiFi。请在设置里允许「位置」权限。");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PASS && res == RESULT_OK && data != null) {
            Toast.makeText(this, data.getStringExtra("msg"), Toast.LENGTH_LONG).show();
            finish();
        }
        if (req == REQ_SCAN && res == RESULT_OK && data != null) {
            Toast.makeText(this, data.getStringExtra("msg"), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverOn) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            receiverOn = false;
        }
        if (scanTimeout != null) handler.removeCallbacks(scanTimeout);
        super.onDestroy();
    }

    private void startScan() {
        if (!receiverOn) {
            registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            receiverOn = true;
        }
        if (!wifi.isWifiEnabled()) {
            wifi.setWifiEnabled(true);
        }
        status.setText("正在扫描…");
        items.clear();
        adapter.notifyDataSetChanged();
        boolean started = wifi.startScan();
        if (!started) {
            // 系统限流（4 次/2 分钟）时直接读缓存
            handler.postDelayed(new Runnable() {
                @Override public void run() { onScanDone(true); }
            }, 1500);
        }
        if (scanTimeout != null) handler.removeCallbacks(scanTimeout);
        scanTimeout = new Runnable() {
            @Override public void run() { onScanDone(true); }
        };
        handler.postDelayed(scanTimeout, 8000);
    }

    private void onScanDone(boolean updated) {
        if (scanTimeout != null) handler.removeCallbacks(scanTimeout);
        List<ScanResult> raw;
        try {
            raw = wifi.getScanResults();
        } catch (Exception e) {
            raw = new ArrayList<>();
        }
        best.clear();
        String cur = currentSsid();
        if (raw != null) {
            for (ScanResult r : raw) {
                if (r.SSID == null || r.SSID.trim().isEmpty()) continue;
                ScanResult old = best.get(r.SSID);
                if (old == null || r.level > old.level) best.put(r.SSID, r);
            }
        }
        items.clear();
        items.addAll(best.values());
        Collections.sort(items, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult a, ScanResult b2) {
                boolean ca = a.SSID.equals(cur), cb = b2.SSID.equals(cur);
                if (ca != cb) return ca ? -1 : 1;
                return b2.level - a.level;
            }
        });
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            status.setText("没扫到热点。检查 WiFi 是否打开、定位服务是否开启。");
        } else {
            status.setText("找到 " + items.size() + " 个热点 · 当前：" + (cur.isEmpty() ? "未连接" : cur));
        }
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        try {
            android.net.wifi.WifiInfo info = wifi.getConnectionInfo();
            if (info == null) return "";
            String s = info.getSSID();
            if (s == null) return "";
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
            if ("<unknown ssid>".equals(s) || "0x".equals(s)) return "";
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private void pick(ScanResult r) {
        boolean open = !needsPassword(r);
        Intent i = new Intent(this, PassActivity.class);
        i.putExtra("ssid", r.SSID);
        i.putExtra("open", open);
        startActivityForResult(i, REQ_PASS);
    }

    static boolean needsPassword(ScanResult r) {
        String cap = r.capabilities == null ? "" : r.capabilities.toUpperCase();
        if (cap.contains("WEP")) return true;
        if (cap.contains("WPA") || cap.contains("PSK") || cap.contains("SAE")) return true;
        return false;
    }

    private class WifiAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(WifiActivity.this).inflate(R.layout.item_menu, parent, false);
            }
            ScanResult r = items.get(i);
            String tag = needsPassword(r) ? "🔒" : "🔓";
            ((TextView) v.findViewById(R.id.it_title)).setText(r.SSID);
            int lvl = WifiManager.calculateSignalLevel(r.level, 4) + 1;
            String cur = currentSsid();
            String sub = tag + "  信号 " + lvl + "/4";
            if (r.SSID.equals(cur)) sub += "   ✅ 已连接";
            ((TextView) v.findViewById(R.id.it_sub)).setText(sub);
            return v;
        }
    }
}
