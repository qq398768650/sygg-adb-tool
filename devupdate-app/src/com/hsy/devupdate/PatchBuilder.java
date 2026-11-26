package com.hsy.devupdate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 差分合成：用本地已装 APK + CDN 上的 patch 清单，只下载变化的块。
 *
 * 算法与 Unity 工程 <c>Assets/Scripts/JS/Update/PatchAssembler.cs</c> 一致：
 *   1. FastCDC 切旧包 → XxHash64 指纹 → 偏移索引
 *   2. 遍历新包块清单，命中的从旧包拷，未命中的记成区间
 *   3. 用 HTTP Range 把未命中的区间下下来
 *   4. 校验新包 SHA256
 *
 * 失败就抛异常，调用方回退整包下载。
 */
public final class PatchBuilder {

    /** 相邻未命中块合并成一次 Range 请求的最大间隔。 */
    private static final long MERGE_GAP = 64 * 1024;

    public interface Progress {
        void onProgress(int pct, String note);
    }

    public static class Result {
        public boolean ok;
        public long newSize;
        public long downloaded;      // 实际下行字节
        public int localBlocks;
        public int remoteBlocks;
        public String error;
    }

    private PatchBuilder() {}

    /**
     * @param oldApk   本地已安装的旧 APK（可为 null，为 null 直接失败）
     * @param patchUrl 新版本的 patch 清单 json
     * @param apkUrl   新版本整包直链（用来做 Range 下载）
     * @param out      合成后的输出文件
     */
    public static Result build(File oldApk, String patchUrl, String apkUrl, File out, Progress cb) {
        Result r = new Result();
        if (oldApk == null || !oldApk.exists() || oldApk.length() <= 0) {
            r.error = "读不到已安装的旧包";
            return r;
        }
        if (out.exists() && !out.delete()) {
            r.error = "无法清理旧的临时文件";
            return r;
        }
        try {
            if (cb != null) cb.onProgress(1, "正在读取差分清单…");
            JSONObject patch = new JSONObject(httpGetString(patchUrl));
            if (!"fastcdc-v1".equals(patch.optString("algorithm"))) {
                throw new RuntimeException("不认识的差分算法：" + patch.optString("algorithm"));
            }
            long newSize = patch.optLong("newSize", 0);
            String newSha = patch.optString("newSha256", "");
            JSONArray blocks = patch.optJSONArray("blocks");
            if (newSize <= 0 || blocks == null || blocks.length() == 0) {
                throw new RuntimeException("差分清单不完整");
            }
            r.newSize = newSize;

            if (cb != null) cb.onProgress(2, "正在扫描本地已装包…");
            HashMap<Long, Long> index = buildIndex(oldApk);

            List<long[]> pending = new ArrayList<>();
            long localBytes = 0;
            int lastPct = 2;

            try (RandomAccessFile oldFs = new RandomAccessFile(oldApk, "r");
                 RandomAccessFile newFs = new RandomAccessFile(out, "rw")) {

                newFs.setLength(newSize);
                byte[] copyBuf = new byte[FastCdc.MAX_SIZE];
                long walked = 0;

                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject b = blocks.optJSONObject(i);
                    if (b == null) continue;
                    long o = b.optLong("o", -1);
                    int l = b.optInt("l", 0);
                    String hex = b.optString("h", "");
                    if (o < 0 || l <= 0 || hex.length() != 16) continue;

                    long h = Long.parseUnsignedLong(hex, 16);
                    Long oldOffset = index.get(h);

                    boolean copied = false;
                    if (oldOffset != null) {
                        copied = copyBlock(oldFs, newFs, oldOffset, o, l, copyBuf);
                    }
                    if (copied) {
                        r.localBlocks++;
                        localBytes += l;
                    } else {
                        r.remoteBlocks++;
                        addPending(pending, o, l);
                    }
                    walked += l;

                    int pct = 2 + (int) (walked * 8 / newSize);   // 本地合成占 2%~10%
                    if (cb != null && pct > lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct, "正在合成（" + (r.localBlocks + r.remoteBlocks) + " 个块）…");
                    }
                }
            }

            if (r.localBlocks == 0) {
                throw new RuntimeException("本地旧包和新版几乎没有相同的块，改走整包下载");
            }

            List<long[]> merged = mergeRanges(pending);
            long remoteBytes = 0;
            for (long[] rg : merged) remoteBytes += rg[1];

            int doneRanges = 0;
            int lastDownPct = 10;
            try (RandomAccessFile newFs = new RandomAccessFile(out, "rw")) {
                for (long[] rg : merged) {
                    downloadRange(apkUrl, rg[0], rg[1], newFs, new Progress() {
                        @Override public void onProgress(int ignored, String note) {}
                    });
                    r.downloaded += rg[1];
                    doneRanges++;
                    int pct = 10 + (int) (r.downloaded * 85 / Math.max(1, remoteBytes));
                    if (cb != null && pct > lastDownPct) {
                        lastDownPct = pct;
                        cb.onProgress(pct, "下载变化部分 " + doneRanges + "/" + merged.size() + " …");
                    }
                }
            }

            if (cb != null) cb.onProgress(96, "正在校验完整性…");
            String actual = sha256(out);
            if (!actual.equalsIgnoreCase(newSha)) {
                throw new RuntimeException("SHA256 不匹配（期望 " + newSha + "，实际 " + actual + "）");
            }

            if (cb != null) cb.onProgress(99, "差分合成完成");
            android.util.Log.i("PatchBuilder", "差分完成 本地块=" + r.localBlocks
                    + " 远程块=" + r.remoteBlocks + " 下载=" + r.downloaded + "/" + r.newSize);
            r.ok = true;
            return r;
        } catch (Exception e) {
            android.util.Log.w("PatchBuilder", "差分失败: " + e, e);
            r.ok = false;
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            if (out.exists()) out.delete();
            return r;
        }
    }

    // ── 内部实现 ────────────────────────────────────────────────

    /** 流式扫旧包，建「指纹 → 绝对偏移」索引。同一指纹保留第一个。 */
    private static HashMap<Long, Long> buildIndex(File apk) throws Exception {
        HashMap<Long, Long> index = new HashMap<>(20000);
        final FastCdc.BlockSink sink = new FastCdc.BlockSink() {
            @Override public void onBlock(long pos, byte[] d, int off, int len) {
                long h = XxHash64.compute(d, off, len);
                if (!index.containsKey(h)) index.put(h, pos);
            }
        };
        try (FileInputStream in = new FileInputStream(apk)) {
            byte[] buf = new byte[1 << 20];
            FastCdc.Chunker ch = new FastCdc.Chunker();
            int n;
            while ((n = in.read(buf)) > 0) {
                ch.feed(buf, 0, n, sink);
            }
            ch.flush(sink);
        }
        return index;
    }

    /** 从旧包 oldOffset 拷 len 字节到新包 o 处；读不满返回 false（按未命中处理）。 */
    private static boolean copyBlock(RandomAccessFile oldFs, RandomAccessFile newFs,
                                     long oldOffset, long o, int len, byte[] buf) throws IOException {
        if (oldOffset + len > oldFs.length()) return false;
        oldFs.seek(oldOffset);
        newFs.seek(o);
        int remaining = len;
        while (remaining > 0) {
            int want = Math.min(remaining, buf.length);
            int got = oldFs.read(buf, 0, want);
            if (got <= 0) return false;
            newFs.write(buf, 0, got);
            remaining -= got;
        }
        return true;
    }

    private static void addPending(List<long[]> pending, long o, int l) {
        if (!pending.isEmpty()) {
            long[] last = pending.get(pending.size() - 1);
            long lastEnd = last[0] + last[1];
            if (o >= last[0] && o <= lastEnd + MERGE_GAP) {
                last[1] = Math.max(lastEnd, o + l) - last[0];
                return;
            }
        }
        pending.add(new long[]{o, l});
    }

    /** [start, length] → 排序合并相邻/重叠区间 */
    private static List<long[]> mergeRanges(List<long[]> ranges) {
        List<long[]> out = new ArrayList<>();
        if (ranges.isEmpty()) return out;
        java.util.Collections.sort(ranges, new java.util.Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        long curStart = ranges.get(0)[0];
        long curEnd = ranges.get(0)[0] + ranges.get(0)[1];
        for (int i = 1; i < ranges.size(); i++) {
            long[] r = ranges.get(i);
            long rEnd = r[0] + r[1];
            if (r[0] <= curEnd + MERGE_GAP) {
                curEnd = Math.max(curEnd, rEnd);
            } else {
                out.add(new long[]{curStart, curEnd - curStart});
                curStart = r[0];
                curEnd = rEnd;
            }
        }
        out.add(new long[]{curStart, curEnd - curStart});
        return out;
    }

    /** 把 [start, start+len) 写进 raf 的 start 处。 */
    private static void downloadRange(String urlStr, long start, long len,
                                      RandomAccessFile raf, Progress cb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
        int code = c.getResponseCode();
        if (code != 206) {
            throw new RuntimeException("服务器不支持断点下载（HTTP " + code + "）");
        }
        raf.seek(start);
        try (InputStream is = c.getInputStream()) {
            byte[] buf = new byte[131072];
            long got = 0;
            int n;
            while (got < len && (n = is.read(buf, 0, (int) Math.min(buf.length, len - got))) > 0) {
                raf.write(buf, 0, n);
                got += n;
            }
            if (got != len) {
                throw new RuntimeException("区间下载不完整（" + got + "/" + len + "）");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String httpGetString(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code + " · " + urlStr);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
