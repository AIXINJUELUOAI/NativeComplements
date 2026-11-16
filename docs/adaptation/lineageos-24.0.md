# LineageOS 24.0 接入

## 状态与证据边界

本轮按用户授权增加 **LineageOS 24.0（Android 17 基线）** 的静态接入，保留 PixelOS/Google Pixel Launcher 原有路径。实现依据不是 ROM 名称或猜测，而是本地拉取的官方仓库：

| 组件 | 官方仓库 | 固定提交 |
|---|---|---|
| manifest | `LineageOS/android` | `913c6baa35ffa32be9ae33368cac69dbec6ead4b` |
| framework/services | `LineageOS/android_frameworks_base` | `5c6d4b35dc4a65cae62391fab5e64386738e6692` |
| native | `LineageOS/android_frameworks_native` | `46018f54b42489de960c62c3db9de131abce43ea` |
| SystemUI 共享库/iconloader | `LineageOS/android_frameworks_libs_systemui` | `0e181d3d5dd8456ee158d9ac8725d9cd59fecd28` |
| Launcher3 | `LineageOS/android_packages_apps_Launcher3` | `fd5398ae59ca898b7907a376df4bd293692ad0fe` |
| ThemePicker（Minimal/极简） | `LineageOS/android_packages_apps_ThemePicker` | `d5a70ebd9cd04dd9e5a98a74efab4bea319248d9` |
| Telecomm | `LineageOS/android_packages_services_Telecomm` | `d75099ec2298dff41dd7607c667344990c52e044` |

源码位于仓库外的 `域外源码库/LineageOS-24.0*`，不进入 Git、不随 APK 分发。`tests/verify_lineageos24.py` 会校验上述提交及本页使用的准确类/成员形状；它只证明**源码合同**，不证明真机、GPU、R8 或 SELinux 行为。

本分支基于上游 `AIXINJUELUOAI/NativeComplements` 的 `320a35c`（含 code147 角落小窗恢复与 code148 Alive Direct Boot）；上游已有实现保留，本提交只增加 LineageOS 目标解析、Minimal 能力适配和缺失 ABI 的探针/回退。

## 发行版/宿主选择

- 静态作用域扩为四个：`system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`、`com.android.launcher3`。
- `core/LauncherTargets` 是唯一的 Launcher 身份选择器：
  - PixelOS/Google 构建使用 `com.google.android.apps.nexuslauncher`；
  - LineageOS 24.0 使用 `com.android.launcher3`；
  - 资格、UID、广播目标、HISTORY/QUERY 均按**实际 HOME 包**解析，不再把 Pixel 包名写死在业务代码中。
- 两个 Launcher 实现仍各自保留真实类/成员探测；缺失能力回退原生或报告 unavailable，不能因为 ROM 名称直接安装另一发行版 Hook。

## 已接入的 LineageOS 差异

| 功能 | 原 PixelOS 形状 | LineageOS 24.0 | 接入方式 |
|---|---|---|---|
| 小窗框架 | WMS/Task/Transition 签名 | 同一 AOSP 17 基线，`computeFreeformBounds`、`moveTaskToBackInner` 等为私有 | 现有精确反射 + `setAccessible` + `PROTECTIVE`；无源码外猜测 |
| 返回输入 | synthetic `EdgeBackGestureHandler$$ExternalSyntheticLambda20` | Kotlin `DisplayBackGestureHandlerImpl(onInputEvent: Function1)` | PixelOS 精确 synthetic 路径优先；不可用时从构造器捕获**实际 lambda 对象**，按捕获的 `EdgeBackGestureHandler` 关联，不猜类名 |
| 通知小窗 | `StatusBarNotificationActivityStarter` synthetic Lambda6/7/10/12 | 私有 `sendPendingIntent(PendingIntent,Intent,Bundle,NotificationEntry)` | code146 已停用通知点击转小窗入口；若入口重新启用，仅在该真实发送点拦截，失败回退原发送 |
| 截图优先级 | `ScreenshotWindow.window` 字段 | Kotlin `val window: PhoneWindow`（getter） | 优先字段，缺失时调用 `getWindow()` |
| 图标 | Launcher3 Java 字段、`BitmapInfo.themedBitmap` 字段 | Kotlin `getThemedBitmap()`、`LauncherAppState.getIconCache()/getModel()`、`BaseIconCache.removeIconsForPkg(String,UserHandle)` | 字段/getter 双探针 + 两种参数顺序探测 |
| 抽屉莫奈/主题 | `ThemeManager.iconState/themeController` 字段 | Kotlin getter，`verifyIconState` 私有；图标样式称为 Minimal（极简） | getter/字段双探针；开启抽屉莫奈时经 `ThemePreference.setValue(MONO_THEME_VALUE)` 自动切换，关闭恢复；无 themedicon provider 时禁用并提示 |
| 导航栏 | `NavigationBar` + Pixel Taskbar | 同名 SystemUI 类 + `com.android.launcher3.taskbar.TaskbarInsetsController` | 同一 ABI 预检；Launcher 包门控改为 LauncherTargets |
| 横屏双栏 | legacy `SplitShadeStateControllerImpl` + Pixel Compose repository | 仍存在 `shouldUseSplitNotificationShade`；`ShadeConfigRepository` 存在，但源码树没有 PixelOS 的 `ShadeModeInteractorImpl`/`ConfigurationRepositoryImpl` | Scene 构建优先尝试 Compose；类缺失时保留 legacy `updateResources`/`shouldUseSplitNotificationShade`，不整体撤销 |
| 信号显示 | synthetic `MobileIconBinder$bind$...` + named repository callbacks | `MobileIconBinder.bind` + 单个匿名 `TelephonyCallback` | 绑定 `mobile_signal` drawable→subId；从公开 `TelephonyManager.registerTelephonyCallback` 读取原始 SignalStrength/ServiceState |
| 来电拦截 | CallsManager/CallFilteringResult | 同名 AOSP Telecom 结构 | 保持现有预检和原生结果优先 |
| 传感器/边缘 | ActivityRecord/InputManagerService | 同一 AOSP 17 基线 | 保持现有纯策略和原生执行边界 |
| Alive 普通壁纸 | 自有 WallpaperService | 标准 WallpaperService；ambient 绑定签名相同 | 普通照片壁纸与资源导入保持可用 |
| Alive AOD 系统投影 | PixelOS `WallpaperRepositoryImpl`/`NotificationShadeDepthController`/LightReveal 细节 | LineageOS 的壁纸 Flow、Scene/Keyguard 实现不同 | **未声称可用**：不安装不确定的 PixelOS 投影，保留原生 AOD/认证/电源路径 |

## 关键边界

- 不按 `ro.lineage.*` 字符串直接改业务行为；Launcher 包与实际 HOME 作为**执行目标**，具体 Hook 仍按真实类/成员形状预检。
- `scope.list` 增加 Launcher3 后，PixelOS 上不存在的包不会安装 Hook；LineageOS 上 Pixel 包不存在也不会被借用。
- `LauncherThemeReporter`、`DrawerMonet`、`IconLayerModule` 对 Kotlin 属性使用 getter-first/field-fallback；PixelOS 的 Java 字段路径仍先命中。
- `LandscapeShade` 在 Scene 构建优先安装 Compose 路径；Compose 安装不可用时回退 legacy，不再因共享的 Scene 标志直接禁用。非 Scene 构建保留原 legacy + 可选 Compose 组合。
- 通知小窗的 LOS `sendPendingIntent` 兜底已按真实源码预检，但 code146 的 `notificationWindowReady()` 恒为 false，因此当前不会改写通知点击；文档不把该停用入口算作 LOS 已交付功能。
- `AliveSystemUi`/`AliveSleepHandoff` 没有在 LineageOS 上伪造支持；普通 Alive 服务和资源导入不受影响。AOD 实验的系统 UI 平滑交接必须在取得 LineageOS 实际 SystemUI ABI/设备证据后另做，不以本轮源码合同冒充完成。

## 实机记录（OnePlus Ace 3 / PJE110，LineageOS 24.0-20261113-NIGHTLY-astonc）

- Vector 已加载模块，作用域由模块固定为 `system_server`、`com.android.systemui`、`com.android.launcher3`；Launcher3 为实际 HOME。
- Launcher3 日志确认 `IMMERSIVE_TASKBAR_READY hooks=4`、`IMMERSIVE_TASKBAR_CONFIG enabled=true`、`IMMERSIVE_TASKBAR_REFRESH updated=1`；SystemUI 也读取到 `IMMERSIVE_CONFIG enabled=true`。
- 抽屉莫奈实测：开启后自动出现 `auto Minimal icon style enabled`、`enabled=true user=true systemThemed=true`，关闭后 `reload ... enabled=false` 且 `reported systemThemed=false`，说明 Minimal 自动切换与恢复均生效。LineageOS 的入口名称是“极简/Minimal”，不是 Pixel 的“主题图标”。
- Launcher 侧还确认 `LAUNCHER_ROTATION_READY`、`GUARD_READY hooks=3`、`RECENTS_READY hooks=1`；SystemUI 侧旋转 Hook 上报已安装。
- 横屏/竖屏往返时 `LandscapeShade` 已进入 legacy 决策点（`SHADE_DECISION false/2/864/false`、`false/1/393/false`）；当时“横屏双栏”开关为关闭，因此 decision 为 false，但这证明 LOS legacy controller 路径被真实调用。开启后的分栏视觉仍需单独确认。
- 导航栏：Launcher3 的 Taskbar 路径持续上报 `IMMERSIVE_TASKBAR_SEEN` / `IMMERSIVE_TASKBAR_REFRESH updated=1`；SystemUI 的 `IMMERSIVE_APPLY` 记录为 `bars=0`，即本机未捕获到 SystemUI `NavigationBar` 实例。当前只确认 Launcher Taskbar 宿主路径，不把 SystemUI 路径写成实机通过。
- 边缘防误触开启后持续出现 `EDGE_INPUT_REGIONS` / `EDGE_INPUT_BACKEND` 心跳，说明 InputFilter 边界与 SystemUI 区域同步生效。
- 来电拦截开启且 Telecom 配置已恢复（`CALL_BLOCKING_CONFIG_RESTORED`）；实际来电过滤未在本次记录中触发。

未在本轮完成的实机项：角落小窗实际呼出（上游 code147 已恢复 corner Hook；本轮未在桌面角落滑动）、弹幕通知、加速度传感器限制、横屏双栏开关开启后的视觉、信号显示视觉、实际来电、Alive AOD。上述项目只完成源码/静态检查，不能写成实机通过。

## 静态检查与结果

```bash
python3 tests/verify_lineageos24.py
python3 tests/verify_runtime.py
./gradlew --no-daemon --console=plain :app:compileFullDebugJavaWithJavac :app:compileWindowDebugJavaWithJavac
./gradlew --no-daemon --console=plain :app:testFullDebugUnitTest \
  --tests 'dev.local.sunwindowdemo.features.shade.ShadeTest' \
  --tests 'dev.local.sunwindowdemo.features.iconlayers.DrawerMonetTest' \
  --tests 'dev.local.sunwindowdemo.features.shade.SignalDisplayTest' \
  --tests 'dev.local.sunwindowdemo.features.iconlayers.LauncherThemeReporterTest' \
  --tests 'dev.local.sunwindowdemo.LinkedWindowsTest'
```

本机结果：

- `verify_lineageos24.py`：通过，覆盖 framework/window、SystemUI、Launcher3/iconloader、Telecom、wallpaper 的固定提交与类/成员形状。
- `verify_runtime.py`：通过；已将原 Windows 专用 `javac.exe/java.exe` 改为跨平台 JDK 工具查找。
- full `compileFullDebugJavaWithJavac`：通过；上游 code147 起已取消独立 window product。
- 上述定向单测通过。全量 `testFullDebugUnitTest` 为 401 项中 51 项失败、`testWindowDebugUnitTest` 为 391 项中 54 项失败；失败集中在既有的 `BackHandoffTest`/`DropAdmissionTest`/`MonetSurfaceTest`、Robolectric `sdk=36` 的 `DefaultSdkProvider` 限制及 `NotificationWindowTest` 既有偏好断言，未宣称全量通过。
- `NavbarModuleTest`/`NavbarProviderTest`/`NavbarTaskbarHostTest` 在本机因 Robolectric `sdk=36` 的 `DefaultSdkProvider` 限制未能启动，属于既有测试环境问题；`NotificationWindowTest.notificationPreferencePersistsAndCanBeDisabled` 仍为既有失败。未把这几个用例描述为已通过。
- 未运行主版 release/R8、原签名 APK 核验，也未安装、重启、修改 LSPosed 或断言 LineageOS 真机行为。静态源合同通过不等于窗口/输入/图标/GPU/动画已在该设备验收。
