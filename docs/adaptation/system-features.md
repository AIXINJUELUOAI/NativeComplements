# 系统功能接入与适配边界（非小窗主体）

## 当前实施状态：0.10.0 / code139

第一轮小窗规则边界与同源产品隔离已完成代码闭环，运行代码已 push 至 `a0f3a7b`，集中证据见[最新进度](<README.md#最新进度恢复与生命周期闭环>)。**用户已追加全部功能解耦、完成并 push 后再接入 Alive 的授权**；非小窗规则边界与当前证据从[总索引](<README.md#非小窗功能接入入口>)进入，不用本历史正文代替新边界；下文是 full 的 138 历史审计，不应将其中完整图标能力或安装链套用于窄 window 产品，也不表示设备/LOS 已兼容。

## 138 历史审计（以下正文）

> 审计基线：`main` / `930a22c`，versionCode 138（0.9.48-fixes-test2）。以下保留当时源码审计、共享依赖与证据缺口，含“当前/未拆分”等表述均按历史阅读，不代表现状态。源码链接已迁移到现路径、去除不可靠行锚，仅是定位参考，不是旧 snapshot 的精确函数内容。本页原审计仅阅读源码与检查脚本，未构建或访问设备；当时归档/基线检查由 Lead 汇总。小窗主体见专项文档。

## 1. 如何理解“支持”

适配由 **发行版/构建版本 + Android 版本 + 实际组件 ABI/资源/调用链 + 宿主环境**共同决定，不能只凭 ROM 品牌、设备型号、同一 API 级别或存在同名类推定兼容。SystemUI、Launcher、Telecom、framework/services 可以分别更新；同属 API37 的 current 与 QPR2 已有参数数量、字段类型、Compose 生成入口差异。

本页区分四层证据：

1. **源码行为**：能说明模块尝试做什么、异常如何处理，不能证明该机已命中。
2. **归档 ABI**：脚本检查指定 hash 的成员、部分真实 DEX 调用；只对检查的构建和路径有效。检查器自身使用符号库存还是重新读取归档，强度也不同。
3. **JVM/Robolectric 测试**：规则、假宿主、真实 SDK 对象的局部回归；不等于 ART Hook、Binder 权限、系统锁和真机动画验证。
4. **运行证据/用户反馈**：只覆盖实际操作过的场景。仓库当前说明记录沉浸、安全 UI、重叠触控已反馈正常，不能由此扩展成全部键盘、横屏、导航模式、用户/显示器或其他发行版保证。

本页没有通用“ROM 已支持”列表。新增构建应先核实实际 owner/声明类、精确参数与返回、资源、直接调用者和 ART 内联，再验证启用、关闭、重建、锁屏/IME/系统优先级；缺证据时保留原生，不增加全局兼容 Hook。

## 2. 安装入口总图与共享状态

正式入口由 [java_init.list](<../../app/src/full/resources/META-INF/xposed/java_init.list>) 列出的七个类构成。作用域由 [scope.list](<../../app/src/main/resources/META-INF/xposed/scope.list>) 与 [module.prop](<../../app/src/main/resources/META-INF/xposed/module.prop>) 固定为 `system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`，API101、`staticScope=true`。静态范围是否限制选择或清理旧授权是框架实现，不由本模块直接写 LSPosed 数据库。

| 入口 | 精确进程/生命周期门控 | 本页负责的委托 |
|---|---|---|
| [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>) | `isSystemServer()`；`onSystemServerStarting` 只尝试一次 | 亮屏建议 `installUndimSuggestion`、`EdgeInputFilter.install`、`MotionAccess.install` 在小窗 `FrameworkAccess` 大块安装前独立尝试 |
| [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>) | 仅 SystemUI 主进程/包；安装一次 | Application 创建后连接 shade/offset/signal/rotation/host；独立安装输入 admission、通知观察、录屏适配；随后安装长返回与截图优先级 |
| [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>) | Pixel 包主进程及 `包名:` 子进程被允许；包匹配、一次尝试 | Launcher 旋转建议先独立安装；触摸服务 guard 成功后安装 BackVisual、独立可选 RecentsFilter |
| [CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>) | system_server，独立于小窗入口 | Telecom 来电过滤 |
| [IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>) | Pixel Launcher **主进程**/包，一次安装 | 桌面素材、抽屉莫奈、可选 FloatingIconView 动画素材 |
| [SystemIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java>) | SystemUI **主进程**/包，一次安装 | 通知图标、原生应用 splash |
| [NavbarModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarModule.java>) | SystemUI 或 Pixel Launcher **主进程**/包；非 server | 两个精确导航 owner 后端，不存在普通应用或 system_server 导航 Hook |

### 独立开关不等于零宿主依赖

- shade、状态栏位置/缩放、signal 可由 Application 创建回调连接，不需要小窗后端先连接成功。导航有自己的 Application 回调、配置线程和 Provider。
- 弹幕、快捷建议、边缘防误触 UI 由 [SystemUiHost.initialize](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>) 创建。它们不受小窗总开关控制，但**仍依赖共享 SystemUiHost 能启动**。其前台包、IME、系统面板/截图环境来自 [updateOverlayPriority](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)；这是共享环境观察，不是各功能自行 Hook 所有窗口。
- `BackBridge.connect` 在主线程启动宿主并以 3 秒重试；独立安装成功、宿主连接成功、配置可读、真正命中是不同状态。[连接实现](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)
- [HostSnapshot](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>) 是多功能宿主最后完整配置的复用点；不是统一业务规则引擎。导航有意不使用其持久旧值：配置读失败发布关闭、5 秒重试，避免失联时残留沉浸。
- `PROTECTIVE` 是 libxposed 回调异常模式，不等于业务状态自动回滚、已修改字段自动恢复或全部 Hook 可动态卸载。下面按真实局部实现注明差异。

## 3. 小白条沉浸：两种真实导航源

源码：[NavbarHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarHost.java>)、[NavbarTaskbarHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarTaskbarHost.java>)、[NavbarProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarProvider.java>)。默认关闭，仅默认显示器手势导航。仅将 `navigationBars` provider 的 `setInsetsSize(Insets.NONE)`，不改 IME overrides、mandatory/system gestures、tappable provider、窗口大小/触摸区域，不接管 pill 明暗。

| 后端 | Hook/关键 ABI | 原生执行与恢复 | 安装失败 |
|---|---|---|---|
| SystemUI | `navigationbar.views.NavigationBar.getBarLayoutParamsForRotation(int): WindowManager.LayoutParams` after；`BarTransitions$BarBackgroundDrawable.draw(Canvas): void`；`Instrumentation.callApplicationOnCreate(Application)` after；字段 `mContext/mNavBarMode/mNavigationBarTransitions`，父类 `BarTransitions.mBarBackground` | `mNavBarMode==2`，main；配置边沿 `repositionNavigationBar(display.rotation)` 原生重生当前及四旋转参数；只对准确所属背景临时空裁剪 Canvas，仍执行 native draw/动画，`finally` 恢复 clip；关闭 invalidate | 精确预检，`repositionNavigationBar(int)` 与 `onInit()` 每个 deopt 返回 false 都失败；撤销已装 handles，框架日志保留异常 |
| current Pixel Launcher | `taskbar.TaskbarInsetsController.setProviderInsets(InsetsFrameProvider,int,int): void` after；`TaskbarBackgroundRenderer.draw(Canvas)` 和同一 `BarBackgroundDrawable.draw(Canvas)`；Application 回调；`context: TaskbarActivityContext`、其自身声明的 `mIsDestroyed/mDragLayer/mControllers/mWindowManager/mWindowLayoutParams`；`TaskbarDragLayer.mBackgroundRenderer`、controllers→navbarButtonsViewController→mTaskbarTransitions→父类背景 | `isGestureNav()`、default display、未销毁、main；配置边沿调用 `onTaskbarOrBubblebarWindowHeightOrInsetsChanged()` 重算主参数/四旋转，再仅在 layer attached 时向**该 owner WM/root/原生 params** 显式 `updateViewLayout`；处理原生 `copyFrom` 浅引用 provider 比较吞更新问题，不写 native last-cache；弱引用 owner，销毁跳过；背景同样 finally 空裁剪 | 精确三参数 setter；重算方法与 `TaskbarDragLayer.dispatchDraw(Canvas)` deopt 任一 false 即失败；撤销本后端已装 handles。**不适配 QPR2 Launcher 五参数 setter**，其失败不改变 SystemUI 后端 |

配置：两个后端各专用 HandlerThread 注册 ContentObserver、异步 Provider 读取、main 发布/重排；失败转关闭，5 秒重试；重排失败保留重试。Provider 的 `system` 只向管理器自身、实际 SystemUI UID、实际 Pixel Launcher UID返回唯一 `enabled`；无远程写或普通 app 权限。

证据：[verify_navbar_target.py](<../../tests/verify_navbar_target.py>) 重新读 hash 固定的 current/QPR2 framework+SystemUI，以及 current Launcher；查声明成员、native getter/caller、rotation 重算/notify/背景 draw。current Launcher hash `3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56` 先前已与设备对齐；current SystemUI 安装成功但 `bars=0` 的历史实证表明实际源可以在 Launcher，不能以 SystemUI 类存在推定 owner。

局部回归：[NavbarImmersiveTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/navbar/NavbarImmersiveTest.java>)、[NavbarTaskbarHostTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/navbar/NavbarTaskbarHostTest.java>)、[NavbarModuleTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/navbar/NavbarModuleTest.java>)、[NavbarProviderTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/navbar/NavbarProviderTest.java>)。含 SDK provider 保持/恢复、共享缓存 fixture、主+四旋转、精准 owner/UID、非 gesture/display/销毁/线程边界；fixture 并非真实 Launcher 执行。

## 4. 图标适配与三路莫奈

### 4.1 Launcher 素材、缓存与动画

[IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>) 的基础 Hook：

- `com.android.launcher3.icons.IconProvider.getIcon(PackageItemInfo,ApplicationInfo,int): Drawable` after；`LauncherActivityInfo.getIcon(int)` / `getBadgedIcon(int)` after。当前用户才替换，缺素材走原生。
- `Application.attach(Context)` after 连接配置、素材 worker。基础安装失败撤销该列表 handles。
- 可选 `FloatingIconView.setIcon(Drawable,Drawable,Supplier,int,boolean)`：从 `mIconLoadResult.itemInfo/isThemed` 识别当前用户目标，native-themed 不强替换；通过原生 setIcon 接收独立 adaptive 背景/前景，检查 `mClipIconView.mBackground/mForeground`；可选入口失败不停止基础素材路径。
- [原生刷新](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>) 不是新 Hook：`LauncherAppState.INSTANCE.get(Context)`→`iconCache/model`，在 `BaseIconCache.workerHandler` 调 `removeIconsForPkg(UserHandle,String)`、model callbacks `onPackageChanged(String,UserHandle)`；main 清 `FloatingIconView.sIconLoadResult`。失败报告“请重启桌面”，不能宣称一定热恢复。

[IconAssets](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java>) 是各宿主独立静态实例的只读渲染快照，专用线程读取/解码/发布，热路径不做 Binder 或磁盘；素材发布、壁纸/配置、包、解锁变化触发失效/重读。`started(pkg)` 仅队列化 opt-in 算法素材生成，真正 Provider 生成在后台；它由小窗服务端已有 resume 观察调用，并不意味着对普通 app 安装 Hook。批量导入/生成的磁盘发布锁是 `IconPolicy.class`，不是 WM 锁。

### 4.2 抽屉莫奈：仅原生绑定作用域

[DrawerMonet.install](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/DrawerMonet.java>) 精确六处 Hook：

| 入口 | 读/改与原生语义 |
|---|---|
| `BaseAllAppsAdapter.getIconOnCreateSetup(ViewGroup)` after | 记住实际 parent，限定普通 AllApps 网格/预测，不扩到 SearchRecyclerView/桌面 |
| `BubbleTextView.setStandardIcon(ItemInfoWithIcon)` | 仅 eligible grid 的 ThreadLocal binding，保留 finally 恢复作用域；已缓存 AppInfo 缺 themedBitmap 时请求后台升级一次 |
| `IconRequestInfo(ItemInfoWithIcon,LauncherActivityInfo,byte[],boolean,CacheLookupFlag)`、`IconCache.getTitleAndIcon(ItemInfoWithIcon,CacheLookupFlag,Supplier)` | enabled 时仅 AppInfo 查询通过 `CacheLookupFlag.updateMask(8,true)` 补主题缓存位，不全局开主题 |
| `ThemeManager.verifyIconState()` after | 读 `iconState.themeController` 是否非空，上报 systemThemed；构造期间不触碰 Dagger singleton |
| `BitmapInfo.newIcon(Context,int,IconShape)` | 仅 binding 范围追加原生 flag bit1；保留共享 BitmapInfo、主题 delegate/角标/素材缺失回退，不写共享 bitmap |

`ItemInfoWithIcon.newIcon` deopt 精确接受 `(Context,int)` 或 `(int,Context)` 两形状；另外 deopt `BubbleTextView.applyIconAndLabel(ItemInfoWithIcon)`、`applyFromWorkspaceItem(WorkspaceItemInfo)`。**当前这三处 deopt 返回值未检查**，不是导航那种 false 即拒装，属于现有保护差异。

独立 drawer 用户开关 AND 系统 themed state 决定有效状态；变更 main 重绑可见图标，worker 读 Provider、上报 Launcher 状态/素材变更。upgrade 使用原生 `IconCache.updateIconInBackground(Executor,ItemInfoUpdateReceiver,ItemInfoWithIcon,CacheLookupFlag)`，回调 main。安装 Hook 发生异常撤销 drawer 自己已装 handles；运行期个别 upgrade 失败记录后保留原生。

### 4.3 SystemUI 通知与原生应用 splash

[SystemIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java>)：`Application.attach(Context)` 连接失败直接停止该入口；通知与 splash 安装各自 `try/catch`，并无统一动态卸载管理器。

- `StatusBarIconView.getIcon(StatusBarIcon)`：读 `mNotification/mShowsConversation/mOriginalStatusBarIconSize`；仅当前用户非 conversation 的 app 标识图标替换，以原生参考 canvas 尺寸归一；素材缺失走 native。素材刷新经 main 调 `updateDrawable(boolean)`，弱引用跟踪 view。
- `SplashscreenContentDrawer$SplashViewBuilder.build(): SplashScreenView`：读 `mActivityInfo/mSuggestType/this$0`、outer `mTmpAttrs`。跳过 type3/4、跨用户/缺 owner。只在本次 native build 临时替换 `mSplashScreenIcon/mIconBgColor`，finally 原样恢复临时 attrs；图标获取只读宿主快照，不在 build 内加载包/解码/Binder。
- 小窗 TaskCover 与 AppCatalog 的图标调用属于小窗展示，不另加系统图标 Hook；共用 [IconAssets](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java>) 的 `cover/window` 策略。三个莫奈开关独立，均受 Launcher 上报的系统 themed 开关门控。模块 mono 优先、原生 monochrome 次之，缺两者时后台转换真实前景/完整图标；128px/32MiB 生成缓存有界，照片保留明暗；素材/direct-native 不因此降分辨率。这不是语义抠图或任意 app 主题注入。

权限/共享边界：[IconLayerProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerProvider.java>) 对自身/同用户 Launcher/同用户 SystemUI/system UID授权完整图标快照及只读素材；`started` 仅 system UID；`report` 仅 Launcher；文件只能读已发布且被引用的路径。**“Launcher 仅得单布尔”专指 ShadeProvider 的 drawer 接口，不是 IconLayerProvider 全部接口**。[ShadeProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeProvider.java>) 的 `drawer` 仅自身/真实 Launcher UID，且只回 drawerMonet；普通 read 不给 Launcher。

证据：[verify_icon_target.py](<../../tests/verify_icon_target.py>)（current SystemUI 图标/splash/scale，部分来自库存，scale 再读 hash 固定归档）、[verify_drawer_monet.py](<../../tests/verify_drawer_monet.py>)（QPR2 Launcher 原生绑定/主题位/素材调用链）、[verify_monet_targets.py](<../../tests/verify_monet_targets.py>)（QPR2 themed lookup/theme state，不能自动推出 current 全链）、[verify_test4_target.py](<../../tests/verify_test4_target.py>)（两构建 FloatingIconView 形状）。局部测试示例：[DrawerMonetTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/iconlayers/DrawerMonetTest.java>)、[IconAssetsTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/iconlayers/IconAssetsTest.java>)、[MonetSurfaceTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/iconlayers/MonetSurfaceTest.java>)、[IconImagesTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/iconlayers/IconImagesTest.java>)。

## 5. 横屏双栏、状态栏位置/图标大小、移动信号显示

### 5.1 横屏双栏

[LandscapeShade](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/LandscapeShade.java>) 默认 off；条件为横屏且 `screenWidthDp>=600`，不改 DPI、不强开 Scene。

- legacy：Hook `SplitShadeStateControllerImpl.shouldUseSplitNotificationShade(Resources): boolean` after，仅满足条件时将结果 true；Hook `NotificationPanelViewController.updateResources():void` after 和其全部构造，记住弱 owner，配置边沿 main 调原生 update。核实 panel 的 `mSplitShadeStateController/mResources`；若可读 `SceneContainerFlag.isEnabled()` 且 true 就拒装，缺 getter 仅接受已核实 legacy 形状，不写 flag。
- ART：`updateResources` 必须 deopt 成功；[CALLERS/CONSTRUCTORS 清单](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/LandscapeShade.java>) 是完整现有 caller 选择。QS 和 NotificationsQSContainer 的 `updateResources()` 必须成功；后九个 media/lockscreen/stack/interactor/binder/remote-input caller 是逐项可选；构造 deopt 仅记录跳过。不是遍历所有 SystemUI 方法。
- [ComposeShadeLayout](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ComposeShadeLayout.java>)：按字段 `legacyUseSplitShade` 或 `legacyUseSplitShade$delegate` 精确选择 legacy map emitter 或 Hydrated `ShadeConfigRepository$onActivated$2$1$1.emit(Object,Continuation)`；Hook emitter 和 interactor（legacy）/repository（Hydrated）构造设置 ThreadLocal；`Resources.getBoolean(int)` 仅在该作用域改宿主资源 `config_use_split_notification_shade` / `config_isFullWidthShade`，其余资源返回 native。保存 `ConfigurationRepositoryImpl$onConfigurationChange$1$callback$1`，main 仅调用其 `onConfigChanged(Configuration)` 让原生流刷新。不写 readonly StateFlow/MutableState，不改磁贴列数。
- 缺 legacy 方法进入精确 Hydrated 路径；Hydrated 仍缺成员则整体拒装。必需 deopt/Hook 失败撤销已装 handles、关闭；运行期 Compose/panel 刷新失败先关闭并尝试恢复原生，日志可能要求手动重启 SystemUI，不能说任何部分失败都无残留。
- 配置 worker 读 HostSnapshot；主线程发起刷新。CHANGED 广播核验实际模块 UID。独立 Provider 仅自身/SystemUI full read。

证据：[verify_target.py](<../../tests/verify_target.py>) 的 legacy 库存、[verify_test4_target.py](<../../tests/verify_test4_target.py>) 的两构建 repository/emitter 字段与签名；不代表任意 Scene 版本。局部规则/资源作用域测试：[ShadeTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/shade/ShadeTest.java>)。

### 5.2 状态栏内容位置与图标比例

[StatusBarOffset](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StatusBarOffset.java>) Hook `PhoneStatusBarView` 全构造、`updateStatusBarHeight()` after；可选 KeyguardStatusBarView 全构造（只把 class missing 当 optional）。main 修改可见子内容 translationY，-32~+32dp，不改状态栏高度、刘海或 DPI。只在布局线程维护弱 View，配置 executor→main、同 Shade snapshot，信任模块 UID广播；基础安装异常撤销本列表。

[StatusIconScale](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StatusIconScale.java>) 可选 Hook `StatusBarIconView.onDraw(Canvas)`，读 `mNotification/mBundleEntry/mSlot/mIconScale`；仅通知及固定 system slot allowlist，不处理 BundleEntry/其他图标。80–120% 乘在 native scale 上，受当前可视范围限制；finally 恢复 `mIconScale`，100%原生无改。关闭/改值 main invalidate。安装前解析字段，缺失只跳过 scale；非完整独立 Hook 管理器。

证据：[verify_icon_target.py](<../../tests/verify_icon_target.py>)；[StatusIconScaleTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/shade/StatusIconScaleTest.java>)、[StatusIconScaleStoreTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/shade/StatusIconScaleStoreTest.java>)。不宣称 Compose 图标全部经过此 View draw。

### 5.3 信号显示：只改 presentation

[SignalDisplay](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplay.java>) 的普通 drawable 路径：

- 精确 Hook `MobileIconBinder$bind$1$2$1$2$2.emit(Object,Continuation)`，字段 `$r8$classId/$mobileDrawable/$viewModel` 和 `getSubscriptionId()`，仅 classId0、Pair.second 为 Cellular、非 carrierNetworkChange 时 ThreadLocal 绑定**这一个** drawable。
- Hook `Drawable.setLevel(int)`，只在上述绑定且同一 drawable 时按订阅调整 native state；不是全局所有图形级别替换。deopt binder emit，false拒装。
- 两套 repository callback（普通与 Kairos）都必须找到，Hook `onSignalStrengthsChanged(SignalStrength)`、`onServiceStateChanged(ServiceState)`、`onCarrierRoamingNtnModeChanged(boolean)` after，按 repo/subId 存观察值；`SignalStrength.getPrimary():CellSignalStrength` 和 `ServiceState.isEmergencyOnly():boolean` 为运行时精确反射。无服务、非 NR/NTN/变网等不误用旧强度；规则在 [SignalDisplayPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplayPolicy.java>)。不写 modem/Telephony 对象或 native flow。
- RADIOS/PAINTS 有各自 synchronized，最多32个弱 drawable；重画派发 main。配置 executor→main，默认关，配置变化恢复记录 nativeState。

独立可选堆叠路径：[StackedSignalTargets](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalTargets.java>) 精确选择静态 void `StackedMobileIcon-*` 两参数形状：`(DualSim,long,String,Modifier,Composer,int)` 或 `(DualSim,long,Modifier,Composer,int)`；唯一性校验，不猜哈希后缀。父入口必须 `StackedMobileIcon(StackedMobileIconViewModel*,Modifier,Composer,int)`；restart lambda 必须捕获 DualSim 且 `invoke(Object,Object)`。legacy `DualSimKt.tryParseDualSim(List)` 可选，QPR2直接构造复制。Hook render 只传 [StackedSignalDisplay](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalDisplay.java>) 的 presentation copy，改相应 Compose changed 位；模块自有 ParcelableSnapshotMutableIntState 仅作重组 revision，不写原生 state。parent/restart deopt 任一 false撤销堆叠自己的 handles，普通 drawable 路径保留；普通路径必需 preflight失败则撤销整个普通列表。

证据：[verify_signal_target.py](<../../tests/verify_signal_target.py>) current SystemUI binder/repo，脚本明确不验证 framework getPrimary；[verify_stacked_signal.py](<../../tests/verify_stacked_signal.py>) 两构建 render/copy 假设；[SignalDisplayTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/shade/SignalDisplayTest.java>)、[StackedSignalTargetsTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/shade/StackedSignalTargetsTest.java>)。需要真实信号/订阅与当前 UI 路径独立验证，不能把“存在堆叠 composable”当“该机正在使用”。

## 6. 电话拦截：原生 Telecom 决策之后追加拒绝

[CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>) 独立 system_server 入口。预解析所有成员后才装：`CallsManager` 全构造 after 取 context；`CallsManager.onCallFilteringComplete(Call,CallFilteringResult,boolean):void` before/after。先读原结果 `shouldAllowCall:boolean`，**只有 native allow** 才判断；原 native block/ignore、screening 原因/response不覆盖。

关键读取：Call 的 `isIncoming/isSelfManaged/isEmergencyCall/isInECBM/hasProperty(int)/getState/getAssociatedUser/getTargetPhoneAccount/getHandle/getHandlePresentation`；registrar 的 `getPhoneAccountUnchecked(PhoneAccountHandle)`、`getSubscriptionIdForPhoneAccount(PhoneAccountHandle):int`。按真实 PhoneAccount→active subscription→slot0/1，不轮询响铃猜 SIM；排除 emergency/ECBM/emergency SMS/self-managed/其他用户/非SIM、无法确定的状态。规则与重复来电窗口在 [CallBlockingRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingRules.java>)。

改写：`CallFilteringResult.Builder.from(result)` 克隆，设 allow=false、reject=true、log=true、notification/audio/silence=false，blockReason=1、模块名、清 screening component/response，build 后仍调用原生 onCallFilteringComplete。不接管默认电话角色、不直接挂断任意通话。

锁/线程：Hook 在原 Telecom 调用线程执行，仅内存 snapshot/联系人集合参与规则，不在此同步查询 Provider/联系人；记录 call 去重集合有同步。真实 PhoneAccount/订阅查询仍在判定路径（不能称无 Binder 操作）。worker 注册配置/联系人 observer，60秒 poll保持最新；联系人集合65秒租约且复核模块 READ_CONTACTS权限，缺/过期只放开陌生号码规则。clearCallingIdentity 都 finally 恢复。历史写入 worker，反射/判定出错放过原生；全安装异常撤销本功能 handles。

[CallBlockingProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingProvider.java>) 仅自身与 UID1000；contacts/backend仅1000；联系人由持有 READ_CONTACTS 的模块进程查询，最多4000，空/超大/失败不拦陌生人。read/record接口授权范围与 backend 不同，不是任意系统包名放行。

证据：[verify_callblocking_enhancements.py](<../../tests/verify_callblocking_enhancements.py>) 是规则 JVM/编译及源码集成不变量，不是 Telecom DEX 全 ABI或真机来电检查；[CallBlockingRulesTest](<../../app/src/test/java/dev/local/sunwindowdemo/CallBlockingRulesTest.java>)。当前入口运行时 preflight才最终决定该构建能否安装，不能由 Android17 注释推出所有 Telecom 发行版支持。

## 7. 弹幕通知：观察已投递消息，不抢通知投递

唯一通知观察 Hook 共享 [BackBridge.installSuggestionNotifications](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)：

- `com.android.systemui.statusbar.NotificationListener.onNotificationPosted(StatusBarNotification,RankingMap):void` after；`onNotificationRemoved(StatusBarNotification,RankingMap,int):void` after。
- 核验其为 NotificationListenerService；缺入口撤销本 pair、原通知完全保留。连接宿主后转给弹幕/快捷建议，不 Hook 普通应用通知发送、不做投递前 NMS 拦截。

[BarrageHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageHost.kt>) 在主线程管理窗口/队列/播放，worker 读配置和存历史。当前用户、非 summary/ongoing/secret、非 call/alarm/transport，目标前台 app与独立总开关、解锁/交互环境才显示。有限轨道满时排队（pending≤32，recent≤128），无限仅复用轨道；**显示成功且 Provider存历史成功后**才取消对应 key/postTime 的原通知版本，失败/不确定路径保留原通知。IME、其他系统面板、菜单/等待暂停；ScreenshotUI特例不隐藏/重建正在播放弹幕。

[BarrageProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageProvider.java>) read/history仅自身/SystemUI实际UID，addHistory仅SystemUI实际UID且extras必需；历史有 Store校验，不提供任意配置远程写。配置依赖 HostSnapshot，不代表 UI宿主与小窗完全解耦。

证据：[BarrageHostTest](<../../app/src/test/java/dev/local/sunwindowdemo/BarrageHostTest.kt>)、[BarrageScreenshotTest](<../../app/src/test/java/dev/local/sunwindowdemo/BarrageScreenshotTest.kt>)。本页未找到专用归档脚本覆盖 NotificationListener两个回调的完整安装链；运行期精确预检仍必需。

## 8. 边缘防误触：系统输入过滤 + 宿主租约/透明窗回退

[EdgeInputFilter](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputFilter.java>) 的 system_server 三个 Hook：`InputManagerService.start():void` after建 host；`setInputFilter(IInputFilter):void` after同步；`filterInputEvent(InputEvent,int):boolean` 检查/筛选。预解析 `mNative/mInputFilterLock/mInputFilter/mContext`，native `setInputFilterEnabled(boolean)`、`injectInputEvent(InputEvent,boolean,int,int,int,int):int`，`MotionEvent.split(int)`、InputEvent.getDisplayId。

[EdgeInputPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputPolicy.java>) 按每根手指 DOWN决定整条流，支持 mixed pointer；全部保留走 native，全部拒绝回收event返回false；部分保留split。`mInputFilterLock` 下若已有 accessibility filter，交原生处理新event；否则用原有policy flags注入 native filtered事件，并正确回收。不能替换/清空无障碍 filter。注入异常/非零结果永久禁用该 host本功能、清区域/流，排main同步，不重复破坏系统输入。

[EdgeTouchHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchHost.java>) main建默认display WindowContext，核验 `LayoutParams.setTrustedOverlay()`；配置worker→main。区域几何由 [EdgeTouchGeometry](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchGeometry.java>) 构建，扣安全区、IME/系统overlay、保护控件和旋转握持角；仅SystemUI实际UID发布有效范围广播，server验证数组界限/形状，3秒租约（2900ms expiry校验），熄屏清理。server backend ACK 仅UID1000；宿主失联回退透明触摸窗口，不是保证功能不受系统优先级影响。SystemUI InputMonitor也能见已消费触摸，因此还依赖第10节的可选 admission guard来避免原生Back误认领。

权限：[EdgeTouchProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchProvider.java>) 仅自身/SystemUI实际UID只读。安装失败撤销server handles，UI仍可独立尝试fallback；宿主初始化失败移窗/不可用。

证据：[verify_test4_target.py](<../../tests/verify_test4_target.py>) 两归档真实输入成员；[EdgeInputFilterTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputFilterTest.java>)、[EdgeInputPolicyTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputPolicyTest.java>)。输入锁、事件ownership/回收、已有过滤器链、租约丢失必须真机验证，JVM不能证明 InputDispatcher注入行为。

## 9. 加速度传感器控制：UID政策，不 Hook 应用传感器

[MotionAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionAccess.java>) Hook `ActivityRecord.setState(ActivityRecord.State,String):void` after；只有 RESUMED 且 `isFocusedActivityOnDisplay():boolean` 时读取 `getUid/getPid():int`与时间，**只投递 worker**，同 UID 页面切换不刷新首屏期限。此 optional安装在小窗 FrameworkAccess 大块之前，失败不停止小窗。

worker等待 `sys.boot_completed`，取 ActivityThread系统context；专用 [SensorCommand](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/SensorCommand.java>) 通过 `ServiceManager.getService("sensorservice")`→`IBinder.shellCommand(FileDescriptor,FileDescriptor,FileDescriptor,String[],ShellCallback,ResultReceiver):void`，不是启动shell进程。先只读get-uid-state探测，再按规则 `set-uid-state package idle --user 0` / `reset-uid-state package --user 0`，1500ms结果等待只在后台；clear identity finally恢复。**当前命令用户固定0，不据此声称多用户独立支持；SensorService“UID idle”能力的实际传感器覆盖由原生实现决定，不是逐个加速度事件过滤。**

[MotionPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionPolicy.java>) 是可复用规则层；共享UID最强规则优先，排除system/persistent与模块/SystemUI/Launcher；重核package↔UID防复用，跟踪可能已应用但超时的命令，期限到期/关闭恢复，失败5秒重试。配置worker+HostSnapshot，若无可用完整配置则释放限制。后台commands/Provider均不进WM锁。[MotionProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionProvider.java>) read仅自身/1000，backend仅1000。

证据：[verify_target.py](<../../tests/verify_target.py>) 包含foreground与shellCommand库存签名，**不验证 SensorService命令实现/返回码在任意发行版相同**；[MotionSettingsTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/motion/MotionSettingsTest.java>)。

## 10. 快捷建议、旋转按钮与公共输入/Launcher桥接

### 10.1 快捷建议主要使用平台回调

[SuggestionHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/SuggestionHost.java>) 默认display overlay WindowContext、main管理可见窗与超时；worker读独立快照，关闭恢复旋转/释放wake lock。核心输入不是新的global Hook：ClipboardManager监听；CameraManager torch callback；AudioManager audio device callback；[SuggestionSmsReceiver](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionSmsReceiver.java>) 接收标准SMS_RECEIVED（需用户RECEIVE_SMS），只把命中OTP的≤4096字符内容以共享身份定向SystemUI，不拦截原短信存储、不日志记录正文；host接收验证对应发送UID。OTP/链接/地址识别在 [SuggestionRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionRules.java>)，音效独立 [SuggestionTones](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionTones.java>)。

通知OTP隐藏共享第7节的after通知观察，只对默认短信包/同用户、能匹配已显示内容时取消；不确定保留原通知。亮屏建议来自 [SunWindowModule.installUndimSuggestion](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)：Hook `PowerManagerService.userActivityNoUpdateLocked(PowerGroup,long,int,int,int):boolean`，先读默认PowerGroup的dim/wake状态，native result true后才post main；1.5秒时效、互动且未锁屏才向SystemUI发共享身份AWAKE。**Locked回调只读状态，不在Power锁内做Provider/窗体工作**。安装失效只禁该建议源。

[SuggestionProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionProvider.java>) 仅自身/SystemUI实际UID；read配置，rotation_status仅允许固定短字符串键，不是任意偏好写接口。局部测试：[SuggestionRulesTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/suggestions/SuggestionRulesTest.java>)、[SuggestionTonesTest](<../../app/src/test/java/dev/local/sunwindowdemo/features/suggestions/SuggestionTonesTest.kt>)。本页未发现涵盖完整PowerGroup建议源的专用归档预检，不能把运行时自检当外部构建验证。

### 10.2 旋转建议：两宿主都可能拥有原生按钮

[RotationSuggestion](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/RotationSuggestion.java>) 与 [LauncherRotationSuggestion](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/LauncherRotationSuggestion.java>) 都精确Hook `com.android.systemui.shared.rotation.RotationButtonController.setRotateSuggestionButtonState(boolean,boolean)`。类同名但分别位于不同APK/classloader，不能只装SystemUI就假定全部来源。

- SystemUI读 `mRotationButton.mKeyButtonView`、`mLastRotationSuggestion/mPendingRotationSuggestion/mUiHandler/mCancelPendingRotationProposal`。仅main、有原生click、宿主接受替代后才将native显示参数变false，保留show侧清理与撤销；点击确认仍比较原proposal再performClick。缺宿主/窗体/设置时不吞原生按钮。指定NavigationBar/RotationButtonController caller方法做best-effort deopt，返回值日志，不全体false拒装。
- Launcher读button接口 `getCurrentView()/hide()`；Hook同state与`onDestroy()`。SystemUI实际UID签名广播预授权+host Binder lease，proposal Binder校验SystemUIUID、channel与无多余payload；每proposal需ACK，1秒不应答恢复native，host death恢复。仅点击目标未变时performClick；不改自动旋转总开关。Launcher当前对controller的其他declared方法和三个嵌套caller做deopt，属于较宽的已有局部策略，不能描述成只deopt单一caller。
- 这两个安装函数catch只记录不可用，没有统一记录/撤销全部已装handles；Launcher某些后续成员失败可能留先装state Hook。它们靠运行门控/缺host走原生，**不等价原子安装/全生命周期动态卸载**。本轮只记录，不扩重构。

局部回归：[RotationSuggestionTest](<../../app/src/test/java/dev/local/sunwindowdemo/RotationSuggestionTest.java>)。没有以此证明所有ROM的RotationButton字段、native超时或Watcher形状一致。

### 10.3 BackBridge / LauncherGuard：其他功能复用的边界

小窗长返回/视觉交接的完整业务不在本页；下面列公共安装面，避免将它们遗漏为“只有设置页功能”。

| 安装块 | 精确主要 Hook/依赖 | 边界与失败策略 |
|---|---|---|
| BackPanel 长返回 | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)：`BackPanelController.onMotionEvent(MotionEvent)/onViewAttached()/onDestroy()`；`EdgeBackGestureHandler.dispatchToBackAnimation(MotionEvent)`；callback.cancelBack，allow/log/rejected booleans；native输入adapter `invoke(Object)` deopt必需 | 只延长已被native接受的back流、匹配owner/downTime，commit前将物理UP替为CANCEL；主线程/lease/generation门控。安装四handle块失败撤销该块，不撤销此前独立功能 |
| SystemUI InputMonitor admission | [安装实现](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)：DisplayBackGestureHandlerImpl.onInputEvent真实字段类型（接口时已核实lambda20）→`invoke(Object):Object`；owner.f$0、mDisplayBackGestureHandlers、cancelGesture(MotionEvent)、Unit.INSTANCE、InputMonitorCompat.mInputMonitor.pilferPointers() | 默认display；宿主决定corner stream，达到阈值才pilfer；对防误触流cancel，保留外层finishInputEvent。异常atomic active=false回native，不影响edge server filter |
| Launcher 角落stream guard | [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>)：`TouchInteractionHandler.onInputEvent(InputEvent):void`、`TouchInteractionService.onCreate()/onDestroy()` | GestureGate只吞明确已拥有的finger stream，其他来源/显示器原生；广播核验SystemUI实际UID、几何与lease；服务销毁清门控。三handle安装失败撤销本块 |
| 预览交接 | [BackVisual](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackVisual.java>)：`LauncherBackAnimationController.applyTransform(RectF,float)` after、`onCancelFinished()`；mBackTarget taskId/leash/mStartRect | 只新鲜匹配task与有效surface，Binder仅SystemUI可读/认领；认领短时隐藏该leash，恢复原生depth；可选失败局部撤销，不全局改返回动画 |
| 历史任务卡 | [RecentsFilter](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/RecentsFilter.java>)：`RecentsView.applyLoadPlan(List,int)`、GroupTask.getTasks/containsTask(int)、reloadIfNeeded/mAppliedTaskListChangeId | 仅来自UID1000且协议匹配的owned hiddenTaskIds，单任务组过滤；保留原生running-task stub，多任务组不整体删；main刷新，安装异常只禁recents |
| Shell finish与截图优先级 | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>) `Transitions.onFinish(IBinder,WindowContainerTransaction)`；读取known/mix/ActiveTransition start/finish/merged/info；[截图实现](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>) `ScreenshotController.handleScreenshot` 唯一三参数且首参ScreenshotData、`ScreenshotWindow.removeWindow()` | finish仅匹配正在settling owned task，合并在native最后transaction；截图只观察实际decor attach，向自有宿主报告优先级。截图Hook有自身rollback，仅在长返回块成功后尝试；非全局安全窗口层级修改 |
| 快捷录屏/截图执行 | [WindowTools](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowTools.java>)：Hook `QSFactoryImpl.createTile(String):QSTile` after取factory；调用 `QSTileImpl.setTileSpec(String)/click(Expandable)` 与native screenrecord tile；截图按需 `InputManager.injectInputEvent(InputEvent,int)` SYSRQ down/up | 原生执行，不自建录屏会话或截图服务；optional失败返回false，录屏factory未观察到时不可用；从SystemUI UI调用，无后台WM锁内操作 |

[GestureGate](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/GestureGate.java>)、原生输入admission与小窗owned-task规则是不同层次。发行版已有Freeform/厂商小窗必须识别**任务、会话和触摸流所有权**；不能为避免冲突全局禁Freeform、截断全部Launcher输入或修改其他来源任务。现有过滤/视觉交接也不是第三方小窗兼容保证。

证据：[verify_target.py](<../../tests/verify_target.py>) 的部分录屏/输入/back-finish库存；[verify_test4_target.py](<../../tests/verify_test4_target.py>) 的current/QPR2 adapter差异、字段与调用形状。部分机制是读字段推导路径、部分deopt仅best effort，仍需实际ART/callback/gesture证据。

## 11. 逻辑与系统执行分离：现状与后续审查单位

当前已经有可复用独立规则类：[CallBlockingRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingRules.java>)、[MotionPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionPolicy.java>)、[EdgeInputPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputPolicy.java>)、[EdgeTouchGeometry](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchGeometry.java>)、[SuggestionRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionRules.java>)、[SignalDisplayPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplayPolicy.java>)、[IconPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconPolicy.java>)、[StackedSignalTargets](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalTargets.java>)。不应为了分层再复制这些规则。

后续如获授权，合理审查单位是单功能的 **入口→精确Targets/预检→规则→owner局部执行→关闭/失败恢复→证据**，而不是新建一个猜ROM的global Hook总表：

- 逻辑层处理输入快照并产出决定，不持有SystemUI View/Telecom/WM对象、不做Provider/解码/阻塞command；规则测试无需真实ROM。
- Targets/入口层解析实际发行版+Android+组件ABI，验证声明类、参数/返回、唯一性/资源/直接caller；“可选”按局部能力，不是一处失败就停所有功能，也不能写缺方法就遍历找近似名。
- 执行层只操纵准确owned对象，遵守native线程/锁和native调用时序，保存/恢复临时字段必须finally；不把静态配置快照与mutable view/lease/流状态揉成一个全局状态。
- 当前 HostSnapshot、IconAssets 是共享infra；SystemUiHost/BackBridge/LauncherGuard则仍有跨功能生命周期依赖。此页仅列出这些耦合，**未实施新的适配架构、重写入口、health watchdog或全套动态卸载**。

## 12. 检查清单与证据缺口

| 检查入口 | 本页可支持的结论 | 不能据此支持 |
|---|---|---|
| [verify_runtime.py](<../../tests/verify_runtime.py>)、[runtime-changes.json](<../../tests/runtime-changes.json>) | 源码基线/项目约束与登记差异 | 对每个功能的完整真实调用覆盖 |
| [verify_target.py](<../../tests/verify_target.py>) | 已登记current库存部分私有ABI、录屏/输入/shade/foreground等 | 未列Telecom/PowerGroup全链、任意新ROM |
| [verify_test4_target.py](<../../tests/verify_test4_target.py>) | current/QPR2重点差异、DEX041逻辑headers、部分安装面 | 所有入口在这两台上都运行过 |
| [verify_navbar_target.py](<../../tests/verify_navbar_target.py>) | hash固定两SystemUI/current Launcher导航成员与部分caller | QPR2 Launcher导航、全部IME/转场 |
| [verify_icon_target.py](<../../tests/verify_icon_target.py>)、[verify_drawer_monet.py](<../../tests/verify_drawer_monet.py>)、[verify_monet_targets.py](<../../tests/verify_monet_targets.py>) | 各自声明构建图标ABI/绑定调用链/原生语义 | 把QPR2 Launcher证据套到任何Launcher更新 |
| [verify_signal_target.py](<../../tests/verify_signal_target.py>)、[verify_stacked_signal.py](<../../tests/verify_stacked_signal.py>) | 指定信号binder/Compose形状与copy策略 | modem数据、framework私有getPrimary或真机正在用堆叠路径 |
| [verify_callblocking_enhancements.py](<../../tests/verify_callblocking_enhancements.py>) | 纯规则/编译/源码不变量 | 归档Telecom ABI、实际双SIM/来电权限效果 |
| [verify_r8_host_names.py](<../../tests/verify_r8_host_names.py>)、[package_release.py](<../../tests/package_release.py>) | release DEX关键类/反射字符串、签名/范围/版本等包装约束 | 每个反射调用可达、native线程时序或功能可用 |

未覆盖证据应显式保留：Telecom完整目标构建的成员与调用链归档；PowerGroup亮屏源；NotificationListener与旋转安装链；SensorService具体命令实现与用户语义；Application连接先后/宿主进程重建；已安装Hook局部失败/关闭后的状态恢复；系统输入event回收与accessibility链；设备上真正owner/界面路径。不要用编译成功、脚本数量或用户单一反馈替代这些验证。
