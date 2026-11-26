package com.hsy.devupdate;

import android.app.admin.DeviceAdminReceiver;

/**
 * 设备管理员组件。
 *
 * 只用来让装机工具/现场人员把本程序设为 Device Owner（`dpm set-device-owner com.hsy.devupdate/.ApkDeviceAdmin`）。
 * 设成 DO 之后：安装业务包不再弹系统确认框（静默装），也能直接给别的应用授权限。
 * 本身不需要任何具体策略，所以 res/xml/device_admin.xml 里只留一个占位策略。
 */
public class ApkDeviceAdmin extends DeviceAdminReceiver {
}
