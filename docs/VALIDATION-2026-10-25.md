# 2026-10-25 原生 standalone 路径验证（0.2.0 历史记录）

本记录针对 0.2.0 的系统 standalone 路径。后续使用者明确接受“应用作为全屏默认 HOME，内部显示真正 DeX，HOME 返回 DeX”，0.3.1 已按此方案继续实现；本记录不代表 HOME 方案不可用。

## 结论

**在当前 SM-G986U1 固件、免 Root 条件下，手机主屏原生 DeX 尚未开启，60fps 限制尚未解锁。**

独立应用已改为 0.2.0「DeX 开关」，只控制三星系统主屏模式。当前系统能力不足时显示限制，不自动创建第二屏、不打开桌面镜像窗口。旧原型的显示与绘制结果不能计入本轮主屏原生 DeX 验收。

## 设备与权限

| 项目 | 本轮结果 |
| --- | --- |
| 设备 | Samsung SM-G986U1 / S20+ |
| Android / One UI | 13 / 5.1，`ro.build.version.oneui=50100` |
| 固件增量 | `G986U1UESCHYC1` |
| 系统类型 | `user`，`ro.debuggable=0` |
| 当前用户服务身份 | shell，UID 2000 |
| 启动验证状态 | `ro.boot.flash.locked=1`，`ro.boot.verifiedbootstate=green` |
| su 检查 | PATH 及常见路径未发现可用 su；未触发 Root 授权请求 |
| 当前约束 | 使用者明确要求保持免 Root |

## 原生主屏路径

系统 `SEC_FLOATING_FEATURE_COMMON_CONFIG_DEX_MODE` 的实际值为：

```text
dual,wireless,dexforpc
```

应用在本机运行时读取 `DesktopModeFeature.SUPPORT_STANDALONE=false`。本地保存的三星 Android 13 服务分析中，构造函数根据这一能力选择 PhonePreconditionChecker，主屏 Standalone 切换走另一个分支；所核对的 Binder 接口没有修改这一静态能力的入口。本轮未重新提取并比对整份 services.jar，因此服务代码用于解释机制，本机运行结果以能力读回和实际模式状态为准。

本轮曾直接发送系统广播：

```text
am broadcast --user 0
  -a com.samsung.android.desktopmode.action.DESKTOP_MODE_UPDATE_REQUEST
  --ei com.samsung.android.desktopmode.extra.DESKTOP_MODE_STATE 1
  --ei com.samsung.android.desktopmode.extra.DESKTOP_MODE_SOURCE 3
```

广播返回 `result=0`，但随后系统仍为：

```text
mStandaloneModeEnabled=false
mDesktopDisplayId=-1
mDualModeEnabled=false
```

请求已经送达不等于系统模式切换成功。测试后的请求已撤回。

`cmd desktopmode help` / `cmd desktopmode` 返回 `No shell command implementation.`；当前正式固件没有开放所核对的调试命令入口。未尝试修改系统特性文件、解锁引导程序或安装 Root 模块。

## 0.2.0 应用实测

连接 Shizuku 后，应用读到：

```json
{
  "mode": "native_standalone_controller",
  "active": false,
  "enabled": 2,
  "state": 0,
  "display_type": 0,
  "standalone_supported": false,
  "system_dex_modes": "dual,wireless,dexforpc",
  "high_refresh_requested": false,
  "pending_settings": 0
}
```

点击“开启本机 DeX 桌面”后，明确显示当前固件未启用 standalone，并保留关闭状态。高刷和主屏帧率检查仅在稳定的主屏原生状态 `enabled=4, state=0, displayType=101` 下启用。

应用不将 `displayType=102` 的外屏 DeX 当作主屏成功；没有 SurfaceView、NativeDexMirror、LocalPcPeer 或桌面承载 Activity。APK 中这些旧类型的描述符已核对不存在，仅申请 Shizuku API 权限，不申请 Internet 或屏幕录制权限。

关闭控制器不会请求退出系统 DeX。这项生命周期设计和真实主屏退出恢复尚需在已开放 standalone 的固件验证；当前手机没有达到该前置状态。

## 构建与包核对

- `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease` 成功。
- 8 项 JVM 检查通过，包括主屏／外屏／过渡态判定及设置恢复；不作为真实主屏 DeX 开启证据。
- lint：0 错误，4 警告。
- Release APK 签名验证通过，APK Signature Scheme v2。
- 包名 `com.alkaidlab.dexlocal`，版本 `0.2.0` / versionCode `2`，大小 115296 字节。
- 本机 APK 与设备已安装 base.apk SHA-256 相同：`671edebae7c0650621f2c20210eaf886ef4bd18086d9e215eeaa012e2de0349b`。
- 原 Sunshine 工程未修改。

本地原始证据位于被忽略的 `work/native-validation/`：`blocked-state.json`、`blocked.png`、`after-start-desktopmode.txt`、`after-start-displays.txt`、`package-check.json`。构建日志为 `work/build-native-controller.log`。

## 验收边界

当前完成的是独立的原生模式控制器、系统限制识别和免 Root 路径验证。使用者要求的手机主屏 DeX 效果仍未实现，原生主屏 60/120fps 对照尚不具备测试条件。

已有 [Magisk／KernelSU standalone 模块](https://github.com/supermarsx/magisk-samsung-dex-standalone-mode) 的实现需要 Root，并修改系统 floating feature。它不符合本任务最终的免 Root 约束，未安装、未运行，也未据其说明宣称本机支持。
