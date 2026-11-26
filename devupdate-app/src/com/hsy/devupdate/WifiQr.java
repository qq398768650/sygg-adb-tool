package com.hsy.devupdate;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 WiFi 二维码内容（Android / iOS 通用格式）：
 *
 *   WIFI:T:WPA;S:MySSID;P:password;H:true;;
 *
 * 也接受 T:nopass（无密码）、T:WEP。字段里的 ; : , \ 用反斜杠转义。
 */
public class WifiQr {

    public static class Info {
        public final String ssid;
        public final String pass;
        public final String type;
        public final boolean hidden;

        Info(String ssid, String pass, String type, boolean hidden) {
            this.ssid = ssid;
            this.pass = pass;
            this.type = type;
            this.hidden = hidden;
        }

        /** nopass 或密码为空都按开放网络处理。 */
        public boolean isOpen() {
            return "nopass".equalsIgnoreCase(type) || pass == null || pass.isEmpty();
        }

        /** WEP 不支持 WifiNetworkSuggestion，只能提示用户手动连。 */
        public boolean isWep() {
            return "WEP".equalsIgnoreCase(type);
        }
    }

    /** 不是 WiFi 二维码 / 缺 SSID 时返回 null。 */
    public static Info parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!s.regionMatches(true, 0, "WIFI:", 0, 5)) return null;
        String body = s.substring(5);

        // 末尾常有一个多余的分号，"WIFI:S:x;T:WPA;P:y;;" -> 去掉最后那个
        if (body.endsWith(";")) body = body.substring(0, body.length() - 1);

        String ssid = null;
        String pass = "";
        String type = "";
        boolean hidden = false;

        for (String part : splitEscaped(body)) {
            if (part.length() < 2) continue;
            char k = Character.toUpperCase(part.charAt(0));
            if (part.charAt(1) != ':') continue;
            String v = part.substring(2);
            switch (k) {
                case 'S': ssid = v; break;
                case 'P': pass = v; break;
                case 'T': type = v; break;
                case 'H': hidden = "true".equalsIgnoreCase(v); break;
                default: break;
            }
        }
        if (ssid == null || ssid.isEmpty()) return null;
        if (type.isEmpty()) type = pass.isEmpty() ? "nopass" : "WPA";
        return new Info(ssid, pass, type, hidden);
    }

    /** 按未转义的 ';' 切分，同时把 \; \: \, \\ 还原成原字符。 */
    private static List<String> splitEscaped(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                cur.append(s.charAt(++i));
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
