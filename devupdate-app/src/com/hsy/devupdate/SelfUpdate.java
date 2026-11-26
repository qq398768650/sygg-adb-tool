package com.hsy.devupdate;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 安装包更新助手自己的在线更新。
 *
 * 新版本 APK 挂在安装工具同一个 GitHub Release 上，文件名固定：
 *   devupdate.json   版本信息（versionCode / versionName / size / sha256）
 *   DevUpdate.apk    APK 本体
 * 走 ghfast / gh-proxy 镜像，避免现场直连 github 拿不到。
 */
public class SelfUpdate {

    /**
     * 按顺序试，第一个能拉到 devupdate.json 的就用。
     * 主源是 jsDelivr 拉仓库里的文件（国内快、和 git push 同步），
     * release 资产那几条兜底（有镜像缓存延迟）。
     */
    public static final String[] BASES = {
            "https://cdn.jsdelivr.net/gh/qq398768650/sygg-adb-tool@main/devupdate-app/",
            "https://ghfast.top/https://raw.githubusercontent.com/qq398768650/sygg-adb-tool/main/devupdate-app/",
            "https://raw.githubusercontent.com/qq398768650/sygg-adb-tool/main/devupdate-app/",
            "https://ghfast.top/https://github.com/qq398768650/sygg-adb-tool/releases/latest/download/",
    };

    static final String JSON_NAME = "devupdate.json";
    static final String APK_NAME = "DevUpdate.apk";

    public static class Info {
        public int versionCode;
        public String versionName = "";
        public long size;
        public String sha256 = "";
        public String base = "";   // 命中的镜像前缀，下载 APK 用同一个
    }

    /**
     * 拉最新版本信息，全部镜像都失败返回 null。
     * 注意：镜像各自有缓存，可能新旧不一（ghfast 常见落后几分钟），所以**取 versionCode 最大的那份**，
     * 不能遇到第一个能用的就跑。
     */
    public static Info fetch(int timeoutMs) {
        Info best = null;
        for (String base : BASES) {
            try {
                String body = getText(base + JSON_NAME + "?t=" + System.currentTimeMillis(), timeoutMs);
                if (body == null) continue;
                JSONObject o = new JSONObject(body);
                int vc = o.optInt("versionCode", 0);
                if (vc <= 0) continue;
                if (best == null || vc > best.versionCode) {
                    Info info = new Info();
                    info.versionCode = vc;
                    info.versionName = o.optString("versionName", "");
                    info.size = o.optLong("size", 0);
                    info.sha256 = o.optString("sha256", "");
                    info.base = base;
                    best = info;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static String getText(String urlStr, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) {
                android.util.Log.w("SelfUpdate", "HTTP " + c.getResponseCode() + " · " + urlStr);
                return null;
            }
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            android.util.Log.w("SelfUpdate", "拉取失败 · " + urlStr + " · " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
