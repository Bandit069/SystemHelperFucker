package com.dsh.sysguard;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 设备管理员接收器：一旦本应用被设为 Device Owner，就能直接隐藏/挂起目标应用。 */
public class GuardAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i("sysguard", "device admin enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i("sysguard", "device admin disabled");
    }
}