package com.hsy.devupdate;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「上次呼出本程序的景区应用」记录。
 *
 * <p>两个地方共用，所以单独抽出来，避免两处各写一份 key：
 * <ul>
 *   <li>{@link MainActivity}：从景区呼出时记住来源，退出时按它返回；进程被杀后重开也还能返回；</li>
 *   <li>{@link BootReceiver}：开机时如果有记录，直接拉起景区应用（等价于「开机直接进景区 App」）。</li>
 * </ul></p>
 */
public final class ScenicPrefs {

    private static final String PREFS = "devupdate";
    private static final String KEY_LAST_SCENIC = "last_scenic_pkg";

    private ScenicPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 上次呼出本程序的景区包名，没有返回空串。 */
    public static String remembered(Context ctx) {
        String pkg = prefs(ctx).getString(KEY_LAST_SCENIC, "");
        return pkg == null ? "" : pkg.trim();
    }

    /** 记住景区包名（从景区呼出时调用）。 */
    public static void remember(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        prefs(ctx).edit().putString(KEY_LAST_SCENIC, pkg.trim()).apply();
    }

    /** 清掉记录（目标包被卸载 / 打不开时调用）。 */
    public static void forget(Context ctx) {
        prefs(ctx).edit().remove(KEY_LAST_SCENIC).apply();
    }

    /** 包是否还装着。 */
    public static boolean installed(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 包是否还有可启动的入口（没装 / 没 LAUNCHER 都返回 false）。 */
    public static boolean launchable(Context ctx, String pkg) {
        if (!installed(ctx, pkg)) return false;
        try {
            return ctx.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
