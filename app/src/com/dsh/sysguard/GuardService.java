package com.dsh.sysguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class GuardService extends Service {
    public static final String PKG = "com.mobiletools.systemhelper";
    public static final String CHANNEL = "guard";
    public static final String ACTION_RUN_ONCE = "com.dsh.sysguard.RUN_ONCE";
    public static volatile String STATUS = "未启动";
    public static volatile boolean RUNNING = false;

    private Handler handler;
    private Runnable tick;
    private long lastRun;
    private String lastOutput = "";
    private String lastVerdict = "";
    private DevicePolicyManager dpm;
    private ComponentName admin;

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        admin = new ComponentName(this, GuardAdminReceiver.class);
        dpm = getSystemService(DevicePolicyManager.class);
        createChannel();
        startAsForeground("守护中，每 30 秒核查一次");
        handler = new Handler(Looper.getMainLooper());
        tick = new Runnable() {
            @Override public void run() {
                try { step(); } catch (Throwable t) { Log.w("sysguard", "step failed", t); }
                handler.postDelayed(this, 30000);
            }
        };
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RUN_ONCE.equals(intent.getAction())) {
            new Thread(new Runnable() { public void run() { step(); } }).start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        RUNNING = false;
        STATUS = "已停止";
        if (handler != null && tick != null) handler.removeCallbacks(tick);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "System Helper Fucker", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void startAsForeground(String text) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("System Helper Fucker")
                .setContentText(text)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
    }

    private void updateNotification(String text) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("System Helper Fucker")
                .setContentText(text)
                .setOngoing(true)
                .build();
        getSystemService(NotificationManager.class).notify(1, n);
    }

    private boolean installed() {
        try {
            getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Device Owner 模式下不需要 Shizuku：直接隐藏目标应用 + 拒绝其敏感权限 + 保护自己不被卸载。 */
    public static boolean isOwner(Context c) {
        try {
            DevicePolicyManager d = c.getSystemService(DevicePolicyManager.class);
            return d != null && d.isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private String ownerStep() {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return "not-owner";
        StringBuilder sb = new StringBuilder();
        try {
            dpm.setApplicationHidden(admin, PKG, true);
            sb.append("hidden=ok ");
        } catch (Throwable t) {
            sb.append("hidden=").append(t.getClass().getSimpleName()).append(' ');
        }
        try {
            dpm.setUninstallBlocked(admin, getPackageName(), true);
            sb.append("self-protected ");
        } catch (Throwable ignored) { }
        String[] perms = {
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.READ_PHONE_STATE"
        };
        for (String p : perms) {
            try {
                dpm.setPermissionGrantState(admin, PKG, p,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED);
            } catch (Throwable ignored) { }
        }
        return sb.toString().trim();
    }

    private void step() {
        boolean ok = installed();
        if (isOwner(this)) {
            String r = ownerStep();
            lastRun = System.currentTimeMillis();
            STATUS = (ok ? "目标在装" : "目标已不在") + "；设备管理员：已隐藏 + 权限拒绝";
            updateNotification("Device Owner 压制中");
            Log.i("sysguard", "owner step: " + r + " installed=" + ok);
            return;
        }
        boolean shizuku = Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        if (!shizuku) {
            STATUS = (ok ? "目标仍在安装" : "目标已不在") + "；Shizuku 未就绪，无法执行压制";
            updateNotification("Shizuku 未就绪");
            return;
        }
        String script = buildScript();
        try {
            ShizukuRemoteProcess p = newProcess(new String[]{"sh", "-c", script}, null, null);
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() < 4000) sb.append(line).append('\n');
            }
            p.waitFor();
            lastOutput = sb.toString().trim();
            lastRun = System.currentTimeMillis();
            lastVerdict = judge(lastOutput);
            STATUS = (ok ? "目标在装" : "目标不在") + "；" + lastVerdict;
            updateNotification(lastVerdict);
            Log.i("sysguard", "run: " + lastOutput.replace('\n', ' '));
        } catch (Throwable t) {
            STATUS = "执行失败: " + t;
            Log.w("sysguard", "exec failed", t);
        }
    }

    /** Shizuku v13 把 newProcess 改成了 private，这里用反射拿回来。 */
    private static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (ShizukuRemoteProcess) m.invoke(null, cmd, env, dir);
    }

    private String judge(String out) {
        if (out.contains("GONE")) return "已卸载（User 0 无安装登记），守护待命";
        if (out.contains("only root can delete system app") || out.contains("Cannot disable system packages"))
            return "系统应用不可卸载，已压制：强制停止 + 权限/后台限制";
        return "已执行压制（强制停止 + 权限/后台限制）";
    }

    private String buildScript() {
        String p = PKG;
        List<String> cmds = new ArrayList<>();
        cmds.add("echo '[uninstall]'; pm uninstall -k --user 0 " + p + " 2>&1");
        cmds.add("if pm list packages --user 0 " + p + " | grep -q " + p
                + "; then APK=$(pm path com.dsh.sysguard | head -n1 | cut -d: -f2); "
                + "CLASSPATH=$APK app_process /system/bin com.dsh.sysguard.PackageUninstaller " + p + " 0 2>&1; fi");
        cmds.add("pm disable-user --user 0 " + p + " 2>&1");
        cmds.add("am force-stop " + p + " 2>&1");
        cmds.add("cmd appops set " + p + " COARSE_LOCATION ignore");
        cmds.add("cmd appops set " + p + " FINE_LOCATION ignore");
        cmds.add("cmd appops set " + p + " MONITOR_LOCATION ignore");
        cmds.add("cmd appops set " + p + " WIFI_SCAN ignore");
        cmds.add("cmd appops set " + p + " GET_USAGE_STATS ignore");
        cmds.add("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND ignore");
        cmds.add("cmd appops set " + p + " START_FOREGROUND ignore");
        cmds.add("cmd appops set " + p + " WAKE_LOCK ignore");
        cmds.add("pm revoke " + p + " android.permission.ACCESS_FINE_LOCATION 2>&1");
        cmds.add("pm revoke " + p + " android.permission.ACCESS_COARSE_LOCATION 2>&1");
        cmds.add("pm revoke " + p + " android.permission.ACCESS_BACKGROUND_LOCATION 2>&1");
        cmds.add("if pm list packages --user 0 " + p + " | grep -q " + p
                + "; then echo STILL_INSTALLED; else echo GONE; fi");
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) sb.append(c).append("; ");
        return sb.toString();
    }

    public static String snapshot() {
        return STATUS;
    }
}