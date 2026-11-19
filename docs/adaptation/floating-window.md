# 小窗宿主接入

## 当前入口与分层

小窗仅在主模块提供：原生角落输入／扇区、菜单操作、通知正文和跨应用启动路由。`notificationWindow`、`landscapeShareWindow`沿用旧键，默认 true 但保留已存 false；后者历史名称不表示仅横屏。长侧滑、无障碍按钮、桌面 shortcut 及窗口／遮罩莫奈已退役。快捷建议的「以小窗打开」是建议侧独立开关（`suggestions` 的 `window` 键，默认关闭），只在用户点击建议按钮且小窗总开关开启时经 [Wire.openIntent](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Wire.java>) 走既有 OPEN 路由，不改变通知正文与跨应用入口的准入。

| 层次 | 实际责任 |
|---|---|
| 值规则 | [CornerGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>)、[WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>)及导航／呈现／生命周期／恢复规则处理采样事实和状态，不读取 ROM 名 |
| SystemUI | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)接原生输入和截图优先级；[SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)维护扇区、热区、配置、受信窗口与安全取消 |
| server | [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)安装 Hook；[FrameworkAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>)预检原生能力；[WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)持有 Task／Session、WM 锁、Handler、事务与 journal |
| Launcher | [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>)只保护明确已拥有的输入流，并可安装历史任务过滤；目标和桥接授权由[LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)分开处理 |

## 输入与局部 ABI

[EdgeBackHookTarget](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/EdgeBackHookTarget.java>)只发现实际 callback：已知 synthetic `invoke(Object):Object`及 owner，或构造器捕获的 Kotlin Function1。检查字段类型、实例方法和唯一 owner，拒绝歧义；同一个 callback Method 去重安装，共用 interceptor，不扫描近似 lambda 猜测。

角落只处理默认显示器、合格单指流及当前安全环境；达到斜向阈值后才 `pilferPointers()`，未 claim 的点击／长按继续原生。禁用、配置变化、截图、安全界面、锁屏、旋转或输入异常撤销自有状态。原生 `proceed()`位于解析异常处理之外，只调用一次；外层接收器的 `finishInputEvent`仍由原生执行。

宿主使用默认显示器 WindowContext。`setTrustedOverlay()`必须可用，不降级成不受信覆盖层。控制触区优先通过 InternalInsetsInfo 合并，内容穿透并扣除普通窗内容／拖柄，保留已捕获流；预检或建窗失败回退旧六窗。回退只能保留最大未遮挡矩形，全遮禁触，不承诺表达所有不连通区域。

## 启动路由

### 通知正文

[NotificationWindow](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/NotificationWindow.java>)仅接原 activity PendingIntent，必须等于通知 contentIntent，排除分组概要、fullScreenIntent、call／alarm、其他用户、模块／SystemUI／android 来源及当前前台包；宿主还要求启用、后端就绪、交互且未锁屏。不能把跨应用路径的系统／安装器排除扩称为通知每个 PI 目标均有同样排除。

[SystemUiWindowTargets](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiWindowTargets.java>)解析两种真实发送 ABI：已知 click-run／sender synthetic 链，或私有 `sendPendingIntent(PendingIntent,Intent,Bundle,NotificationEntry):int`。前者用 ThreadLocal 约束真实正文点击并 `finally`恢复动画标志；后者从原 options 读取显示器。两者只转交默认显示器原 PI／fill-in，不重建 Intent。

解析不匹配时原生发送一次；交给宿主后由宿主独占派发／失败处理，interceptor 异常不再次 `proceed()`。结果标记 `dispatched=true`后不重发 PI，可在权限允许且任务明确时移到前台；确定未派发才沿宿主原路径发送。同步传输异常仍沿现有 fallback，不能声称未知提交结果有端到端 exactly-once 保证。

### 跨应用

[ShareWindow](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ShareWindow.java>)围绕原生 ActivityStarter 复制 options，保留 Intent、URI grants、result 链和系统启动判断。准入核 realCaller／source／UID、真实前台全屏 Task、默认显示器／用户、3000ms 宿主租约、锁屏／恢复／转场；排除同 app、桌面、系统／安装器／权限等不适用请求。安装确认走原生全屏，不为适配删安全条件。

[WindowServerTargets](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowServerTargets.java>)精确选择 `isResizeable(boolean)`／无参、`handleStartResult`七／八参及 `startActivityUnchecked`十四／十六参（TaskDisplayArea／CallerInfo 差异），校验返回值与实例方法。它只解析布局，共用启动和恢复规则，不按 ROM 选择业务。

用户可在防误触设置中维护“不以小窗打开的应用”（`windowExcludedPackages`），默认空、不预置包名，与来源应用手势禁用表独立。新打开目标命中时保留真实启动并交给原生展示；已管理任务及其内部页面继续运行；存在同包受管任务时先保留原生任务选择，实际解析成未管理的新任务才应用排除。包名偏好不授予跨用户启动权限。

受管来源打开系统设置／权限、设备认证、文档选择及安装确认时，沿已有独立原生容器路由复制并清理小窗 options，不修改原 Intent／grants／result 或系统启动判断；权限控制器依据系统解析角色识别，不依赖包名前缀。原生页面不套用小窗退出动画，不夺取其他宿主管理的 freeform。

连续跳转交接前确认旧 session 仍存活；结束的中转任务不参与退出遮罩和视觉交接。接管或呈现失败与用户关闭分开：前者使用原目标任务做可见的前台原生恢复，后者维持既有退后台恢复；不可打印原生回退后仍排队将该目标退后台。无效 Surface 不写透明度，恢复记录只有实际还原成功后移除。

## 任务、视口与呈现

接管要求原生 multi-window／freeform 能力、主用户 0／显示器 0、普通应用、attached root+leaf Task 且直接属于 TaskDisplayArea，以及合法 bounds／mode、可调整性与转场。用户在设置页选择继续开启不会绕过这些条件。不全局解锁 Freeform、不强抢 OEM TaskOrganizer／Shell 管理的未知任务。

[ServerViewport](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ServerViewport.java>)仅处理 owned 客户端出站 Configuration／Insets：Activity transaction、初次 add、relayout／resize、直接 Insets 通知保持一致。复制共享配置／源，不修改 WMS 全局缓存或普通 app；系统 Task 仍为 mode 5，客户端映射遵守现有应用规则。它不是普通应用 Activity／ViewRoot Hook。

[TaskCover](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskCover.java>)跟随 SF 帧节奏，只在完全不透明时复用真实呈现，揭开前刷新；转场 Surface 和同步完成事务有明确转交责任。[TaskDecoration](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskDecoration.java>)的白色 backing 是 Task 下自有最低层 ColorLayer，继承 crop／圆角／缩放／显隐；透明到外部的像素深色模式仍白，不猜固定顶部高度。原生 Task Surface 不可释放，自有子层在替换／恢复／dispose 清理。普通拖柄和独立贴边条共用随系统深浅模式切换的对比色，不改变应用透明背景规则。完全贴边且动画结束后，隐藏受管 Task 子树、backing 与装饰，保留独立恢复条；向内拖出／还原时沿既有事务重新显示，不能只依赖屏外坐标，也不改逻辑任务生命周期或截图安全权限。旋转取消进行中的拖动但保留贴边侧，复用已有位置重映射。不可聚焦迷你窗若仍占用输入系统 focusedApp，只对当前受管身份将其让给原生选出的同用户／显示器、可见可聚焦背景，续跑退出后重验；不强启桌面、不移动任务或覆盖其他应用／安全窗口焦点。

原生安全窗口集合决定暂停：外部安全 Task 隐藏自有展示与输入；同 Task 权限确认保留真实内容和必要焦点，只撤自有遮罩／装饰。alpha=0不能代替 sink／keys／touch 门控，不提高自有层压过安全窗口。边侧续跑仅合格 owned mini、活租约和显式开关，不全局保活；清理宽限仅重放已延期的内部清理请求，外部强停和显式结束即时原生执行。

## 线程、通信与恢复

- server 原生 Hook 可能从 Binder、WM traversal 或动画线程进入。Task／Session／native 身份与事务在既有 WM global lock 下重验；display-priority worker 上的 SF Choreographer 共用 frame transaction，不能并发共享。
- SystemUI View／WindowManager 在 main，配置与图标 I/O 在 worker。广播核实际 shared sender UID；Binder 核 descriptor／op／协议16／token／session／租约；Back 同步操作只给 SystemUI。不能只信 CONTROL 声明、action 或自报包名。
- 安装失败只卸载该链 handles；可选能力缺失保留对应原生路径，不等于模块全量动态卸载。启动健康计数和完整动态 Hook 卸载尚未实现。
- [WindowRecoveryPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowRecoveryPolicy.java>)提供身份／ready／重试规则；原生查找、AtomicFile／Parcel 与恢复副作用仍在 Engine。主包恢复记录沿用 `/data/system/sunwindow-demo5.json`等既有命名，未完成保留 journal，超限报告 RECOVERY_PENDING，不为显示成功清记录。
- 恢复逐项还原 mode／bounds／AOT／配置、Surface 尺寸／crop／圆角及焦点，并移除自有层。租约死亡或卸载 Hook 不等于原生对象自动复原。旧独立包的 first-claim、资格与迁回要求见[兼容墓碑](<standalone-window.md>)。

## 接入检查

按实际改动复用[基线与主机检查](<../../tests/verify_runtime.py>)、[接口检查](<../../tests/verify_target.py>)及既有输入／通知／恢复定向用例。成员存在、测试通过或构建成功不能证明 ART 回调、输入注入、Surface 合成或目标设备效果；仓库外资料缺失应记 SKIP。PR 简述实际差异与未核部分即可，不强制额外报告；可选设备场景见[检查清单](<../../DEVICE_ACCEPTANCE.md>)。
