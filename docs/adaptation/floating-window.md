# 小窗：Hook、系统适配边界与决策／执行现状

## code166：LOS 接入与 Hook 目标整理

保 code165 的角落、通知和跨 app 路径；[LOS24](<lineageos-24.0.md>)贡献只增加真实宿主接口接入，不将 PR 的旧 code146 功能停用带回。输入 callback、通知发送、ActivityStarter 不同签名由局部目标解析，调用者共用规则；桌面目标由[LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)统一为已知系统实际 HOME／UID。四个静态 scope 不意味着四条任意授权链。

通知发送的 native proceed 仅一次，解析前失败可保原生；进入派发接管后的异常不重新发送原 PendingIntent。保 user／display／前台／正文 PI／认证／租约及恢复条件，不恢复退役功能。正式检查见[索引](<README.md>)，不沿用下方历史通过数。

## 历史收口范围：0.11.5-alive-test3 / code151

用户明确要求与Alive唤醒修复同包恢复两项：**应用间跳转小窗打开**、**通知小窗打开**。[行为设置](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowScreens.kt>)恢复原独立开关，复用`landscapeShareWindow`/`notificationWindow`旧键、默认true并保留已存false；“landscape”是历史键名，跨应用路由不只限横屏。

- 前台全屏应用跨app仍通过[ShareWindow](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ShareWindow.java>)与[WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)原有caller/realCaller/UID、当前前台任务与归属、host新鲜租约、安全界面、安装器/系统/同app/桌面排除及准入/恢复。只调整真实启动options，不重建Intent、不删grant/result、不把规则混进Hook。
- 通知仅现有[NotificationWindow](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/NotificationWindow.java>)精确可选原生适配与[宿主](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)认证OPEN票据，转交原activity PendingIntent及草稿；Hook预检或dispatch前失败保原生，已派发后不再重发通知活动。前台/警报/呼叫等仍按原策略。
- 不恢复长侧滑、无障碍按钮、shortcut、快捷建议小窗、独立window产品及小窗/遮罩Monet；不改protocol16/三个scope/原签名与后端owner恢复边界，不安装、重启或替用户改配置。
- 既有通知、跨应用启动、导航/宿主规则与恢复开关UI定向检查共22项通过；Alive额外2项值状态检查最终重跑，未跑全套。小窗只读独立复核未发现可证P1/P2。release最终重建与签名核验见[总览](<README.md>)；不以主机构建代替当前真机通知/跨app效果验证。通知安全排除沿旧链，不能将跨应用路径的安装器排除扩称为通知所有真实PI目标均有专门系统/安装器排除；后端明确回报dispatched=true时不重发，同步广播异常沿旧fallback，不声称覆盖未知transport提交结果。

## 历史收口范围：0.11.3-alive-test2 / code147

code147按用户授权取消独立小窗产品，小窗继续由主模块提供；恢复的是**原生Hook左右下角滑动与扇区**，不使用无障碍区域或系统无障碍按钮。只定向接回角落输入、扇区展示、设置及取消清理，不整体回退code146，不恢复长侧滑、通知/分享/shortcut、快捷建议小窗或全屏跨应用自动小窗。小窗/遮罩Monet仍实际关闭并忽略旧值，抽屉Monet不变。本节是正在收口的实现契约，不预报构建、测试、提交或真机PASS；最终证据由[接入总览](<README.md>)统一记录。

### 产品合回，不回退其他ROM接入边界

取消独立APK不等于把规则重新揉进Hook，也不等于已经兼容任意ROM。第一轮已完成的导航、恢复、生命周期与连续性规则提取继续保留；下方code139/138内容按历史阅读，不能用历史“未拆分”覆盖现有分层。适配贡献者仍遵循 **平台事件输入 → 共用规则/状态 → 平台执行及恢复**，复用同一引擎，不另复制ROM分叉。

| 层次 | code147保留的责任 | 其他ROM接入时需核对 |
|---|---|---|
| 角落规则 | [CornerGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>)与[Rules](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Rules.java>)判断左右角、斜向阈值、单指流、取消及完成，不持有原生InputMonitor | 向同一规则输入真实显示器、坐标、设备/流身份、时间和区域快照，不按ROM名改变安全规则 |
| SystemUI原生适配 | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)接原生输入、精确预检与deopt、达到阈值后才pilfer；[SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)负责扇区/热区、配置、状态门控、保护矩形和回收 | 核输入回调/owner/monitor的实际类与字段类型、pilfer与finishInputEvent时机、宿主包及UID，核截图/安全界面/键盘/锁屏/旋转取消和Launcher冲突保护 |
| 准入与窗口状态 | [WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>)等值规则保留；[AndroidWindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowAdmission.java>)读取真实task事实并生成恢复快照 | 规则结果不是执行成功；系统实际任务层级、Freeform能力、可调整性、用户与显示器身份必须核实 |
| 系统执行与恢复 | [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)、[FrameworkAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>)与[WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)保留WM锁、Handler、Binder、Task/Surface/Insets及journal | 核Framework/SystemUI/Shell/Launcher确切版本、成员签名和调用顺序，保UID/protocol16/token/lease、安全窗口和失败恢复；不能只换类名或打开Freeform即称移植完成 |

### 失败边界与最小接入步骤

1. 先建立目标系统归档及成员/调用链差异，沿下方Hook清单定位原生适配，不把历史清单当当前目标ABI已通过；运行时仍精确预检，必需成员不符不安装该执行链，局部可选功能按现有局部回退，不强开系统能力。
2. 角落观察不等于夺取输入：默认显示器、真实安全状态与可用后端先成立，规则达到斜向阈值后才接管；异常保留原生输入完成路径，隐藏/禁用/截图/安全界面/配置变化要撤销自有扇区与热区，不留透明层抢触摸。
3. 复用现有规则检查、启动/Hook失败保护、UID边界及恢复检查，按实际改动最小定向验证，再由贡献者在目标系统手工核对角落输入、迷你/贴边恢复、IME、旋转、安全遮挡和退出恢复。不为每个功能新堆套件，也不以主机构建代替设备效果；fork/adapt/test后提交PR，由review与现设备回归决定合入。
4. 主模块原签名、applicationId、恢复记录与静态作用域保持。停止独立产物不等于卸载设备上的旧独立包；[所有权保护](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>)保留原key与first-claim，[资格投影](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendEligibility.java>)及[冲突回退](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowBackendConflict.java>)继续兼容旧包，不强抢、不热转交、不迁journal。用户应先在旧版恢复窗口，再手工停用/卸载旧独立模块，最后完整重启；模块不替用户操作LSPosed或设备设置。

## 历史实施状态：0.10.0 / code139

第一轮小窗纯规则/状态与原生执行边界已完成代码闭环，运行代码已 push 至 `a0f3a7b`；导航、恢复及窗口生命周期均已收口，集中证据见[最新进度](<README.md#最新进度恢复与生命周期闭环>)。原生 Hook/WM/事务/持久化保留适配侧，不是任意平台可替换或设备/LOS 兼容声明；下方 138 Hook 审计按历史阅读。

## 1. 138 历史审计范围与读法

以下保留 `930a22c` / 138 的历史审计（含当时“未增加抽象/未拆分”等结论），不是当前实现状态。源码链接是现路径参考；旧函数内容已有变化，不是旧 snapshot 的精确内容，已移除不可靠源码行锚。

本页为 `main` 提交 `930a22c`（versionCode 138，0.9.48-fixes-test2）的**现状审计**，不新增功能、不表示其他 Android 发行版已经兼容。入口清单见 [java_init.list] 与 [scope.list]：小窗在 system_server、SystemUI、Pixel Launcher 三处协作，不在普通应用内安装 Hook，不依赖无障碍。Java 包仍为 `dev.local.sunwindowdemo`。

- 下文 `wm.*` 指 `com.android.server.wm.*`，`Tx` 指 `android.view.SurfaceControl.Transaction`，`AR` 指 `wm.ActivityRecord`，`WC` 指 `wm.WindowContainer`；未写返回值的条目以链接源码的实际成员为准。
- **核心**：位于 [SunWindowModule] 外层安装链，签名／安装失败会卸载其 `handles` 并记录 `DISABLED signature-preflight`。不是“所有子功能统一原子安装”。
- **局部可选**：自己的 try/catch／回滚链；缺失后保留其他路径。可选只描述代码故障边界，不等于缺少后体验／安全行为完全等价。
- **调用依赖**不是 Hook。[FrameworkAccess] 的 `method/field/call` 是精确参数反射与缓存，并不等于这些成员全部被拦截。完整反射成员以该类构造器和各功能安装器为准；本页按行为链列 Hook inventory、关键调用／字段及降级，不把少数成员冒充全部 ABI。
- 本页作者仅做源码审计及文档链接／格式检查；归档校验执行结果由 Lead 汇总。本页不报告新的构建、安装或实机验证结果，历史用户反馈不替代跨版本验证。

## 2. “应该怎样”与“怎样做到”：目前没有完全解耦

[Geometry]、[Rules]、[WindowPolicy]、[GestureGate]、[CornerGesture]、[RotationGate]、[MiniMagnet] 等已有无 Android framework 依赖的几何／布尔规则／手势状态判断；[verify_runtime.py] 会把其中一组直接交给 JDK 编译并运行 host checks。这是可复用逻辑的真实边界。

但 [WindowEngine] 同时保存 native task／activity 对象、Session 状态、WMS 锁、Handler、SurfaceControl 事务、恢复日志和平台反射；有些决策依赖当前对象身份而非稳定快照。[WindowPolicy] 虽不引用 framework 类型，仍接收 `Object` 身份，并有微信客户端 mode=5、其他 mode=1 的现存兼容规则。**不能把 FrameworkAccess 称作已实现的完整平台接口，也不能声称换一个反射类即可移植。**

| 事件输入（现状） | 状态／规则决策（现状） | 平台执行（现状） |
| --- | --- | --- |
| SystemUI 命令、长侧滑／角落流、通知正文 PendingIntent、分享原生 start | Wire 校验、Session／关联返回链、WindowPolicy 与入口候选判断；大部分编排仍在 WindowEngine | 原生 ActivityOptions／Task 配置、move/focus、事务遮罩、结果回报 |
| Task.prepareSurfaces、配置变化、窗口 finishDrawing、Shell collect/ready/finish | placement、RotationGate、LaunchCoverFlow、cover 可揭开条件 | task scale/crop/radius/alpha、TaskVisibilityGuard、TaskCover 与装饰层 |
| 锁屏／熄屏、安全窗口原生集合、宿主 lease 死亡 | screenSuspended、prioritySuspended、恢复／停放状态 | hide task 和自有 controls、临时焦点更改、journal 恢复；不提高安全窗口层级 |
| Insets／客户端配置出站、WindowState 输入资格 | owned task/token/IWindow 身份映射、逻辑视口 | 深拷贝出站配置与 Insets、控制窗口触区；不改无关应用或 WMS 缓存 |
| 边侧隐藏、应用睡眠／内部清理请求 | 显式设置、可见性、UID、lease、五分钟宽限 | 定向 lifecycle 谓词、临时电源白名单／有期限 wakelock、延期原始强停重放 |

若后续拆分，合理方向是先为单条链固定“事件快照 → 状态转移／效果意图 → 原生执行与结果”及失败恢复契约，保留纯规则测试，再隔离 platform 适配；**本次没有增加任何抽象、接口或替代执行器**。不能只按 ROM 名堆 if：Android API／framework 与 SystemUI、Launcher 各自的版本、精确 ABI、ART 内联和实际能力需分别验证。

## 3. system_server 核心安装链

安装来源：[SunWindowModule] 的 `onSystemServerStarting`。仅 `isSystemServer()` 且首次尝试安装；先独立安装熄屏建议、边缘过滤、传感器入口，然后创建 FrameworkAccess／WindowEngine，并先安装清理宽限与 continuation。后两者及前述独立功能不都在核心 `handles` 中，核心回滚不是模块全量动态卸载。

### 3.1 主 Hook inventory

| 精确目标（或受约束匹配） | 读／改与用途 | 失败边界 |
| --- | --- | --- |
| `wm.WindowManagerService.systemReady()` | after 原生，attach WMS、取 root／ATM／global lock，启动宿主通信与恢复 | 核心；attach 内另捕获 `ATTACH_FAILED` |
| `wm.Task.prepareSurfaces()`；`onConfigurationChanged(Configuration)` | after 原生，重新应用已接管 Session 展示／配置 | 核心 |
| `wm.Task.computeFreeformBounds(Rect,Configuration)` | 仅 owned task 填逻辑 bounds 并跳过原生；其他任务 proceed | 核心 |
| `wm.RecentTasks.isVisibleRecentTask(Task)` | 仅需要隐藏的已管理历史任务返回 false | 核心；Launcher 另有卡片过滤 |
| `wm.InsetsPolicy.updateSystemBars(WindowState,int,int,boolean)` | 保留原生调用；符合 owned immersive 保护条件时将第四参改 false | 核心；不是全局小白条沉浸 Hook |
| `wm.Task.moveTaskToBack(Task)` | owned 根返回映射为关闭／关联返回时返回 true，否则原生 | 核心 |
| `wm.WindowState.canReceiveTouchInput()` | owned 内容／普通窗入场背后触摸门控返回 false，否则原生 | 核心 |
| `wm.WindowState.canAffectSystemUiFlags()` | 有 Session 且 owned task 返回 false，避免迷你窗改变系统栏状态 | 核心 |
| `wm.TaskFragment.canBeResumed(ActivityRecord)` | `nativeResult OR keepVisibleResumed`，仅活的可见 owned 顶层 Activity | 核心；焦点与 lifecycle 不等同 |
| `wm.Transition.onTransactionReady(int,Tx)` | 原生之前观察／遮罩转场 | 核心 |
| `wm.Transition.buildFinishTransaction(Tx,TransitionInfo,DisplayContent[])` | 原生之后补 owned 呈现／mask 的 finish 事务 | 核心 |
| `wm.Transition.collect(WindowContainer)` | 原生前记录显示变换，原生后跟踪容器／mask | 核心 |
| `wm.Task.moveTaskToBackInner(Task,Transition)` | before/after 维护真正返回后台与关联状态 | 核心 |
| `wm.TransitionController.requestStartTransition(Transition,Task,RemoteTransition,List)` | 原生前记录 owned 转场请求，保留原生返回 | 核心 |
| `wm.ActivityStarter.handleStartResult(ActivityRecord,ActivityOptions,int,boolean,RemoteTransition,Transition,int)`，缺失时仅试末尾追加 `ActivityCallerState.CallerInfo` 的八参版本 | 原生前后识别真实 source/start/result，安排关联任务及快捷项归属；不重写 Intent flags | 核心；`mSourceRecord/mLastStartActivityRecord/mRealCallingUid` 也必须存在 |
| [ServerViewport] 出站全链（下一节） | owned 客户端一致视口 | 核心；安装异常向外传播 |

### 3.2 局部可选观察／改写 inventory

来源仍为 [SunWindowModule]，不意味着可以在任意 ABI 静默启用：

| 目标 | 行为与降级 |
| --- | --- |
| `WindowManagerService.updateNonSystemOverlayWindowsVisibilityIfNeeded(WindowState,boolean):void` + `mHidingNonSystemOverlayWindows:List` | after 不改变原生结果，只合并排队更新安全暂停。字段／方法任一不符则二者设 null，缺少**本次安全优先级适配**；并非已实现另一套同等安全检测 |
| `WindowState.finishDrawing(Tx,int):boolean` | after 通知真实绘制序号供 cover 稳定判断；失败 `TASK_COVER_DRAW_HOOK_UNAVAILABLE` |
| `WindowState.canReceiveKeys(boolean):boolean`；预检 `TaskFragment.resumeTopActivity(AR,ActivityOptions,boolean):boolean` | owned 被阻断窗口不可接键；预检成功置 visibleResumeReady。发生恢复调用异常会关闭该可选能力，不因此恢复整个 task |
| `ActivityRecordInputSink.getInputWindowHandleWrapper(boolean)`，字段 `mActivityRecord`，返回 wrapper 的 `setInputConfigMasked(int,int)` | 先原生计算，再对 blocked owned sink 写 NOT_TOUCHABLE=8；失败原生 sink 保留，不能声称 mini 非焦点本身即可阻断全部输入 |
| `ActivityRecord.shouldBeResumed(AR):boolean`、`shouldPauseActivity(AR):boolean` | 仅可见 owned mini 在原生判断基础上加 resume／抑制 pause；还检查 shouldMakeActive／canResumeByCompat；不全局强改任务 focusability |
| 上述 lifecycle 的 `makeActiveIfNeeded(AR)`、`shouldResumeActivity(AR)`、`relaunchActivityLocked(boolean,int)` | **deopt 调用点而非额外 Hook**；记录结果；局部安装抛错则卸载 paired lifecycle hooks |
| `android.window.BackAnimationAdapter.isAnimatable(int)` | owned 关联根 Back 抑制远端预测预览，内部 app 回调保留；缺失只失去此预览门控 |
| `Task.isResizeable(boolean)`，缺失仅试无参；`ActivityRecord.isResizeable():boolean` | 只对 owned 或 ThreadLocal 预检候选返回 true，不改 app manifest／系统开发者开关；paired 局部回滚 |
| `DisplayContent.onRequestedOverrideConfigurationChanged(Configuration)` | 原生前观察显示几何，尽早遮住旋转快照；缺失保留其他旋转观察路径 |
| `ActivityRecord.setRequestedOrientation(int)` | after 观察应用请求；失败不禁小窗 |
| `ActivityRecord.finishRelaunching()`；`activityResumedLocked(IBinder,boolean)` | after 布局诊断／恢复观察；独立失败不禁小窗 |
| `ActivityStarter.mRequest` → `Request.originatingPendingIntent`；`PendingIntent.getTarget()` | 不是 Hook；显式 PendingIntent 归属识别能力，失败只关闭该归属路径，不猜发起者 |

## 4. owned 客户端视口：system_server 出站边界

[ServerViewport] 不 Hook app Activity／ViewRoot；原生 task 仍是 mode 5。客户端配置 mode 通常映射成 1（微信保留 5，见 [WindowPolicy]），不是把系统所有 freeform 都伪装全屏。

| 精确目标／匹配规则 | 读取／改写内容与边界 |
| --- | --- |
| `android.app.servertransaction.{LaunchActivityItem,ActivityRelaunchItem,ActivityConfigurationChangeItem,MoveToDisplayItem,WindowStateResizeItem}` 构造器 | 只匹配首参为 `IBinder`（Activity）或 `IWindow`（resize）的非空构造器；每类必须至少一个。按参数类型映射 Configuration／MergedConfiguration／InsetsState，非仅凭同名任意重载；不是固定单一构造器签名 |
| `wm.WindowProcessController.scheduleClientTransactionItem(IApplicationThread,ClientTransactionItem)` | 只处理 ConfigurationChangeItem 且 `mConfigActivityRecord` 归属 owned task 的进程配置；新建 `ConfigurationChangeItem(Configuration,int)`，不改 display-wide 配置 |
| `wm.WindowManagerService.addWindow(Session,IWindow,WindowManager.LayoutParams,int,int,int,int,InputChannel,WindowRelayoutResult):int` | 唯一九参精确匹配。after 成功只映射 owned IWindow 的初次 Insets；不填它原本空的 mergedConfiguration |
| `wm.WindowManagerService.relayoutWindow(...)` | 在 declared methods 中要求含 IWindow 与 WindowRelayoutResult 且匹配数量恰为一；after 成功映射 owned 出站 mergedConfiguration／Insets。**当前不是完整固定参数签名验证**，移植需补实际 ABI 对照而非宣称任意版本兼容 |
| `android.view.IWindow.Stub.Proxy.insetsControlChanged(InsetsState,InsetsSourceControl.Array)` | direct 通知绕过 relayout，仍只映射 owned Binder 的 InsetsState |

依赖 `ActivityRecord.forTokenLocked(IBinder)`、task `forAllWindows(Consumer,boolean)`、MergedConfiguration 拷贝与 get/set、WindowConfiguration 的 rotation/maxBounds，以及 InsetsState／InsetsSource 复制／frame 操作。WMS 缓存／共享源先复制，只有 Binder out 参数保留 identity 再 set 映射结果。逻辑 display frame／cutout 与 IME 坐标按 owned placement 变换；无关任务保持原生。初次 add、resize、relayout、直接 Insets 缺一都会产生首帧／键盘不一致，因此目前该安装器不是随意可关的附加美化。

## 5. 独立 server 子链

### 5.1 根 Back 与关联返回

[LinkedBack] 局部安装三项：

1. `android.window.BackNavigationInfo.Builder.setOnBackInvokedCallback(IOnBackInvokedCallback)`：对已接管根的 native callback 包 Binder wrapper，依旧转发非本路径事务。
2. `wm.ActivityClientController.onBackPressed(IBinder,IRequestFinishCallback):void`。
3. `wm.ActivityClientController.finishActivity(IBinder,int,Intent,int):boolean`：只消费结果码为 0 且无 result Intent 的对应根返回；返回 false 防止活 Activity 被标记 finished，真实结果／内部页 finish 原生处理。

读取 IOnBackInvokedCallback.Stub 的 start/cancel/invoke transaction 常量、WindowState callback info／可见性、AR token。通过 `DisplayContent.registerPointerEventListener(WindowManagerPolicyConstants.PointerEventListener)` 观察提交的原生流（注册监听，不是 Hook）。Pending 原子引用有期限、UID 与根 token 身份约束，路由时清除／恢复 Binder calling identity。任一安装失败只卸载局部三项并记录 `LINKED_ROOT_BACK_HOOK_UNAVAILABLE`。

### 5.2 分享／原生启动路由

[ShareWindow] 是可选四 Hook 子链：`ActivityStarter.computeLaunchParams(AR,AR,Task)`、`resolveReusableTask(boolean):Task`、`computeTargetTask():Task`、`startActivityUnchecked(...)`。源码注释提到的 `setNewTask` 是返回 null 后继续触发的原生选择行为，不是额外 Hook。

`startActivityUnchecked` 首选 14 参：`(AR,AR,IVoiceInteractionSession,IVoiceInteractor,int,ActivityOptions,Task,TaskFragment,BalVerdict,NeededUriGrants,int,Transition,boolean,int):int`；仅缺失时试在 ActivityOptions 后插入 TaskDisplayArea、末尾追加 CallerInfo 的 16 参版本。通过 ThreadLocal shareLaunch／launchForeground 区分同一次 native start，复制 ActivityOptions，保留真实 Intent、URI grants、result 链及原生 start 判定。只对选定请求阻止复用来源 fullscreen task；安装器失败卸载已装局部 hooks。安装器、权限、系统选择器／文档／拨号等有明确排除，安装确认请求走原生 fullscreen options 而不是拉入小窗。

### 5.3 边侧隐藏继续运行

[WindowContinuation] 在 TaskFragment 与 Task 各解析并去重 `shouldSleepActivities():boolean`、`sleepIfPossible(boolean):boolean`，另 Hook `ActivityRecord.shouldBeVisibleUnchecked():boolean`；deopt `updateAndCheckVisibility(boolean,boolean)`。仅用户开启 keepEdgeAwake、活 lease、非停放／恢复／关闭／全屏转场／安全暂停、边侧隐藏 mini 才改谓词；shutdown 仍原生。不得拿这组 Hook 全局保活。

电源侧调用 `PowerExemptionManager.addToTemporaryAllowList(String,int,String,long)`（60秒）与有期限 PARTIAL_WAKE_LOCK（90秒），至多每30秒续期。能力／服务不可用时记录 unavailable；更新失败释放对应 wakelock，原生省电语义保留。原生睡眠和电源扩展是两个独立前提，不能把“Hook存在”当作后台永久运行保证。

### 5.4 清理宽限

[TaskCleanupGuard] 单独以最高优先级 Hook `com.android.server.am.ActivityManagerService.forceStopPackage(String,int):void`。仅被模块接管过的 package+user、内部异步维护清理适用；外部 Binder 强停、模块显式结束立即原生执行。真实可见页面／边侧隐藏保留，parked 视为不可见，离开保护后五分钟才重放已延期原请求；复核包 UID，ThreadLocal replay 防重入。`states` 有独立同步；检查可见性和执行重放不应拿着该锁嵌套进入 WMS。安装失败原生清理不变，不是免费稳定性保护。

## 6. SystemUI／Pixel Launcher 入口与输入

### 6.1 SystemUI 启动与长侧滑

[BackBridge] 仅 SystemUI 主进程，`Instrumentation.callApplicationOnCreate(Application)` after，主线程启动 [SystemUiHost]；也由 `BackPanelController.onViewAttached()` 补 connect。宿主初始化失败 cleanup/live=null，3秒后重连；不是创建新 Android service。

长侧滑局部核心四项：

- `com.android.systemui.navigationbar.gestural.BackPanelController.onMotionEvent(MotionEvent)`、`onViewAttached()`、`onDestroy()`。
- `...EdgeBackGestureHandler.dispatchToBackAnimation(MotionEvent)`。
- 依赖 `mView/backCallback`、`NavigationEdgeBackPlugin.BackCallback.cancelBack()`、handler 的 `mAllowGesture/mLogGesture/mInRejectedExclusion`。
- `DisplayBackGestureHandlerImpl.onInputEvent` 对应 adapter 的 `invoke(Object)` 必须成功 deopt；接口形状时仅选已知 `EdgeBackGestureHandler$$ExternalSyntheticLambda20`，不是扫描所有 lambda 猜测。

只延长原生已接纳的 Back 流，提交前 cancel 原生 predictive Back，并把匹配 UP 改 CANCEL；失败 proceed／清理，不截断无关流。Hook 安装失败卸载这四项，不等于前面已装的 host、通知等辅助链全部卸载。长侧滑受 `corner.longBackWindow` 独立配置控制。

辅助小窗相关 Hook（其他独立功能入口不在本页重复）：

| 来源 | 目标／用途 | 可选失败 |
| --- | --- | --- |
| [BackBridge] Shell finish | `com.android.wm.shell.transition.Transitions.onFinish(IBinder,WindowContainerTransaction)`，精确核对 mKnownTransitions／mMixpatchAnimations 与 ActiveTransition.mStartT/mFinishT/mMerged/mInfo | 只给匹配 owned Back 的原生最后一份 finish 事务追加 mask；缺失不改 Shell finish；不是强行 finish 所有转场 |
| [BackBridge] 截图观察 | ScreenshotController 唯一 `handleScreenshot` 三参且首参 ScreenshotData；`ScreenshotWindow.removeWindow()` | 只观察截图浮层优先级并隐藏相关自有入口；此处仅验证形状，不宣称全签名跨版本；局部卸载 |
| [NotificationWindow] | StatusBarNotificationActivityStarter synthetic `...Lambda7/6.run():void` 和 `...Lambda10/12.startPendingIntent(RemoteAnimationAdapter):int`，按 f$ 字段类型预检；deopt run | ThreadLocal 真实正文 click 内短暂关闭动画并恢复，只转发原 PendingIntent；排除通话／闹钟／全屏／分组概要／跨用户，缺失回原生通知点击 |
| [WindowTools] | `QSFactoryImpl.createTile(String):QSTile` after 捕获原生 factory；调用 `QSTileImpl.setTileSpec(String)`、`click(Expandable)` 触发录屏 | 无 factory／缺签名快捷操作返回失败，不造替代录屏实现；截图调用 InputManager.injectInputEvent(InputEvent,int)，非 Hook |

### 6.2 SystemUI 控制层不是应用内容窗口

[SystemUiHost] 用显示器0、原生 window context；入口宿主 type 2017（TYPE_STATUS_BAR_SUB_PANEL），[WindowControls] 拖柄／mini hit windows 使用 TYPE_APPLICATION_OVERLAY（2038）。`WindowManager.LayoutParams.setTrustedOverlay()` 必须存在，失败抛初始化异常；**不降级成不受信任覆盖层**，也不是系统告警窗口永远置顶。

控制触区优先单 root：反射 `ViewTreeObserver.OnComputeInternalInsetsListener`、`InternalInsetsInfo.setTouchableInsets(int)`、`touchableRegion`、`TOUCHABLE_INSETS_REGION`、`addOnComputeInternalInsetsListener`。这是 View 回调注册，不是 Xposed Hook。内容区域穿透；普通窗内容／可见拖柄从 mini Region 扣除，保留已捕获触摸流。预检／建窗失败回退旧六窗；回退重叠只保留最大未遮挡矩形，全盖禁触，不能表达所有不连通剩余区域。`dispatchTouchEvent=false` 不能把已命中的 WMS 事件重新投给下面的窗口。

### 6.3 Pixel Launcher

[LauncherGuard] 只匹配 `com.google.android.apps.nexuslauncher` 包及其进程，不是任意 Launcher3 ABI：

- `com.android.quickstep.TouchInteractionService.onCreate()/onDestroy()` 和 `TouchInteractionHandler.onInputEvent(InputEvent):void` 三项。仅已授权／lease存活的角落手指流、正确 display/device/downTime 才跳过 launcher 消费；其他输入原生继续。失败卸载三项、disconnect/gate.disable。
- [BackVisual] 可选 `LauncherBackAnimationController.applyTransform(RectF,float)` after 与 `onCancelFinished()`：保存有时效的真实 leash pose，只有 SystemUI UID 能查询／认领，认领后仅隐藏对应预测 leash。失败局部回滚，不猜窗口位置。
- [RecentsFilter] 可选 `RecentsView.applyLoadPlan(List,int)`，依赖 GroupTask.containsTask(int)/getTasks、reloadIfNeeded、mAppliedTaskListChangeId；只过滤单 task 历史组，保留运行 task stub 作为原生 Home/recents 动画身份，不过滤成组分屏。配置来源限 system UID+协议。

## 7. 必需的 native 能力、事务与恢复

### 7.1 接管前置条件与原生小窗冲突

[WindowEngine] 的 validate 要求原生 `mSupportsMultiWindow`／`mSupportsFreeformWindowManagement` 都为 true，task root+leaf+attached、parent 为 TaskDisplayArea、display=0、user=0、普通应用 activity type、非 forceOpaque、符合 bounds/mode override 及 freeform/display area 能力、无阻塞转场。scoped resize 只扩大当前候选／已接管任务的可调整资格；**不全局开启或禁用原生 Freeform**。

带原生桌面模式／厂商小窗的系统还可能同时管理 bounds、focus、always-on-top、caption、TaskOrganizer、Shell leash 或 input sink。当前没有通用“另一小窗管理器所有权协议”；ABI 成功不表示双方不会交替覆盖。适配时应验证选中 task 是谁管理、父层级／mode／leash 是否可支持，并对不明所有权保守拒绝或保留原生；不要为了避免冲突全局屏蔽系统 Freeform。多显示器／多用户／非叶根／分屏不在当前 validated 支持范围，不能按 ROM 名绕过条件。

### 7.2 Surface／状态依赖

[FrameworkAccess] 必需簇包括：Task bounds/mode/focus/AOT/config、DisplayContent focus/IME/stable bounds、TransitionController collect/request/no-animation 与 Transition targets/changes/startTx、AppCompatController.SizeCompatModePolicy 浮窗缓存、TaskSnapshotController、SurfaceAnimator leash、SurfaceControl crop/radius/scale/position、TaskDecoration preflight。常量 `TransitionInfo.FLAG_NO_ANIMATION` 明确要求 262144；不是值随意可替换。

`inPlayingTransition(WC)`、SurfaceFreezer.hasLeash 可选；缺失时回到更保守的 animating 判定，可能暂停更多展示。装饰排序调用 native `Transition.assignLayersForFinishTransaction(WC,Tx)`，在 playing/collecting/building transition layers 时不重排；不指定一个跨安全窗口的全局最高 Z。

[TaskVisibilityGuard] 持有确切 task Surface 和借出的 detached activity leashes，WM 锁内调用；mask 转交／释放后旧回调不得再夺所有权。alpha=0 不是禁触，因此 parked／screen／外部安全暂停还 native hide task 并门控 sink／keys。[TaskCover] 是临时 sibling subtree，buffer／真实 Activity 继续，SF 帧时钟驱动；仅完全不透明时复用真实呈现，揭开前刷新。ScreenDecorationsUtils 的所属显示器原生窗口圆角可用则用于 fullscreen 端点；失败记录 unavailable，不猜额外状态栏裁剪。

[TaskDecoration] 的 bar/dim 是 parent 下 sibling，白 backing 是 task 下最低 Z 的自有 ColorLayer：全逻辑 canvas、白色、无输入，随 task crop/corners/scale/alpha/hide；独立于 cover pose、bar/dim hide。安全暂停／parked／screen 显式禁用 backing，surface 更换回收自有 child，不 release task 原句柄。透明到外部的全部像素都将变白，深色仍白；没有 126px 高度或包名特例。

### 7.3 线程、锁、Binder 与失败恢复

- system_server 原生 Hook 可能从 Binder、WMS traversal、动画／显示线程进入；不能假设都在模块 Handler。attach 取 `WMS.mGlobalLock`；native task 遍历／修改、Session 与 native 身份检查、render/transition/mask 需沿现有锁边界。
- 模块 `HandlerThread("SunWindowDemo5",THREAD_PRIORITY_DISPLAY)` 驱动控制合并、恢复、report；其 Looper 上取 `Choreographer.getSfInstance()`，失败 fallback getInstance。renderFrame 在 global lock 内 beginFrame/apply/endFrame；a.transaction 有 frameTx 时共用，否则取 native pendingTx。非线程安全的 frameTx 不能移到并发执行器随意共享。
- 安全窗口 after Hook 只用 AtomicBoolean 合并，投 Handler 后拿 WM 锁；不在安全集合变化调用栈中直接抢全局焦点。判定同 display、可见、不同 UID：外部 task 隐藏自有展示／输入；同 task 权限保留真实 task 并临时允许焦点，仅撤自有遮罩／装饰。缺原生集合时不臆造包名白名单。
- SystemUI View／WindowManager 操作在主线程；Wire Binder 帧收件只验证 uid=1000、descriptor／protocol16并合并 main.post；设置读取在单线程 settingsWorker，成功后主线程套完整快照。Provider 失败可使用已有宿主完整缓存并退避重试，不能当作配置已实时同步。
- Server 控制 Binder 校验实际管理器／SystemUI UID，Back prepare/cancel/finish 仅 SystemUI UID；广播校验 shared identity 的发送 UID。COMMAND receiver 实际 `registerReceiver(...,null,handler,RECEIVER_EXPORTED)`，**没有广播注册权限过滤**；原注释“Permission is enforced”与调用不符，真实边界是接收时 `getSentFromUid()`／controlUid 及后续协议／身份检查，而非仅声明 CONTROL 权限即可自动保护。lease/token/protocol 共同约束会话，不能只凭 action 或包字符串授权。Launcher guard 状态仅接受 SystemUI UID+活 Binder；history 仅 system UID。
- [TaskCleanupGuard] 使用 states 独立锁，LinkedBack 用 AtomicReference／ThreadLocal，不能将它们等同 WMS 全局锁；native 回调／重放不能在持不相关锁时阻塞反向通信。
- `failure(Session,Throwable)` 先置 restoring，Handler 发起 recover，避免向 WM caller 抛出适配异常。恢复记录为 `/data/system/sunwindow-demo5.json`，另有 hidden history；恢复按任务身份逐项复原原 mode/bounds/AOT/config/forceTranslucent、surface 大小／crop／radius／focus并移除自有层。恢复未完成保留 journal，超过当前重试上限记录 RECOVERY_PENDING，不能为“成功”清空证据。
- restoreTask 在恢复 surface crop 前 remove decoration；forget/dispose 也覆盖只存在 backing 的情况。模块卸载 Hook 或宿主死亡不是 native surface 自动复原的替代品。启动健康计数和全量动态卸载仍未实现。

## 8. 证据分层与移植验证清单

### 8.1 已有静态归档与测试入口（不是本轮执行结果）

| 证据 | 实际覆盖／不能证明的内容 |
| --- | --- |
| [new-members.json]、[viewport-members.json] | 归档 framework SHA256 `89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f`；services `3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9`，API37成员库存／部分视口边界。存在成员不证明时序／输入／转场行为 |
| [verify_target.py] | 明确检查部分新增 API37 成员、addWindow/安全集合、rotation、Insets、notification、handoff、launch-input、continuation、Back finish 等。不是全量 FrameworkAccess 自动等价审计；其中 app Activity/ViewRoot 成员库存不表示当前有 app Hook |
| [notification-members.json]、[back-finish-members.json]、[edge-input-members.json] | 所用旧归档 SystemUI SHA256 `a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972` 的 synthetic lambda／Shell／输入形状。不能外推到别的 SystemUI 编译产物；运行时备选 lambda 编号也不因此已全部实测 |
| [handoff-members.json]、[launch-input-members.json]、[continuation-members.json] | 原生 start／任务选择／电源 API 的补充库存。实际采用哪种 overload 仍由精确预检决定，不能按“Android 17”直接承诺 |
| [verify_runtime.py]、[runtime-changes.json] | 不可变 Demo8.2 摘要来源与现代码变更原因、纯 JVM 几何／规则／visibility 检查；不能证明 ART Hook 生效 |
| [SystemPriorityTest]、[ViewportInsetsTest]、[ControlsOverlapTest]、[TaskBackdropTest] | JVM/Robolectric 的安全分流、复制映射、遮挡触区、child 生命周期回归，不验证设备窗口排序／secure flags 或 SurfaceFlinger 合成 |
| [SurfaceFrameTest]、[LaunchCoverFlowTest]、[TaskCleanupGuardTest]、[OwnedLaunchPolicyTest] | 事务／遮罩／清理与归属规则测试入口；本页不宣称该组全部通过，也不把历史已知失败隐去 |

目标适配应先记录 API level、构建指纹及 framework/services/SystemUI/Launcher 的实际 hash，核对成员参数、返回值、字段类型、常量语义、调用方内联，再做能力预检。Pixel Launcher 当前支持证据不覆盖任意 QPR2 Launcher；其他功能有 QPR2 路径也不能推导小窗 Launcher Hook 已兼容。

### 8.2 一条链一个可恢复验证闭环

1. **仅观察启动**：确认三进程 eligible/安装日志、核心与局部 unavailable、宿主 UID／协议／lease；不因 HOOKS_READY 推定 attach/host 已可用。
2. **接管与退出**：普通主用户主屏任务、native freeform 能力；检查未接管任务完全原生、原生桌面模式／厂商小窗所有权冲突、journal 恢复、失联／进程死亡。
3. **输入与生命周期**：normal/mini/边侧隐藏／parked、普通窗覆盖 mini、已捕获流、sink、键盘与焦点；可选续跑关闭／不可用均测试，不强称所有输入靠 alpha 解决。
4. **配置与转场**：初次 add、relayout、resize、直接 Insets、应用横竖屏、显示旋转、Home/recents、关联跳转和有结果返回；逐项检查 native start/finish 事务交接。
5. **系统优先级**：外部安装／确认、同 task 权限、键盘、锁屏／熄屏、截图浮层；保留原生安全 UI，不用提高自有层或吞安全输入“修复”。
6. **静态与真机结论分开**：归档脚本、host/Robolectric、构建、设备采样各记结果与构建身份。可选能力缺失要描述真实降级，核心能力／身份不明则不接管，不能借测试通过声称其他 ROM／版本全面兼容。

[java_init.list]: <../../app/src/full/resources/META-INF/xposed/java_init.list>
[scope.list]: <../../app/src/main/resources/META-INF/xposed/scope.list>
[SunWindowModule]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>
[FrameworkAccess]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>
[WindowEngine]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>
[Geometry]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Geometry.java>
[Rules]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Rules.java>
[WindowPolicy]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowPolicy.java>
[GestureGate]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/GestureGate.java>
[CornerGesture]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>
[RotationGate]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/RotationGate.java>
[MiniMagnet]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/MiniMagnet.java>
[ServerViewport]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ServerViewport.java>
[LinkedBack]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LinkedBack.java>
[ShareWindow]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ShareWindow.java>
[WindowContinuation]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowContinuation.java>
[TaskCleanupGuard]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskCleanupGuard.java>
[BackBridge]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>
[SystemUiHost]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>
[WindowControls]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowControls.java>
[NotificationWindow]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/NotificationWindow.java>
[WindowTools]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowTools.java>
[LauncherGuard]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>
[BackVisual]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackVisual.java>
[RecentsFilter]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/RecentsFilter.java>
[TaskVisibilityGuard]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskVisibilityGuard.java>
[TaskCover]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskCover.java>
[TaskDecoration]: <../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskDecoration.java>
[verify_target.py]: <../../tests/verify_target.py>
[verify_runtime.py]: <../../tests/verify_runtime.py>
[runtime-changes.json]: <../../tests/runtime-changes.json>
[new-members.json]: <../../tests/target/new-members.json>
[viewport-members.json]: <../../tests/target/viewport-members.json>
[notification-members.json]: <../../tests/target/notification-members.json>
[back-finish-members.json]: <../../tests/target/back-finish-members.json>
[edge-input-members.json]: <../../tests/target/edge-input-members.json>
[handoff-members.json]: <../../tests/target/handoff-members.json>
[launch-input-members.json]: <../../tests/target/launch-input-members.json>
[continuation-members.json]: <../../tests/target/continuation-members.json>
[SystemPriorityTest]: <../../app/src/test/java/dev/local/sunwindowdemo/SystemPriorityTest.java>
[ViewportInsetsTest]: <../../app/src/test/java/dev/local/sunwindowdemo/ViewportInsetsTest.java>
[ControlsOverlapTest]: <../../app/src/test/java/dev/local/sunwindowdemo/ControlsOverlapTest.java>
[TaskBackdropTest]: <../../app/src/test/java/dev/local/sunwindowdemo/TaskBackdropTest.java>
[SurfaceFrameTest]: <../../app/src/test/java/dev/local/sunwindowdemo/SurfaceFrameTest.java>
[LaunchCoverFlowTest]: <../../app/src/test/java/dev/local/sunwindowdemo/LaunchCoverFlowTest.java>
[TaskCleanupGuardTest]: <../../app/src/test/java/dev/local/sunwindowdemo/TaskCleanupGuardTest.java>
[OwnedLaunchPolicyTest]: <../../app/src/test/java/dev/local/sunwindowdemo/OwnedLaunchPolicyTest.java>
