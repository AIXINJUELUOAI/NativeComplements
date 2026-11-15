# 同源独立小窗：现状依赖与接入隔离审计

## 当前实施状态：0.10.0 / code139

**同源独立小窗版正在实施，不是可用/全部解耦/LOS 兼容公告。** 已 push：`3afdb01` 的纯 WindowPresentation + Android 执行分界；`58f39f9` 的真实 Freeform correlated STATUS gate（不自动改设置）与 single Properties owner 隔离；`bd3aafb` 的独立 [LauncherThemeReporter](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java>)。owner 门控用于避免重复接管，不等于任意 OEM 原生小窗均可安全共存。

### 未 commit、已落工作区的产品边界

- [构建配置](<../../app/build.gradle.kts>) 已有 `full` / `window` productFlavors，主包保留 `dev.nativecomplements.module`，window 为 `dev.nativecomplements.window`；同源同一 [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)，未复制状态机。
- C1 [WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>) / [AndroidWindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowAdmission.java>) 已分出接管规则与 native 读取。C2 controls / group / navigation / recovery / input 待后续，不把一条接管/呈现链当整个引擎解耦。
- 多功能导航已从 main 移到 [full ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)；[Page](<../../app/src/main/java/dev/local/sunwindowdemo/navigation/Page.kt>) 仍共享，[window ModuleApp](<../../app/src/window/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>) 为新窄入口。
- main 注册表已移到 [full java_init.list](<../../app/src/full/resources/META-INF/xposed/java_init.list>)（7 entry）；[window java_init.list](<../../app/src/window/resources/META-INF/xposed/java_init.list>) 为 4 entry：SunWindowModule、LauncherGuard、BackBridge，加 [LauncherThemeModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeModule.java>)。[scope.list](<../../app/src/main/resources/META-INF/xposed/scope.list>) 仍在 main，仍是原三系统作用域。
- [HostExtras](<../../app/src/main/java/dev/local/sunwindowdemo/HostExtras.java>) 与 [full ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>) / [window ProductFeatures](<../../app/src/window/java/dev/local/sunwindowdemo/ProductFeatures.java>) 是真实源集隔离非小窗实现，不是隐藏 UI 或仅靠常量等 R8 猜测删除。尚须完整 release R8/产物核验，不把源码集隔离写作已交付瘦身证明。
- window 的 [WindowIconProvider](<../../app/src/window/java/dev/local/sunwindowdemo/features/iconlayers/WindowIconProvider.java>) 只保留 native mono / Monet 窄桥接；主题状态由独立 reporter 链提供，不带 drawer、图标库或生成入口。它不承诺与 full 的导入素材/强转生成图标能力等价。

已 push 阶段的单测证据为 **345 pass**；当前工作区完整双单测 **full 353 / window 348 均 0 失败**，双 release/R8、原签名/身份/scope/入口/快捷方式及禁止非小窗 Hook 的 DEX 核验通过；仍为阶段产物，未做设备回归。没有新增设备可用性或 LOS 兼容结论。主题报告或 IPC 失效时可能保留旧主题状态与图标快照；沿用既有快照语义，不保证任意主题故障均立即关闭。后续 LOS 贡献先等我们完成 refactor，再由贡献者 fork / adapt / test PR，我们 review + 现设备回归后才决定 merge，详见 [当前总览](<README.md>)。

## 138 历史审计（以下正文）

审计基线：`930a22c`（versionCode 138 / 0.9.48-fixes-test2）。以下保留当时依赖、风险及 PR 建议，包括当时“独立版/flavor/门控尚未实现”的结论；不代表当前状态。源码链接已迁到现路径并移除不可靠行锚（full 导航迁移后行数减少 7 行），仅供定位参考，旧函数内容已有变化，不是旧 snapshot 的精确源码。历史原文的“当前/全部尚未实施/本轮”均指 138 审计，不覆盖上方实施进展。

## 1. 两个独立维度，不能混为一次“拆包”

1. **打包独立**：同源代码产出独立管理 APK、权限、Provider、配置和恢复记录；保留主模块覆盖升级兼容点。当前只有一个 applicationId，没有 productFlavor，见[构建配置](<../../app/build.gradle.kts>)。
2. **系统适配解耦**：输入事件进入、规则／状态决策、系统 Hook 执行／恢复分别梳理；同一小窗核心由已核实系统接入调用，不复制另一份 WindowEngine，再按 ROM 写分叉引擎。当前这些职责仍混合在入口和引擎中。

两维可先后推进；换包名既不能获得 OEM 接入支持，也不能证明与原版或系统内置小窗共存。以下均是现状，不是已完成的重构。

## 2. 先看真实端到端链

| 层次 | 当前实际依赖／行为 | 拆分前必须保留的语义 |
|---|---|---|
| 管理 UI | [WindowController](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowController.kt>)通过 Wire 请求状态、监听结果、启动图标目录；[WindowPreferenceStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/config/WindowPreferences.kt>)写 Config 并广播变更。导航仍在[多功能 ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)中。 | 独立页面不能直接搬全首页；恢复、诊断、快捷方式、权限反馈都需有入口，而不只显示小窗开关。 |
| 输入接入 | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)装 SystemUI 原生输入监听、角落接管、长侧滑返回协调；[LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>)防 Launcher 抢已拥有输入流，并装返回动画、最近任务过滤。 | 不能用新透明窗全面抢输入替代原生取消／pilfer时序；角落和长侧滑不是同一识别器。 |
| 规则／本地状态 | [CornerGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>)、[Rules](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Rules.java>)、[GestureGate](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/GestureGate.java>)负责几何／流归属；[SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)保配置、扇区、菜单、控件及目录。 | 识别阈值、可用性、当前输入流、任务会话是不同状态，不能只提取工具类就称逻辑／Hook 已分离。 |
| 控制协议 | [Wire](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Wire.java>)使用显式广播启动请求及 Binder 高频控制／帧，协议16、租约、token；[服务端控制入口](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)核验 UID、接口 token、操作白名单。 | 广播发送者、PendingIntent创建者／用户、Binder调用者、任务身份都要继续核验；不能以“同签名”或客户端包名字符串取代。 |
| 系统执行 | [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)安装 FrameworkAccess／WindowEngine、配置、边界、显示与输入 Hook；[WindowEngine 的 snapshot／validate](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)再取真实 task、配置和能力。 | 规则返回意图不等于执行成功；需保留系统预检、线程／WM锁和失败恢复结果。 |
| 持续呈现／恢复 | 引擎持有真实任务、Session、journal、租约死亡通知；[WindowContinuation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowContinuation.java>)对已拥有隐藏任务按需保活。 | 拆分不能丢失关闭还原、后台任务保护、安全暂停、退出回收；状态并不全在管理 APK。 |

小窗总开关是功能行为配置，**不是全套动态卸载或运行时隔离开关**。关闭设置不等于已安装的所有 Hook 都卸载。分组 Hook／依赖清单另见[小窗接入审计](<floating-window.md>)；其他系统功能另见[系统功能审计](<system-features.md>)（由同轮审计维护）。

## 3. 独立包需要隔离的身份与持久化面

| 面 | 当前真实值／位置 | 改 applicationId 后仍有的风险 |
|---|---|---|
| 应用／Java身份 | [构建配置](<../../app/build.gradle.kts>)：applicationId=`dev.nativecomplements.module`，namespace=`dev.local.sunwindowdemo`。 | namespace 与安装身份不同；不必为独立包复制 Java 包，但必须查所有安装身份常量。 |
| 模块入口与范围 | [java_init.list](<../../app/src/full/resources/META-INF/xposed/java_init.list>)含7入口，含非小窗模块；[scope.list](<../../app/src/main/resources/META-INF/xposed/scope.list>)仍为 system、SystemUI、Pixel Launcher。 | 仅保留3个宿主不是功能裁剪；也不能勾选普通应用弥补宿主接入缺口。 |
| Wire | [Wire](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Wire.java>)硬编码 PKG、COMMAND、RESULT、CONTROL、frames.v16；[BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)派生 backLong.v1；[BackVisual](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackVisual.java>)派生 BACK_VISUAL 与 Binder描述符。 | 新 APK 仍会呼叫原版动作／检查原版 UID；同协议号不代表正确对端。独立命名空间与版本协商须明确，不是任意把协议16改大。 |
| Launcher消息 | [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>)把 GUARD_STATE／QUERY_GUARD_STATE直接写死；[WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)派生 HISTORY。 | 两个后端都可能改同一个 Launcher 输入／recents 状态；需要拥有者，不只 action 改名。 |
| Manifest | [Manifest](<../../app/src/main/AndroidManifest.xml>)只有 diagnostics authority 使用 `${applicationId}`；settings、iconlayers等多项 authority、CONTROL权限、diagnostic/shortcut taskAffinity仍写死。 | authority 冲突可阻止并装；签名权限定义、任务归属、shortcuts、诊断识别也必须一致迁移；不能把所有Provider保留给独立版。 |
| 管理端配置 | [ConfigProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ConfigProvider.java>)authority派生自 Wire，读管理APK的 `corner`；只允许管理者/SystemUI实际UID，`targets`是窄写接口。 | 新包私有目录自然不同，但现有URI/身份不会自动变；不可为方便改为任意进程读写。 |
| SystemUI影子配置 | [SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)使用宿主自己的 `nativecomplements_corner`；[ConfigProvider.copy](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ConfigProvider.java>)完整快照 clear+commit。 | 同一 SystemUI 的同名 prefs 会被另一产品覆盖；不能认为管理APK配置独立就足够。 |
| 系统恢复／方向／历史 | [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)：`/data/system/sunwindow-demo5.json`、`/data/system/nativecomplements-window-orientation.json`；[historyFile](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)：`/data/system/sunwindow-hidden-tasks.json`。 | 两引擎会读写对方的恢复记录和最近任务；仅更换管理包无法隔离。需要显式归属／版本与旧记录迁移决策，不删旧数据“修复”。 |
| 宿主快照／图标盘缓存 | [HostSnapshot.directory](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>)：system_server用 `/data/system/nativecomplements`，其他宿主用各自DP files下 `nativecomplements`，按feature文件共用；[IconAssets.imageBytes](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java>)再用 `icon_assets`。 | 相同宿主中多产品共享路径，旧配置/素材可能跨产品回退；authority进入素材摘要虽能区分部分内容，但快照路径和feature名仍须归属隔离。 |
| 诊断 | [Diagnostics.URI](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/Diagnostics.java>)已基于 BuildConfig.APPLICATION_ID。 | 此处已可随安装身份变化，但调用源、宿主缓存和恢复日志不能因此都假定独立。 |

**并装、同时启用是不同承诺。**隔离 authority 可解决安装冲突，却不能解决两个 libxposed模块同时对同一 task、input monitor、Surface或原生返回流执行。不同模块ClassLoader下静态字段通常各有一份，但它们操作的是同一系统对象和宿主磁盘。当前没有产品间单一后端租约／选主、冲突检测或互斥安装门控，不能承诺双版本同时启用安全。

## 4. 不是删掉非小窗入口就完成瘦身

- [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)在引擎之前装唤醒建议、边缘输入过滤与传感器控制；删除独立的 CallBlocking/Icon/Navbar入口仍会留下这些接入。
- [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)还装横屏双栏、状态栏偏移、信号显示、旋转建议、通知建议、WindowTools和通知小窗。通知打开小窗与建议/信号等不是同一需求，需逐项定保留边界。
- [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>)还装旋转建议。返回动画交接、手势保护、最近任务过滤则是现有小窗体验的一部分，不可整段删除。
- [SystemUiHost.initialize](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)还创建 SuggestionHost、EdgeTouchHost、WindowQuickHost、BarrageHost。快捷功能若要保留，需要确认[ShortcutTarget](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ShortcutTarget.java>)中应用包名与 quick action 混合列表的协议，不能把 `targets`一概当包名。

因此建议先有“依赖归属表＋安装边界测试”，再实施最少的产品安装入口选择；不要先建万能插件框架，也不要为了独立包复制一套引擎。

## 5. 莫奈与图标：必须独立决定保留到哪层

当前小窗莫奈并非只依赖 AppCatalog：

1. [IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>)安装 DrawerMonet，其[连接路径](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>)负责主题观察器启动。
2. [DrawerMonet](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/DrawerMonet.java>)读取 Launcher ThemeManager的themeController，在校验状态后向IconLayerProvider `report(systemThemed)`；不是读取随便一个Secure开关。
3. [IconLayerStore.read](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerStore.java>)把 `icon_layers.system_themed`和[ShadeStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeStore.java>)的两个surface开关合并。
4. [IconAssets](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java>)仅在系统主题与对应开关有效时后台缓存 native mono或强转图标，独立图标适配总开关；优先模块mono，再原生，再缓存。AppCatalog.windowIcon与TaskCover共用此源，SystemIconModule另接原生应用splash。

[IconLayerProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerProvider.java>)按owner／同用户Launcher／SystemUI／system UID分权：素材read不是任意应用可读，主题report仅Launcher，started仅system。独立接入不能放松此边界来绕过新包对接。

删 IconLayerModule 后，新的独立包不会自动继续上报主题状态；旧快照可能短时保留先前值，这不是独立版正常工作证据。独立版若保留小窗/遮罩莫奈，应先抽出最小“主题状态报告＋surface设置＋只读素材”接入，再决定是否保留全局桌面替换、抽屉、通知适配和原生应用 splash。也可以明确只保留原图，但需产品确认，不能静默丢功能。**本轮没有做任何拆出。**

## 6. Freeform、内置小窗与“双开”必须分别定义

### 6.1 当前能力不是任意 ROM 的 Freeform 解锁器

[validate](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)要求原生 `mSupportsMultiWindow` / `mSupportsFreeformWindowManagement`，且默认显示、主用户0、普通应用、root+leaf且直接挂TaskDisplayArea，以及合法可调整的windowing mode/bounds。没有全局强开系统Freeform或写DPI。对[已有自由窗的接管](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)只容许直接关联的继承窗口，并非把所有内置小窗都纳入管理。

系统内置Freeform的冲突应按**任务所有权和具体路径**调查：谁创建、谁持有恢复快照、谁可改bounds/Surface/input、何时交还系统。不能默认屏蔽整套原生Freeform，不能为“兼容”对所有未拥有任务关系统窗口能力。当前已有owned-task门控，但没有覆盖各OEM内置小窗实现的共存协议。独立版不自动解决这一点。

### 6.2 四类“双开”不要混称已支持

| 需求 | 当前结论 |
|---|---|
| 主模块与独立小窗APK并装 | 尚无独立产品与身份隔离实现。 |
| 两模块同时启用 | 尚无单一后端拥有者／互斥策略，不能承诺。 |
| 同包同时开两个独立应用实例 | [open](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)会优先复用同包现有Session；[AppCatalog](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AppCatalog.java>)当前用户按包去重，不提供任意同包多实例选择。关联任务/小程序窗口不等同此能力。 |
| OEM应用分身／工作资料／第二用户 | [validate](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)硬限user0；[shortcut identity](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)也核验UserHandle。记录userId和按用户保护任务不代表开放跨用户启动。 |

## 7. 138 原建议最小 PR 顺序（当时全部尚未实施）

每个PR只解决一个可验证边界，主模块默认行为必须保持；不以一次大搬迁混合功能增加。

1. **盘点／固定现状**：完成输入→规则状态→执行／恢复依赖与精确Hook审计；补各入口“何时注册/谁调用/失败如何降级/写何状态”矩阵。保留现有回归和已知失败，不改摘要掩盖差异。此页属于这一阶段，不是分层已完成。
2. **逻辑与执行分界的小提取**：先选一条真实链（如角落识别）把事件数据、规则决定、Hook执行/取消结果标清，复用现有CornerGesture/Rules，不复制Engine。独立编译的规则测试不能代替设备上取消/恢复测试。
3. **身份归属统一**：在同一身份源下逐项替换本页列出的authority/actions/permission/taskAffinity/宿主prefs/系统文件；保留原版既有值，独立身份另外分配。测试Manifest与各端Wire值一致，拒绝交叉UID/协议/租约；数据迁移不得自动覆盖原版恢复记录。
4. **单一后端与冲突决策**：先明确并装但互斥启用，或一个后端、多管理端的设计；覆盖两模块加载顺序、进程重启、租约死亡、旧journal存在时恢复。另对内置Freeform按未拥有/已拥有/交接三类验证；不是统一关闭原生功能。
5. **最小安装边界**：按第4节显式决定必需小窗、通知入口、快捷功能、图标主题报告和可选附加功能。验证“独立版不会安装被排除功能Hook”，而不是隐藏页面即宣称剥离。
6. **最后增加独立打包入口**：身份与互斥策略明确后才加flavor或等价产物选择，沿用同源核心；核验签名/authority唯一性/模块入口/三系统scope。独立版是否独立签名、是否读旧数据、是否与主模块共存都需明确批准，不推断现有签名授权。
7. **按系统接入推进兼容矩阵**：仅为已读归档/实际设备补精确接入，并分别验输入、可见性、焦点、安装确认、IME、旋转、原生小窗、退出恢复；没有证据的系统写未验证。最终才承诺可用范围。

审计不等于实现，构建成功不等于真机兼容；当前没有新增独立APK、Freeform强开、OEM双开、OpenAlive或其他新功能。
