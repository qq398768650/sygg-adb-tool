package com.hsy.devupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启。
 *
 * <p>① 记着上次呼出本程序的景区应用（还装着、且有启动入口）→ 直接拉起它，
 * 现场观感就是「开机直接进景区 App」，游客不用看到本程序；</p>
 * <p>② 没有记录 / 那个包被卸载或没有入口 → 拉起本程序主页，
 * 现场还能连 WiFi、拉新包（本程序设为默认桌面时系统也会起它，这里不冲突）。</p>
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // 先试景区应用：这才是现场要的开机画面
        try {
            String scenic = ScenicPrefs.remembered(ctx);
            if (ScenicPrefs.launchable(ctx, scenic)) {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(scenic);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    android.util.Log.i("DevUpdate", "开机自启：拉起景区应用 " + scenic);
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // 兜底：留在本程序主页，保证现场有操作入口
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
