package com.hsy.devupdate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;

/** 用方向键 + 确认键输入 WiFi 密码，然后用 NetworkSuggestion 连接。 */
public class PassActivity extends Activity {

    private static final String NUM_ROW =
            "1234567890-_./:@#$%&*+=!?";
    private static final String LOW_ROW = "abcdefghijklmnopqrstuvwxyz";
    private static final String UP_ROW = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private String ssid;
    private boolean openNetwork;
    private final StringBuilder pwd = new StringBuilder();
    private int page = 0; // 0 数字符号 1 小写 2 大写

    private GridLayout keys;
    private TextView value;
    private TextView hint;
    private LinearLayout actions;
    private Handler handler = new Handler(Looper.getMainLooper());

    private boolean connecting = false;
    private int tries = 0;
    /** 提交给系统的建议，失败/取消时要移除，否则系统会一直重试这个密码。 */
    private WifiNetworkSuggestion submitted;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_pass);
        ssid = getIntent().getStringExtra("ssid");
        openNetwork = getIntent().getBooleanExtra("open", false);
        keys = findViewById(R.id.pass_keys);
        value = findViewById(R.id.pass_value);
        hint = findViewById(R.id.pass_hint);
        actions = findViewById(R.id.pass_actions);
        ((TextView) findViewById(R.id.pass_title)).setText(openNetwork ? "连接开放网络" : "输入 WiFi 密码");
        ((TextView) findViewById(R.id.pass_ssid)).setText(ssid);

        buildKeys();
        buildActions();
        render();
        if (openNetwork) {
            hint.setText("这个热点不需要密码，按「连接」即可");
        }
    }

    private void render() {
        value.setText(pwd.length() == 0 ? "（按确认键开始输入）" : pwd.toString());
    }

    private String currentChars() {
        if (page == 1) return LOW_ROW;
        if (page == 2) return UP_ROW;
        return NUM_ROW;
    }

    private void buildKeys() {
        keys.removeAllViews();
        String chars = currentChars();
        for (int i = 0; i < chars.length(); i++) {
            final char c = chars.charAt(i);
            Button btn = makeButton(String.valueOf(c), 22);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { insert(c); }
            });
            keys.addView(btn);
        }
    }

    private Button makeButton(String text, int sp) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(sp);
        btn.setTextColor(0xFFF3F4F6);
        btn.setBackgroundResource(R.drawable.btn_bg);
        btn.setPadding(0, 0, 0, 0);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setStateListAnimator(null);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = 0;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(5, 5, 5, 5);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void buildActions() {
        actions.removeAllViews();
        addAction(page == 0 ? "abc" : "123", new Runnable() {
            @Override public void run() {
                page = (page == 0) ? 1 : 0;
                buildKeys();
                buildActions();
                focusLast();
            }
        });
        if (page > 0) {
            addAction(page == 1 ? "ABC" : "abc", new Runnable() {
                @Override public void run() {
                    page = (page == 1) ? 2 : 1;
                    buildKeys();
                    buildActions();
                    focusLast();
                }
            });
        }
        addAction("← 删除", new Runnable() {
            @Override public void run() {
                if (pwd.length() > 0) pwd.deleteCharAt(pwd.length() - 1);
                render();
            }
        });
        addAction("清空", new Runnable() {
            @Override public void run() {
                pwd.setLength(0);
                render();
            }
        });
        if (connecting) {
            addAction("✖ 取消", new Runnable() {
                @Override public void run() { cancelConnect(); }
            });
        } else {
            addAction("✔ 连接", new Runnable() {
                @Override public void run() { connect(); }
            });
        }
        focusLast();
    }

    /** 重建按钮后把焦点放回最后一个（连接/取消），否则焦点会跳到第一个键上。 */
    private void focusLast() {
        if (actions == null || actions.getChildCount() == 0) return;
        View v = actions.getChildAt(actions.getChildCount() - 1);
        if (v != null) v.requestFocus();
    }

    private void addAction(String text, final Runnable action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(18);
        btn.setTextColor(0xFFF3F4F6);
        btn.setBackgroundResource(R.drawable.btn_bg);
        btn.setPadding(0, 0, 0, 0);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(5, 0, 5, 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        actions.addView(btn);
    }

    private void insert(char c) {
        if (connecting) return;
        if (pwd.length() >= 63) return;
        pwd.append(c);
        render();
    }

    /** 支持实体键盘 / adb input text 直接打字。 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (connecting) {
            // 连接中只放行返回键（用来取消），其余按键吞掉防止乱改密码
            if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (pwd.length() > 0) pwd.deleteCharAt(pwd.length() - 1);
            render();
            return true;
        }
        int uc = event.getUnicodeChar();
        if (uc >= 32 && uc < 127 && keyCode != KeyEvent.KEYCODE_ENTER
                && !event.isCtrlPressed() && !event.isAltPressed()) {
            insert((char) uc);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (connecting) {
            cancelConnect();
            return;
        }
        if (pwd.length() > 0) {
            pwd.deleteCharAt(pwd.length() - 1);
            render();
        } else {
            super.onBackPressed();
        }
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return "";
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getSSID() == null) return "";
            String s = info.getSSID();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
                s = s.substring(1, s.length() - 1);
            }
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private void connect() {
        if (connecting) return;
        String pass = pwd.toString();
        if (!openNetwork && pass.length() < 8) {
            hint.setText("WPA 密码至少 8 位，当前 " + pass.length() + " 位");
            return;
        }
        // 已经连在这个网络上时，输什么密码都会「看起来成功」，直接说明情况避免误判
        if (ssid != null && ssid.equals(currentSsid())) {
            hint.setText("当前已经连在 " + ssid + " 上，不需要再连（要换密码请先在系统设置里忘记网络）");
            return;
        }
        final WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            hint.setText("WiFi 服务不可用");
            return;
        }
        connecting = true;
        tries = 0;
        dimKeys(true);
        hint.setText("正在连接 " + ssid + " …");
        buildActions();

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder().setSsid(ssid);
        if (!openNetwork) {
            builder.setWpa2Passphrase(pass);
        }
        submitted = builder.build();
        int status;
        try {
            status = wifi.addNetworkSuggestions(Collections.singletonList(submitted));
        } catch (Exception e) {
            connecting = false;
            submitted = null;
            hint.setText("提交失败：" + e);
            dimKeys(false);
            buildActions();
            focusLast();
            return;
        }
        // 0=成功 3=已存在同样的建议，两者都算提交成功
        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                && status != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            connecting = false;
            submitted = null;
            hint.setText("系统拒绝了连接请求（status=" + status + "）");
            dimKeys(false);
            buildActions();
            focusLast();
            return;
        }
        // 断开当前网络，逼系统选新提交的网络
        try { wifi.disconnect(); } catch (Exception ignored) {}
        poll();
    }

    @SuppressWarnings("deprecation")
    private void poll() {
        if (!connecting) return;
        tries++;
        String cur = currentSsid();

        if (ssid != null && ssid.equals(cur)) {
            connecting = false;
            submitted = null;
            Intent data = new Intent();
            data.putExtra("msg", "已连接 " + ssid);
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        if (tries > 20) {
            failConnect("40 秒内没连上。可能密码不对、不在范围内，或热点有客户端隔离。\n改密码后再按「连接」，或按返回键重选热点。");
            return;
        }
        hint.setText("正在连接 " + ssid + " … " + (tries * 2) + "s（按返回可取消）");
        handler.postDelayed(new Runnable() {
            @Override public void run() { poll(); }
        }, 2000);
    }

    /** 连接中把键盘变灰，避免看起来「按键没反应」。 */
    private void dimKeys(boolean dim) {
        for (int i = 0; i < keys.getChildCount(); i++) {
            View v = keys.getChildAt(i);
            v.setEnabled(!dim);
            v.setAlpha(dim ? 0.35f : 1f);
        }
    }

    private void removeSuggestion() {
        if (submitted == null) return;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) wifi.removeNetworkSuggestions(Collections.singletonList(submitted));
        } catch (Exception ignored) {}
        submitted = null;
    }

    /** 用户主动取消：停轮询 + 撤掉刚提交的建议，系统才不会一直拿错密码重试。 */
    private void cancelConnect() {
        connecting = false;
        handler.removeCallbacksAndMessages(null);
        removeSuggestion();
        hint.setText("已取消。可以改密码再按「连接」。");
        dimKeys(false);
        buildActions();
        focusLast();
    }

    private void failConnect(String msg) {
        connecting = false;
        handler.removeCallbacksAndMessages(null);
        removeSuggestion();
        hint.setText(msg);
        dimKeys(false);
        buildActions();
        focusLast();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
