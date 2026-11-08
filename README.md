# 类原生补全

面向小米 15 / PixelOS Android 17 的个人系统增强模块。第一版只接入小窗，首页不展示尚未实现的功能。管理界面采用 Compose Material 3 Expressive，支持系统动态颜色与深色模式。

## 当前版本

`0.1.0-preview1`，versionCode 13，applicationId `dev.local.sunwindowdemo`。

沿用 Demo8.2 的包名、签名和协议 14，覆盖升级保留 SharedPreferences("corner")、快捷应用顺序、禁用应用和既有设置。26 个运行层 Java 文件保持逐字节一致，包括窗口承载、白条、扇形菜单、返回长滑、迷你窗、动画、历史隐藏、恢复记录与三个 Hook 入口。`tests/verify_runtime.py` 以原始源码 ZIP 及摘要核对。

仍保留一个活动小窗、180 秒测试期限及原版已知问题；本次没有承诺完成 IME、完整 Shell 转场或所有应用兼容性。当前签名是原型测试签名，后续正式发布需单独规划签名迁移。

## 安装

1. 先在旧版恢复/关闭小窗，确认没有待恢复记录。
2. 覆盖安装 `deliverables/NativeComplements-0.1.0-preview1.apk`，无需卸载或清除数据。
3. LSPosed 保留三个作用域：`system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`，正常重启。
4. 打开“类原生补全”→ 小窗 → 诊断与恢复，检查后端和无障碍服务。服务名称更新为“类原生补全 · 小窗控制”，组件名未变。
5. 优先核对原快捷应用、各项参数和黑名单；再检查扇形菜单、侧边长滑、普通/迷你/全屏、关闭与恢复。

安装、重启、手机手势操作由用户执行。电脑端检查与离屏截图不等于 LSPosed 真机验收。

## 工程

单 `app` Gradle 模块。界面组织为 `home`、`navigation`、`core/ui`、`features/floatingwindow/{ui,config,legacy}`。

为了保留包内访问、反射路径及 Android 组件身份，小窗 Java 运行层目前仍使用 `dev.local.sunwindowdemo` 包，集中在 `legacy` 目录；没有在同一次迁移中重新拆解窗口后端。`MainActivity` / `SettingsActivity` 的组件名也保持不变。

管理界面自己的适配类使用原来的偏好键，不重写小窗配置逻辑。系统端运行类不引用 Compose。旧版诊断 Activity 完整保留原有主题和行为。

## 构建

需要 JDK 17+、Android SDK `android-37.0`、Gradle 8.14.4。首次构建需要访问 Google Maven 和 Maven Central。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --console=plain :app:assembleRelease :app:testDebugUnitTest
python tests\verify_runtime.py
```

`local.properties` 指定本机 SDK；原有 Demo 升级密钥保存在被 Git 忽略的 `.local/demo.keystore`，不打包进 APK。libxposed API 101.0.1 仅为 compileOnly，保存在 `app/libs`，不随 APK 打包。

原始 Demo8.2 APK、源码 ZIP 和静态声明检查报告保存在 `baseline/`；静态报告为历史证据，没有将其冒充本次设备检查。

## 停用和回退

先用“诊断与恢复 → 恢复原窗口”结束小窗，再到系统无障碍设置停用服务；如需停用 Hook，在 LSPosed 禁用模块后正常重启。

`baseline` 中的旧 APK versionCode 为 12，不能当作可直接覆盖新版的回退包。不要为降级而卸载或清除数据。需要回到旧管理界面时，可基于保留的旧源码、同一签名构建更高版本号的恢复包；该回退包本轮未生成或安装。

## 后续范围

先对第一版进行同设备迁移验收，再按具体复现步骤修复小窗 BUG。下一个功能做到可用后，才添加自己的首页条目、配置和设置页。统一跨进程远程偏好、启动健康计数和完整动态 Hook 启停尚未新增；现有预检、初始化异常回滚和窗口恢复机制保留。
