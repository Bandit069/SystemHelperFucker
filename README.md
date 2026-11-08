# System Helper Fucker

一个**不依赖 root** 的 Android 小工具，用来处理运营商预装在定制 ROM 里的 “SystemHelper” 类隐私采集应用（典型包名 `com.mobiletools.systemhelper`）。

> 不需要 root，不需要解锁 BL，不需要改系统分区。
> 兼容 Android 8.0+（API 26+），targetSdk 34：HyperOS 1/2/3（Android 11–16）与 HyperOS 4 均可运行。在 Xiaomi 17 / HyperOS 4.0.6 / Android 17 上实测可用；其它品牌理论上通用（vivo / OPPO / 一加 / 真我 / 中兴 / 三星等定制机也有同名预装）。

---

## 它做什么

1. **用户级卸载**：通过 Shizuku 拿到 shell(uid 2000) 后执行 `pm uninstall -k --user 0 <pkg>`。
2. **绕过厂商限制**：当 `pm uninstall` 被 ROM 拒绝（例如 MIUI 报 `Failure [only root can delete system app for a particular user]`）时，用 `app_process` 直接调用隐藏 API：

   ```java
   IPackageManager.deletePackageAsUser(pkg, -1, null, 0,
           DELETE_KEEP_DATA | DELETE_SYSTEM_APP)
   ```

   `DELETE_KEEP_DATA = 1`、`DELETE_SYSTEM_APP = 4`，即 flags = 5。
   卸载是 **per-user** 的：系统分区里的 APK 文件还在，但 User 0 不再有安装登记，应用无法运行，也无法接收广播、启动服务。

3. **守护**：前台服务每 30 秒检查一次，目标包一旦重新出现（OTA 恢复、被其它应用装回、`pm install-existing`），立即再次卸载。
4. **可选 Device Owner 模式**：如果设备能设置 Device Owner（无多用户、无账号），则改用 `DeviceManager.setApplicationHidden()` + `setPermissionGrantState(DENIED)`，完全不依赖 Shizuku，重启也有效。

附带的数据层压制（在卸载成功前或 Device Owner 模式下生效）：

- `pm revoke` 定位/后台定位/电话状态权限
- `cmd appops set ... ignore`：定位、后台运行、前台服务、唤醒锁、使用情况访问
- `cmd netpolicy add restrict-background-blacklist <uid>`：单应用后台数据限制

## 构建

只用 Python + JDK，不依赖 Gradle / Android Studio：

```bash
# 需要 JDK 17+，JAVA_HOME 指向它
python build.py
# 产物: build/SystemHelperFucker.apk
```

首次运行会下载 `android.jar`（platform-36）、build-tools 36.0.0 和三个 Shizuku 的 AAR（api / provider / aidl，来自 Maven Central），放进 `.sdk/` 和 `libs/`。
如果本机已有 SDK，可指定复用：

```bash
ANDROID_HOME=/path/to/Android/Sdk python build.py
```

## 使用

1. 安装并启动 **Shizuku**（`moe.shizuku.privileged.api`），用无线调试启动服务。
2. 安装本应用：

   ```bash
   adb install --user 0 -r build/SystemHelperFucker.apk
   ```

   > MIUI 上普通 `adb install` 可能报 `INSTALL_FAILED_USER_RESTRICTED`，加 `--user 0` 即可。

3. 打开应用 → 点「申请 Shizuku 权限」→ 在 Shizuku 弹窗里选「始终允许」→ 点「启动守护」。
4. （可选，MIUI）在 设置 → 应用管理 → 本应用 → **自启动** 打开，否则重启后广播会被厂商白名单拦掉，需要手动再打开一次应用。
5. 恢复被卸载的应用：

   ```bash
   adb shell pm install-existing --user 0 com.mobiletools.systemhelper
   ```

## 目录结构

```
app/
  AndroidManifest.xml
  src/com/dsh/sysguard/
    MainActivity.java        界面（纯代码 UI，无 XML 布局）
    GuardService.java        前台守护服务 + 命令脚本 + 判定逻辑
    PackageUninstaller.java  app_process 入口，反射调用 deletePackageAsUser
    BootReceiver.java        开机 / 自身更新后拉起守护
    PkgReceiver.java         目标包被安装/更新时立即触发
    GuardAdminReceiver.java  Device Owner 模式用的 DeviceAdminReceiver
  res/                       图标与 device_admin 策略声明
build.py                     无 Gradle 构建脚本
```

## 为什么普通 `pm uninstall` 不行

`pm uninstall --user 0` 走的是 shell 命令层的检查，MIUI 的 `PackageManagerServiceImpl.shouldRestrictEnabledSettingsChange()` 会直接抛 `SecurityException` 或返回 `only root can delete system app`。
但 `IPackageManager.deletePackageAsUser()` 本身对 shell 是开放的（shell 持有 `DELETE_PACKAGES` 等权限），用 `app_process` 起一个 Java 进程直接调用，就绕开了命令层的限制。

## 限制

- 非 root 下只能做**用户级**卸载，`/system/priv-app/.../xxx.apk` 仍在系统分区；恢复出厂设置或部分 OTA 会把它带回来（守护会再次处理）。
- 依赖 Shizuku；Shizuku 进程死掉后守护会显示「Shizuku 未就绪」，需要重新启动 Shizuku。
- Device Owner 模式要求设备没有多用户、没有已登录账号，多数日常使用的手机满足不了。
- 不同 ROM 的包名/路径可能不同，可自行修改 `GuardService.PKG`。

## 免责声明

本项目仅用于保护个人设备隐私、卸载用户不想要的预装组件。请在**你自己的设备**上使用；卸载系统应用可能影响运营商相关功能（如 VoLTE 配置、机卡绑定业务），请自行评估。

## License

Apache-2.0