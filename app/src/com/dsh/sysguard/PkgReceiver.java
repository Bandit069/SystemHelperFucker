package com.dsh.sysguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class PkgReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
        Log.i("sysguard", "pkg event: " + intent.getAction() + " " + pkg);
        if (GuardService.PKG.equals(pkg)
                && (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction()))) {
            Intent i = new Intent(context, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        }
    }
}