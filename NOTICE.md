# 来源与依赖

DeX 主页沿用 Sunshine Android 的 GPL-3.0 许可，完整许可见 `LICENSE`。提取起点为本地 Sunshine 工程提交 `c1f8f447cbcfbdde9d6002ceddb459f45280747f`。

提取的实现包括 SamsungLocalDex、NativeDexMirror、CaptureGeometry 和 headless 下的五个本机协议类；已使用独立命名空间及接收端名称、标识。全屏 HOME、主页备份恢复、生命周期管理、尺寸适配、设置恢复和应用界面在本工程实现。

使用 Shizuku API / Provider 13.1.5，来源为 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。Android HOME 角色接口参考 [RoleManager](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_HOME)。

安装包和源码交付不包含三星 APK、厂商原生库、系统反编译材料或个人配对数据。本地原始证据位于被忽略的 work 目录。
