# NativeComplements

类原生 Android 功能补全模块。当前版本为 `0.2.0-preview20`（versionCode 33），目前只有小窗功能。

设置界面采用 Compose Material 3 / Material 3 Expressive、系统动态颜色和深色模式。首页按功能独立跳转，功能可用后再加入入口。

## 当前小窗实现

- 基于原生 freeform 的默认小窗、mini、小窗与全屏切换，以及关联任务的独立小窗和返回接续。
- 可见 mini 的生命周期与前台输入焦点分开处理；长按菜单提供本应用静音和 mini 大小切换，按钮与图标复用 SunOS 资源。
- 保留应用方向、系统栏取色、最近任务恢复、锁屏后的窗口状态和固定浅色操作条。
- SystemUI 承载操作层，system_server 管理任务；不依赖无障碍，不要求为普通应用逐个勾选 LSPosed 作用域。

这是一套针对已检查的 Android 17 / API 37 私有接口实现的 LSPosed 适配，并非 SunOS ROM 模式的完整移植。构建、主机检查与真机反馈分别记录，见 [VERIFICATION.md](VERIFICATION.md)。

## 环境与构建

- JDK 17+、Android SDK `android-37.0`，Gradle Wrapper 8.14.4。
- SDK 路径通过 `ANDROID_HOME` 或本地 `local.properties` 配置。
- 覆盖升级沿用本机 `.local/demo.keystore`；该文件不进入 Git，新的工作目录需要在本地配置原签名。

Windows 下执行：

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleRelease
```

构建、检查包身份和签名并交付 APK：

```powershell
python tests/package_release.py
```

产物位于 `deliverables/NativeComplements-<version>.apk`。脚本只生成 APK，不生成源码 ZIP、备份或验收报告，不安装应用、不操作设备。

## LSPosed 作用域

固定三个作用域：

- `system`
- `com.android.systemui`
- `com.google.android.apps.nexuslauncher`

包名保持 `dev.local.sunwindowdemo`，宿主协议为 16。安装和重启由用户自行安排。

## 项目结构

- `app/src/main/java/dev/local/sunwindowdemo/home`、`navigation`、`core/ui`：首页、导航和共用界面。
- `features/floatingwindow/config`、`features/floatingwindow/ui`：小窗配置与设置页。
- `features/floatingwindow/legacy`：当前系统 Hook、任务管理、窗口呈现和输入处理。
- `tests`：主机检查、归档接口签名和打包工具。
- `baseline`：不可变 Demo8.2 来源、摘要和历史构建证据。

`python tests/verify_runtime.py` 检查 Demo8.2 来源、变更登记及主机规则；`python tests/verify_target.py` 检查归档目标的一部分接口签名，后者不代表当前设备完整兼容性。

## 开发约定

以 `main` 集成，已交付版本使用 tag 固定。并行功能各用独立分支和 worktree；系统 Hook 入口、共用配置、首页导航与版本号统一集成。运行代码相对 Demo8.2 的变更原因记入 `tests/runtime-changes.json`。

日志、构建缓存、设备采集、签名和交付文件不提交；源码通过 Git 版本管理，不创建逐轮目录副本。

## 来源

项目与复用资源的授权及修改说明见 [LICENSE](LICENSE) 和 [NOTICE.md](NOTICE.md)。原 Demo8.2 基线保持不变。
