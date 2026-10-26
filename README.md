# DeX 主页

独立的三星手机桌面应用，包名 `com.alkaidlab.dexlocal`，当前版本 **0.4.4**。保持免 Root，不依赖 Sunshine 运行。

**实验版下载：[GitHub Releases](https://github.com/nanaoniiiii/dex-home/releases)。** 当前安装包为 [DeX-Home-v0.4.4.apk](https://github.com/nanaoniiiii/dex-home/releases/download/v0.4.4/DeX-Home-v0.4.4.apk)，使用条件和验证范围见 [0.4.4 发布说明](docs/releases/v0.4.4.md)。当前已在 S20+ / Android 13 实测，**60 帧限制尚未解除**。

应用作为 Android 的全屏默认 HOME，内部显示本机系统生成的真正三星 DeX。按手机 HOME 会进入 DeX 主页，并让原生 DeX 返回其桌面；打开的 DeX 应用继续在该桌面中运行。

默认 HOME 入口负责将请求路由到手机显示 0，全屏宿主独立承载 DeX 画面。返回桌面时使用三星在该 DeX 显示上的最小化窗口操作，保留应用任务。

三星系统内部使用 DeX for PC 双屏路径，画面直接合成到手机上的 HOME Surface。没有更改固件的 `standalone` 能力开关；安装和使用不需要 Root。

## 使用

1. 启动手机上的 Shizuku／Stellar，在 DeX 主页中连接并授权。
2. 点击“启动接收服务”，再打开三星 DeX。首次在列表选择 **DeX Home**，确认“立即开始”，然后返回。
3. 查看全屏预览。当前会话创建的 DeX 桌面会按手机画布调整逻辑尺寸。
4. 从软件入口打开设置，选择“设为默认主页并进入”。应用在切换前保存原主页组件。
5. 按 HOME 返回 DeX 桌面。桌面没有本应用的悬浮设置按钮；设置从“DeX 主页”软件入口打开。
6. 退出使用三星任务栏最左侧的应用菜单 → **退出 DeX**。应用自动恢复原来的手机桌面和默认 HOME，接收服务继续运行。
7. 以后直接打开三星 DeX，再次连接 **DeX Home**，会自动进入全屏 DeX 主页并切换默认 HOME。

当前三星原有的任务栏自动隐藏设置会保留。隐藏时可从底部向上滑出任务栏。

## 依赖与范围

需要支持 DeX for PC 的三星固件、已连接的 Wi-Fi（wlan0 IPv4）和 64 位 Shizuku 用户服务。设备自己的系统 DeX APK 提供加密兼容库，应用运行时加载，不随安装包分发。

发现过程使用 Wi-Fi 广播；本地接收端的媒体与控制路径使用本机地址。显示读取原生 DeX 合成画面，经过 SurfaceControl 到主页窗口，不经过 Sunshine、Moonlight 或二次视频编解码。

手机默认主页、系统 DeX 状态、桌面实际绘制帧率和内屏运行模式分别验证。不能把默认 HOME 已改变或内屏刷新率请求成功当作桌面帧率解锁成功。

## 退出与恢复

三星原生“退出 DeX”会恢复保存的 HOME 选择、DeX 逻辑尺寸和高刷实验设置。本次桌面会话结束，后台接收端重建待机，因此目标可持续发现；不保证退出前的 DeX 应用进程和窗口保持运行。

需要完全停止时，在退出 DeX 后打开软件设置，点击“停止接收服务”。该操作还会恢复服务临时控制的“强制外屏桌面”系统设置。

若使用者已通过系统设置选择其他主页，恢复操作保留其新选择。设置恢复区分不存在的键与原值为空字符串。

宿主进程重建时保留会话 30 秒以便重连。超时未恢复时，用户服务尝试恢复原主页、结束当前会话，并继续保持接收端待机。若 Shizuku 尚未启动，主页会提供连接、管理和系统主页设置入口。

“常驻”以接收服务实际运行为前提。系统重启或 Shizuku 自身被终止后，可能需要重新启动权限服务并打开应用；本轮没有验证自动开机恢复或小时级稳定性。

## 刷新率实验

“尝试 120Hz”临时请求三星高刷模式、内屏 min/peak refresh rate，并尝试设置本会话 DeX 的用户首选模式。关闭实验或退出时恢复。

验证使用独立的动态绘制程序，分别记录真实 DeX 源应用的 gfxinfo 帧数、帧时间、系统显示模式和 SurfaceFlinger 状态。系统省电模式开启时会提示先关闭。

只读采样：

```powershell
python tools/measure_source_frames.py --serial <当前ADB序列号> --package <DeX上的动态源应用包名> --output work/my-run --seconds 20
```

诊断文件位于 `Android/data/com.alkaidlab.dexlocal/files/diagnostics.json`，可在应用分享。当前本机基线与高刷请求后的实际源绘制都约为 60.2fps，系统活动模式仍为 60Hz；**60 帧限制没有解锁**。详见 [当前验证记录](docs/HOME-2026-10-26.md)。

## 构建

JDK 17、Android SDK 34、Gradle 8.5、AGP 8.2.2、Kotlin 1.9.22。设置本机 `local.properties` 的 `sdk.dir` 后：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

APK 位于 `app/build/outputs/apk/release/app-release.apk`。当前实验版 Release 构建使用本机 Android debug key 签名，正式发布前需设置长期签名。

原生 standalone 控制器 0.2.0 的历史结论见 [旧验证记录](docs/VALIDATION-2026-10-25.md)。该结论只针对系统原生主屏路径；本版按使用者确认的“全屏默认主页承载真正 DeX”方案实现。

GPL-3.0，来源与依赖见 [NOTICE.md](NOTICE.md)。原 Sunshine 工程保留。
