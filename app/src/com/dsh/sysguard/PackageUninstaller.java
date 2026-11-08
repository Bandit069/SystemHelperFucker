package com.dsh.sysguard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 以 ADB shell(uid 2000) 身份运行：直接调用 IPackageManager.deletePackageAsUser，
 * 绕过 MIUI 对 `pm uninstall` 系统应用的拦截，做 User 0 的用户级卸载（保留数据）。
 *
 * 用法（由 GuardService 通过 app_process + 本应用 APK 作为 CLASSPATH 调用）：
 *   com.dsh.sysguard.PackageUninstaller <package> <userId>
 */
public final class PackageUninstaller {
    public static void main(String[] args) {
        try {
            int uid = (Integer) Class.forName("android.os.Process").getMethod("myUid").invoke(null);
            if (uid != 2000) {
                System.out.println("SKIP_NOT_SHELL uid=" + uid);
                return;
            }
            if (args.length < 2) {
                System.out.println("USAGE: PackageUninstaller <package> <userId>");
                return;
            }
            String pkg = args[0];
            int user = Integer.parseInt(args[1]);

            Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
            Object pm = Class.forName("android.app.ActivityThread")
                    .getMethod("getPackageManager").invoke(null);
            Class<?> pmc = Class.forName("android.content.pm.PackageManager");
            int flags = pmc.getField("DELETE_KEEP_DATA").getInt(null)
                    | pmc.getField("DELETE_SYSTEM_APP").getInt(null);
            Method del = ipm.getMethod("deletePackageAsUser", String.class, int.class,
                    Class.forName("android.content.pm.IPackageDeleteObserver"), int.class, int.class);
            try {
                del.invoke(pm, pkg, -1, null, user, flags);
                System.out.println("DELETE_REQUESTED " + pkg + " user=" + user + " flags=" + flags);
            } catch (InvocationTargetException e) {
                System.out.println("DELETE_FAILED " + e.getCause());
            }
        } catch (Throwable t) {
            System.out.println("ERROR " + t);
        }
    }
}