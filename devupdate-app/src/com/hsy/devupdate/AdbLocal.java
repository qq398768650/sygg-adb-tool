package com.hsy.devupdate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 ADB 客户端：连本机 adbd（127.0.0.1:5555）执行 shell 命令。
 *
 * 前提（现场播放器出厂即满足）：
 *  - 无线调试端口已开：persist.adb.tcp.port=5555（电脑端 install_one/wifi_debug_enable 会设，重启保留）
 *  - adbd 未启用调试认证：ro.adb.secure 未设置；否则会收到 AUTH 包，这里直接放弃并返回 null
 *
 * 用它是为了绕开「设备更新助手不是 Device Owner、无法直接调 PackageManager 授权别的包」的限制：
 * adbd 的 shell 用户本身持有 GRANT_RUNTIME_PERMISSIONS，用它的身份执行 pm grant 就行。
 */
public class AdbLocal {

    private static final int PKG_CNXN = 0x4e584e43; // "CNXN"
    private static final int PKG_AUTH = 0x48545541; // "AUTH"
    private static final int PKG_OPEN = 0x4e45504f; // "OPEN"
    private static final int PKG_OKAY = 0x59414b4f; // "OKAY"
    private static final int PKG_CLSE = 0x45534c43; // "CLSE"
    private static final int PKG_WRTE = 0x45545257; // "WRTE"

    /** 无线调试端口，和 install_one() 里 adb tcpip 用的保持一致 */
    public static final int PORT = 5555;

    private static final int MAX_DATA = 256 * 1024;

    /** 本机 5555 是否有 adbd 在听 */
    public static boolean available() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** 执行一条 shell 命令，返回 stdout + stderr；连不上或需要认证返回 null。 */
    public static String shell(String cmd, int timeoutMs) {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            sock.setSoTimeout(timeoutMs);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            send(out, PKG_CNXN, 0x01000001, MAX_DATA, "host::\0".getBytes("UTF-8"));

            final int localId = 1;
            int remoteId = 0;
            boolean opened = false;
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;

            while (System.currentTimeMillis() < deadline) {
                byte[] h = readN(in, 24);
                if (h == null) break;
                int command = le32(h, 0);
                int arg0 = le32(h, 4);
                int len = le32(h, 12);
                if (len < 0 || len > MAX_DATA) break;
                byte[] data = len > 0 ? readN(in, len) : new byte[0];
                if (data == null) break;

                if (command == PKG_CNXN) {
                    if (!opened) {
                        send(out, PKG_OPEN, localId, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
                        opened = true;
                    }
                } else if (command == PKG_AUTH) {
                    // 设备开了调试认证，没有已授权密钥就没法继续
                    return null;
                } else if (command == PKG_OKAY) {
                    remoteId = arg0;
                } else if (command == PKG_WRTE) {
                    sb.append(new String(data, "UTF-8"));
                    send(out, PKG_OKAY, localId, remoteId, new byte[0]);
                } else if (command == PKG_CLSE) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        byte[] h = new byte[24];
        put32(h, 0, cmd);
        put32(h, 4, arg0);
        put32(h, 8, arg1);
        put32(h, 12, data.length);
        put32(h, 16, checksum(data));
        put32(h, 20, cmd ^ 0xffffffff);
        out.write(h);
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private static int checksum(byte[] d) {
        int s = 0;
        for (byte b : d) s += (b & 0xFF);
        return s;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r <= 0) return null;
            got += r;
        }
        return buf;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }
}
