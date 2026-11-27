package com.hsy.devupdate;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测一下当前网络的延迟。
 *
 * 先 ping 网关（DHCP 给的路由器地址，代表局域网这段好不好），
 * 网关也 ping 不通再退到公网 DNS（114.114.114.114，代表能不能真出去）。
 * 只用来给用户一个「网络通不通、卡不卡」的直观数字，不追求精确。
 */
public class NetPing {

    private static final Pattern TIME = Pattern.compile("time[=<]\\s*(\\d+(?:\\.\\d+)?)\\s*ms");
    private static final String FALLBACK_HOST = "114.114.114.114";

    /** 返回「23 ms」/「超时」，没连 WiFi 时返回空串（界面就不显示这一段）。 */
    public static String label(Context ctx) {
        String gw = gateway(ctx);
        double ms = gw.isEmpty() ? -1 : ping(gw);
        if (ms < 0) ms = ping(FALLBACK_HOST);
        if (ms < 0) return "超时";
        if (ms < 1) return "<1 ms";
        return Math.round(ms) + " ms";
    }

    /** 当前路由器的 IP，取不到返回空串 */
    public static String gateway(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm == null ? null : wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return "";
            int g = d.gateway;
            // DhcpInfo 里的地址是小端存的
            return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
        } catch (Exception e) {
            return "";
        }
    }

    /** 三次 ping 的平均延迟，全丢包返回 -1 */
    private static double ping(String host) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "3", "-W", "2", "-i", "0.2", host});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            double sum = 0;
            int n = 0;
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = TIME.matcher(line);
                if (m.find()) {
                    try {
                        sum += Double.parseDouble(m.group(1));
                        n++;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            p.waitFor(6, TimeUnit.SECONDS);
            if (n == 0) return -1;
            return sum / n;
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
