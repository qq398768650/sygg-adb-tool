package com.hsy.devupdate;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键授权：把业务包声明过的「危险权限」全部授权，再把几个 appop 放开。
 *
 * 两条路，优先第一条：
 * 1. **本程序是 Device Owner**（装机工具绿色锁按钮 / dpm set-device-owner 设过）→
 *    直接 `DevicePolicyManager.setPermissionGrantState`，不依赖任何端口，最稳。
 * 2. 不是 DO（老设备、或 DO 被业务包占着）→ 走 `AdbLocal` 本地 adbd（127.0.0.1:5555）的 shell 身份执行 `pm grant`。
 *
 * appops（读写存储、安装未知应用、悬浮窗、通知）没有对应 API，两条路都靠 adb 兜底；拿不到 adb 也不影响运行时权限已授。
 */
public class Grants {

    public static class Result {
        public int total;
        public int granted;
        public final List<String> failed = new ArrayList<>();
        public String error = "";
        /** 本次走的是 DC 管理接口还是 adb */
        public boolean viaOwner = false;
    }

    public static boolean isDeviceOwner(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 本机所有业务包（com.hsy.*），不含自己 */
    public static List<String> scenicPackages(Context ctx) {
        List<String> out = new ArrayList<>();
        if (isDeviceOwner(ctx)) {
            // DO 有 QUERY_ALL_PACKAGES，直接列，不用等 adb
            for (PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0)) {
                String pkg = pi.packageName;
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue;
                if (pkg.startsWith("com.hsy.")) out.add(pkg);
            }
            Log.i("Grants", "DO 分支 " + out.size() + " 个业务包");
            if (!out.isEmpty()) return out;
        }
        String s = AdbLocal.shell("pm list packages", 15000);
        Log.i("Grants", "adb 分支 pm list packages -> " + (s == null ? "null" : "ok"));
        if (s == null) return out;
        for (String line : s.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String pkg = line.substring("package:".length()).trim();
            if (pkg.isEmpty() || pkg.equals(ctx.getPackageName())) continue;
            if (!pkg.startsWith("com.hsy.")) continue;
            out.add(pkg);
        }
        return out;
    }

    public static Result grantAll(Context ctx, List<String> pkgs) {
        Result r = new Result();
        r.total = pkgs.size();
        r.viaOwner = isDeviceOwner(ctx);
        Log.i("Grants", "grantAll total=" + r.total + " viaOwner=" + r.viaOwner);
        for (String pkg : pkgs) {
            if (grant(ctx, pkg)) r.granted++;
            else r.failed.add(pkg);
        }
        return r;
    }

    /** 给单个包授权，成功返回 true */
    public static boolean grant(Context ctx, String pkg) {
        boolean owner = isDeviceOwner(ctx);
        Log.i("Grants", "grant " + pkg + " viaOwner=" + owner);
        if (owner) {
            boolean ok = grantByOwner(ctx, pkg);
            // appops 还是只能靠 adb；拿不到就算了，别把整体判失败
            String appops = appopsCmd(pkg);
            AdbLocal.shell(appops, 15000);
            return ok;
        }
        return grantByAdb(ctx, pkg);
    }

    /** DO 路线：逐条 setPermissionGrantState */
    private static boolean grantByOwner(Context ctx, String pkg) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            ComponentName admin = new ComponentName(ctx, ApkDeviceAdmin.class);
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            boolean any = false;
            boolean allOk = true;
            if (pi.requestedPermissions != null) {
                for (String perm : pi.requestedPermissions) {
                    if (!isDangerous(ctx, perm)) continue;
                    any = true;
                    boolean ok = dpm.setPermissionGrantState(admin, pkg, perm,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (!ok) allOk = false;
                }
            }
            return any ? allOk : true;
        } catch (Exception e) {
            Log.w("Grants", "setPermissionGrantState 失败", e);
            return false;
        }
    }

    /** adb 路线：拼一条 pm grant 命令 */
    private static boolean grantByAdb(Context ctx, String pkg) {
        StringBuilder cmd = new StringBuilder();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                for (String perm : pi.requestedPermissions) {
                    if (isDangerous(ctx, perm)) {
                        cmd.append("pm grant ").append(pkg).append(' ').append(perm).append("; ");
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        cmd.append(appopsCmd(pkg));
        return AdbLocal.shell(cmd.toString(), 20000) != null;
    }

    /** 这几个不是运行时权限，得用 appops */
    private static String appopsCmd(String pkg) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("appops set ").append(pkg).append(" MANAGE_EXTERNAL_STORAGE allow; ");
        cmd.append("appops set ").append(pkg).append(" REQUEST_INSTALL_PACKAGES allow; ");
        cmd.append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow; ");
        cmd.append("appops set ").append(pkg).append(" POST_NOTIFICATION allow");
        return cmd.toString();
    }

    private static boolean isDangerous(Context ctx, String perm) {
        try {
            PermissionInfo info = ctx.getPackageManager().getPermissionInfo(perm, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Exception e) {
            return false;
        }
    }
}
