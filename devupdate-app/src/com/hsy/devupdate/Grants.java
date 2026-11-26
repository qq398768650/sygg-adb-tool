package com.hsy.devupdate;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键授权：把业务包声明过的「危险权限」全部 pm grant，再把几个 appop 放开。
 *
 * 走 AdbLocal（本地 adbd 的 shell 身份执行），因为本程序不是 Device Owner，
 * 直接调 PackageManager 授不了别的包的权限。
 */
public class Grants {

    public static class Result {
        public int total;
        public int granted;
        public final List<String> failed = new ArrayList<>();
        public String error = "";
    }

    /** 本机所有业务包（com.hsy.*），不含自己 */
    public static List<String> scenicPackages(Context ctx) {
        List<String> out = new ArrayList<>();
        String s = AdbLocal.shell("pm list packages", 15000);
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
        for (String pkg : pkgs) {
            if (grant(ctx, pkg)) r.granted++;
            else r.failed.add(pkg);
        }
        return r;
    }

    /** 给单个包授权，成功返回 true */
    public static boolean grant(Context ctx, String pkg) {
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
        // 这几个不是运行时权限，得用 appops
        cmd.append("appops set ").append(pkg).append(" MANAGE_EXTERNAL_STORAGE allow; ");
        cmd.append("appops set ").append(pkg).append(" REQUEST_INSTALL_PACKAGES allow; ");
        cmd.append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow; ");
        cmd.append("appops set ").append(pkg).append(" POST_NOTIFICATION allow");
        return AdbLocal.shell(cmd.toString(), 20000) != null;
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
