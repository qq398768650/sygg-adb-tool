package com.hsy.devupdate;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;

/**
 * 提交 WiFi 账号并轮询是否连上。
 *
 * 走 WifiNetworkSuggestion（Android 10+，普通 App 就能用，不需要 Device Owner）：
 * 提交后系统可能弹一次「是否允许连接到建议的网络」，按确认即可。
 */
public class WifiConnect {

    public interface Callback {
        /** 结束时回调一次；ok=false 时 msg 是给人看的中文原因。 */
        void onResult(boolean ok, String msg);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WifiNetworkSuggestion submitted;
    private boolean running;
    private int tries;
    private String ssid;
    private String pwd;
    private Callback cb;

    public WifiConnect(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return running;
    }

    /** 当前已连的 WiFi 名称，读不到返回空串。 */
    @SuppressWarnings("deprecation")
    public static String currentSsid(Context ctx) {
        try {
            WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return "";
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getSSID() == null) return "";
            String s = info.getSSID();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
                s = s.substring(1, s.length() - 1);
            }
            if ("<unknown ssid>".equals(s) || "0x".equals(s)) return "";
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /** 开始连接；已经有连接在进行时直接返回 false。 */
    public boolean connect(String ssid, String pass, boolean open, Callback cb) {
        if (running) return false;
        WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            cb.onResult(false, "WiFi 服务不可用");
            return false;
        }
        this.ssid = ssid;
        this.pwd = pass == null ? "" : pass;
        this.cb = cb;
        this.tries = 0;
        this.running = true;

        if (!wifi.isWifiEnabled()) {
            try { wifi.setWifiEnabled(true); } catch (Exception ignored) {}
        }

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder().setSsid(ssid);
        if (!open) builder.setWpa2Passphrase(this.pwd);
        submitted = builder.build();

        int status;
        try {
            status = wifi.addNetworkSuggestions(Collections.singletonList(submitted));
        } catch (Exception e) {
            finish(false, "提交失败：" + e.getMessage());
            return false;
        }
        // 0=成功 3=同样的建议已经提交过，都算成功
        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                && status != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            finish(false, "系统拒绝了连接请求（status=" + status + "）");
            return false;
        }
        // 断开当前网络，逼系统按新的建议重选
        try { wifi.disconnect(); } catch (Exception ignored) {}
        ui.postDelayed(pollTask, 2000);
        return true;
    }

    /** 停止轮询并撤回建议，系统就不会一直拿错密码重试。 */
    public void cancel() {
        if (!running) return;
        ui.removeCallbacks(pollTask);
        removeSuggestion();
        running = false;
        if (cb != null) cb.onResult(false, "已取消");
        cb = null;
    }

    /** 正在连的 SSID（界面显示进度用）。 */
    public String target() {
        return ssid;
    }

    public int elapsedSeconds() {
        return tries * 2;
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tries++;
            if (ssid != null && ssid.equals(currentSsid(ctx))) {
                finish(true, "已连接 " + ssid);
                return;
            }
            if (tries > 20) {
                finish(false, "40 秒内没连上。可能密码不对、不在范围内，或热点有客户端隔离。");
                return;
            }
            ui.postDelayed(this, 2000);
        }
    };

    private void removeSuggestion() {
        if (submitted == null) return;
        try {
            WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) wifi.removeNetworkSuggestions(Collections.singletonList(submitted));
        } catch (Exception ignored) {}
        submitted = null;
    }

    private void finish(boolean ok, String msg) {
        ui.removeCallbacks(pollTask);
        if (!ok) removeSuggestion();
        running = false;
        Callback c = cb;
        cb = null;
        if (c != null) c.onResult(ok, msg);
    }
}
