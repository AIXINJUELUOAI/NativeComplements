# LineageOS 24.0 接入

## 来源与集成

贡献者：[LFRon](https://github.com/LFRon)（提交作者 mozixun）。原始贡献为 [PR #4](https://github.com/AIXINJUELUOAI/NativeComplements/pull/4)，提交 `89b0c783518b833da5f39bfa5de0131a13de392b`。集成基于 code165，保留 PR 祖先及作者，不将其中较旧的 code147/148 Alive 内容覆盖当前实现。

维护者记录仅用于首次一次提示、关于页署名与反馈路由，不作为权限或兼容性认证。维护者仓库未开启 Issues 时使用[主项目反馈](https://github.com/AIXINJUELUOAI/NativeComplements/issues)，不要求贡献者补测试模板或样例，不在每个功能页添加“未适配／未验证”提醒。

## 真实接入，而非 ROM 分支

- 静态作用域为 `system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`、`com.android.launcher3`，仅新增 Launcher3 宿主，不新增普通应用作用域。
- [LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)负责已知桌面包与实际 HOME／UID；不是任意 HOME 即获得模块能力，也不按 ROM 字符串决定 Hook 安装。
- 相同 Hook 共用。真正不同的 Hook 目标、参数布局和安装时机集中在功能自己的小接入／Targets，公共状态、规则、身份核验和恢复逻辑不复制。
- 必要 Hook 按宿主实际类、方法、字段类型预检，未知形状保持原生；尚无回报不等于不支持。安装成功不表示视觉、功耗和全部恢复已验收。

## PR 差异的集成取舍

| 范围 | 集成方式 |
|---|---|
| 小窗框架 | 复用既有 FrameworkAccess 与共用准入／恢复，集中 Task resizability、ActivityStarter 参数布局目标 |
| 返回输入 | 保 Pixel synthetic 路径；Kotlin Function1 从构造参数捕获真实 callback，严格归属和事件资格不放宽 |
| 通知小窗 | 保 main 已恢复的正文原 PendingIntent、派发前原生回退及派发后不重发；LOS 通过精确发送点接入 |
| 截图 | 字段／Kotlin getter 仅局部只读解析，不复制截图业务 |
| 桌面图标 | 接入 Kotlin getter 和缓存参数布局；相同渲染 Hook 共用 |
| 抽屉莫奈 | **不集成 PR 的自动 Minimal 切换**：该行为会改变系统桌面主题偏好，并非单纯 ABI 适配。保原生主题门控；用户自己在系统“壁纸与样式”中选择主题图标／Minimal |
| 导航栏 | 既有 SystemUI／Taskbar 宿主边界保留，相同 Hook 不再套 ROM 工厂 |
| 横屏双栏 | Legacy 与 Hydrated 的不同安装目标隔离，规则共用；不强开 Scene，不写只读 Flow |
| 信号 | 原有回调和 stacked targets 保留；不同 binder／TelephonyCallback 接入独立，避免重复 Hook 和半安装 |
| 来电／传感器／边缘 | 相同接口及共用规则保留，桌面排除范围加入 Launcher3 |
| Alive | 当前 code165 自有壁纸、Direct Boot、安全门及视觉实现保留；不伪造新系统投影、不做 ROM 名称禁用或逐功能警告 |

## 合并后复核与补充适配（2026-11）

上游合并 PR 后继续按官方 `lineage-24.0` 源码复核新增 Hook；以下均为**实现适配**，不是 ROM 名称分支：

| 范围 | 复核结果与处理 |
|---|---|
| 桌面双击锁屏 | LineageOS `Launcher.getDragController()` 返回 `LauncherDragController`（`DragController` 子类），不再要求精确返回基类；只放宽返回值协变，仍校验 `isDragging` 和空白 Cell |
| 深色 AOD | LineageOS `DozeMachine.mDozeHost` 声明为 `DozeHost` 接口，`mAlwaysOnSuppressed`／`mBatteryController` 实际在 `DozeServiceHost`；改为校验接口后读取实现字段 |
| Alive 壁纸投影 | `WallpaperInteractor.wallpaperRepository` 和 `WallpaperRepositoryImpl` 构造参数在 LineageOS 使用接口类型；先试实现类，再按源码回退到 `WallpaperRepository`／`ConfigurationInteractor` 接口 |
| Alive 睡眠交接 | `NotificationShadeWindowControllerImpl.mWindowRootView` 是 `ViewGroup`，`mScrimsVisibilityListener` 是 `Consumer`；改为按可赋值的实际字段读取，并从实际监听器实例解析捕获的 `NotificationShadeDepthController`，不再依赖 `$1`/`WindowRootView` 精确字段类型。`KeyguardService` 的 binder 实现按两个已核实方法扫描，不依赖匿名类编号 |
| 横屏双栏（Legacy/Scene） | LineageOS `NotificationPanelViewController.mSplitShadeStateController` 声明为接口；接收接口或其实现。`ShadeConfigRepository.legacyUseSplitShade` 是 Kotlin getter `Flow`，没有 PR 使用的字段/R8 lambda 时改用实际 Flow 实例的 `collect` 作用域，保留原字段式 Pixel 路径；回调刷新为可选 |
| 旋转建议 | LineageOS `RotationButtonController.mRotationButton` 声明为 `RotationButton` 接口，不再假定具体 `mKeyButtonView` 字段；使用接口的 `getCurrentView()`，保留原生建议、超时和点击策略 |
| 信号叠层 | `StackedMobileIcon` 的 R8 重启 lambda 编号不保证固定；缺失该合成类时跳过它，保留实际 Compose render 与父 composable 的叠层适配，不再因此关闭整条路径 |
| 短信拦截 | 固定检查 `com.android.phone`、`InboundSmsHandler.processMessagePart/filterSms/deleteFromRawTable`、`InboundSmsTracker` 与 `BlockChecker.getBlockStatus`；实现只在电话进程安装，规则和原生永久删除路径不变 |
| 来电拦截 | 保留既有 Telecom `CallsManager.onCallFilteringComplete`、`CallFilteringResult.Builder` 和 `PhoneAccountRegistrar` 接口；不新增绕过原生过滤的路径 |
| 抽屉主题提示 | 实际 HOME 为 Launcher3 时提示用户把应用图标样式设为 **Minimal**；仍由用户自行选择，不自动改写 `ThemePreference` |

未发现必须整项禁用的 LineageOS 24.0 功能：上述目标在固定源码中存在；当前限制是设备行为、R8 实际形状和视觉/功耗仍需真机回归，不能用本页静态合同替代。

## 上游资料与检查边界

PR 附有[固定源码来源](<../../tests/lineageos24-provenance.json>)及[源码合同检查脚本](<../../tests/verify_lineageos24.py>)，固定官方 `lineage-24.0` 的 framework、SystemUI 库、Launcher3、Telecomm、Telephony 与 ThemePicker 提交。脚本需要贡献者机器上仓库外源码树；没有该树时输出 **SKIP**，不记为通过，也不把贡献者文档中的旧主机或设备结论当作当前验收结果。

PR 原文提到 OnePlus Ace 3 / LOS24 的加载、Taskbar、旋转、边缘同步及主题切换记录，但原始设备材料未随仓库提供，不依赖这些声明作为验收；其中自动主题切换已明确不纳入。无需补交材料才允许使用，也不以缺材料为由给每个功能弹警告。

当前架构与检查入口见[接入索引](<README.md>)，发布历史见 [Git 提交记录](https://github.com/AIXINJUELUOAI/NativeComplements/commits/main)。不维护按功能的认证矩阵，不自动安装、重启、改 LSPosed 或设备设置。
