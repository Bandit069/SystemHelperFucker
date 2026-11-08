package com.dsh.sysguard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 2001;
    private static final int REQ_NOTIF = 2002;
    private TextView state;
    private TextView log;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    refresh();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = new TextView(this);
        title.setText("System Helper Fucker");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        state = new TextView(this);
        state.setTextSize(14);
        state.setTextColor(Color.parseColor("#B0B0B0"));
        state.setPadding(0, 32, 0, 24);
        root.addView(state);

        root.addView(btn("申请 Shizuku 权限", v -> {
            if (!Shizuku.pingBinder()) {
                state.setText("Shizuku 服务未运行：请先打开 Shizuku 应用并通过无线调试启动");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                state.setText("Shizuku 权限已授予");
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        }));

        root.addView(btn("启动守护", v -> {
            getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
            Intent i = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            refresh();
        }));

        root.addView(btn("立即执行一次", v -> {
            Intent i = new Intent(this, GuardService.class);
            i.setAction(GuardService.ACTION_RUN_ONCE);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            refresh();
        }));

        root.addView(btn("停止守护", v -> {
            getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, GuardService.class));
            refresh();
        }));

        root.addView(btn("移除设备管理员", v -> {
            try {
                android.app.admin.DevicePolicyManager d =
                        getSystemService(android.app.admin.DevicePolicyManager.class);
                d.clearDeviceOwnerApp(getPackageName());
                state.setText("已清除 Device Owner 身份（部分机型需在电脑执行 dpm remove-active-admin）");
            } catch (Throwable t) {
                state.setText("清除失败: " + t + "\n电脑执行: adb shell dpm remove-active-admin com.dsh.sysguard/.GuardAdminReceiver");
            }
            refresh();
        }));

        log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(Color.parseColor("#80CBC4"));
        log.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = 32;
        root.addView(log, lp);

        setContentView(root);

        Shizuku.addRequestPermissionResultListener(permListener);

        // 服务若被系统杀掉，用户再次打开本应用时自动重新拉起
        if (getSharedPreferences("guard", MODE_PRIVATE).getBoolean("enabled", false)
                && !GuardService.RUNNING) {
            Intent i = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }

        Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                refresh();
                log.setText("守护状态: " + GuardService.snapshot() + "\n\n" + GuardService.STATUS);
                h.postDelayed(this, 1000);
            }
        });
    }

    private Button btn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        if (state == null) return;
        String s;
        if (!Shizuku.pingBinder()) {
            s = "Shizuku: 未运行\n请先打开 Shizuku → 通过无线调试启动";
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            s = "Shizuku: 已授权 (uid " + Shizuku.getUid() + ")";
        } else {
            s = "Shizuku: 服务在运行，但本应用未获授权 → 点上方按钮申请";
        }
        state.setText(s + "\n守护: " + (GuardService.RUNNING ? "运行中" : "未运行")
                + "\nDevice Owner: " + (GuardService.isOwner(this) ? "是（隐藏 + 权限拒绝，无需 Shizuku）" : "否（可用 Shizuku 模式）"));
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        super.onDestroy();
    }
}