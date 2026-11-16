# Alive 实验 AOD：SystemUI 能力、doze 模糊与有界视觉交接

## 当前诊断：code152（未新增内容层Hook）

151用户仍报熄屏闪真实时钟/日期/通知；152仅编辑卡片确认窄修，不是此问题已解决。真实树中LightRevealScrim与KeyguardRootView为兄弟，SharedNotificationContainer在root外，当前scrim空clip不会裁内容。root内同时含DeviceEntry/UDFPS/auth视觉，不可整root或Shade强alpha0/visibility隐藏；这两个容器继承ConstraintLayout且无自有draw/dispatchDraw，不能以getDeclaredMethod假称已适配。Root alpha Flow与通知原生多贡献min为真实候选，不能覆盖值后硬restore1，撤门必须让原生最新状态重算。将shouldDelayKeyguardShow复回true又会阻断当前ACK要求的真实locked/双showing，不能恢复旧1300ms猜改。尚无安全闭合的内容门实现；需同nonce/user/root下真实alpha/认证/转场边沿证据，再决定内容门与ACK/稳定AOD的撤门时机。不提前clip未swap壁纸、不修改认证/电源/亮度，timeout/wake/配置/用户/组件变化仍原生回退。

## 历史增量：0.11.5-alive-test1 / code149（不改SystemUI Hook）

本轮[固定AMBIENT下移与单页UI](<alive-platform.md>)只改应用编辑与motion默认锚点。系统AOD值仍只读本用户设置，实际常亮/省电/遮挡仍遵守原生；没有动态时钟bounds输入，不改本篇147睡眠准入/UID/nonce/期限/认证/亮度边界。三种解锁现象仍未解决，不能用本轮位置修改宣称认证或系统涟漪修复。

## 历史增量：0.11.4-alive-test1 / code148（数据准备，不改认证）

code148不新增SystemUI认证或解锁Hook，保留147下述入场选择、FIRST_SWAP UID/nonce/期限校验与双真实showing安全门。[Direct Boot准备](<alive-platform.md#code148direct-boot-数据边界>)只将核心资源及当前已应用照片/AtomicFile配置快照放入本用户应用私有DE，使系统所选且directBootAware的普通/实验壁纸service有机会在首次解锁前读取；不强选、重绑或启动壁纸，不使草稿/编辑预览在锁前可读。

迁移仅在已解锁worker访问CE，核心资源完整hash/白名单复制、只读暂存/fsync/rename，不覆盖已发布代、不删除CE源。已应用照片先发布、再提交并读回核验AtomicFile快照，revision偏好不作权威。失败或未准备仍安全回退；新安装先应用一次、旧版先已解锁迁移，之后下一次开机才具备数据。DE已应用照片以及保留的历史已应用/确认应用中断候选在首次解锁前对本应用可读是明确隐私取舍，不是系统认证放宽；本轮不自动回收或限制其总量，不能声称DE只存当前一张。

本轮编译/打包及首解锁前行为由Lead收口，本文不预报PASS。三种解锁现象仍在诊断，时钟下移/避让、照片burn-in及通知/跨应用扩展未实现；Direct Boot不作为这些问题已修复的证据。

## 保留实现：0.11.3-alive-test2 / code147

146真机只读证据确认GONE→AOD原生shouldDelayKeyguardShow约1300ms，首帧已有但真实锁屏/视觉交接必须等待；不是首swap本身延迟。147新增下列精确ABI，并与原Handoff同组安装/deopt，任一失败unhook整组：

- `ScreenOffAnimationController.onStartedGoingToSleep():void` 与 `animations:java.util.List`；主线程原动画循环前准备本次episode（重复不续期），循环及Fold候选保留。
- `UnlockedScreenOffAnimationController.startAnimation():boolean`、`shouldPlayUnlockedScreenOffAnimation():boolean`；字段 `notifShadeWindowControllerLazy:dagger.Lazy`、`lightRevealScrim:LightRevealScrim`、`lightRevealAnimationPlaying:boolean`、`lightRevealAnimator:ValueAnimator`、`decidedToAnimateGoingToSleep:Boolean`。
- 仅本次active未绝限、精确自有选中令牌/当前用户/AOD、默认屏ON且非interactive、同native窗口/root、实例确在动画列表、无occlusion/goingaway/fading/bouncer且原生未动画/decision仍null，进入原start同步动态ThreadLocal；原谓词先proceed，只有原true且重验上述门成立才投影false。原start自身走FALSEcache分支，不手写字段/强制showKeyguard。原 `maybeHandlePendingLock` 仍检查认证过渡并调用原 `doKeyguardLocked`。
- 不全局改predicate/DozeParameters/PowerManager，也不改原1300ms状态流或亮度；原生后续查询自然读其FALSEcache并可能早DOZE。原wake清cache/handler/animator仍运行，ThreadLocal finally清理，不带到其他查询。取消后的“回原生”只指恢复窗口flag与绘制，不重放已选择不播放的旧动画；不能承诺原动画自动补播或完整1800ms。
- admission是锁屏前的动画入场选择，故不要求already showing；**视觉safe仍要求两处真实showing且所有原安全门，FIRST_SWAP UID/nonce/期限/Surface验证不放宽**，没有准备好的可见帧就不透出。Service duration0只保已有未过期PREPARE/active/defaultON/noninteractive有界等待，不要求等待时已经可见/locked/Surface有效；绘制与ACK仍要求这些事实，命令schema不当发送者认证；DOZE回执只允许已start成功帧，OFF/SUSPEND仍撤回，不强保ON。

新147尚未真机验证，其他scrim/opaque/原生亮度仍可能影响观感；不把ADMISSION/VISUAL日志当物理可见证据。

## 历史实现：code146

历史为0.11.3-alive-test1/code146，覆盖145仅跳额外淡出的实现。此为用户追加授权的模块例外，不是普通应用获得签名权限；新Handoff尚未真机验证。仅主版安装 [AliveSystemUi](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemUi.java>)。明确选择 `dev.nativecomplements.module/dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService` 才可能产生额外能力；普通Alive仍为ambient=false回退。不新增全局enable偏好或Provider。

## 已核宿主与事件输入

离线依据为 current SystemUI SHA-256 `a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972`，不把其他 Android/OEM ABI 当作已验证目标。

`com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl` 构造参数依次为 `CoroutineScope, CoroutineDispatcher, BroadcastDispatcher, UserRepository, WallpaperManager, Context, SecureSettings, ConfigurationInteractorImpl`。原构造先完成，再把本实例的 `wallpaperSupportsAmbientMode: Flow` 投影为：

```
host FlowKt.combine(nativeAmbient, lockscreenWallpaperInfo, wallpaperInfo,
                    UserRepositoryImpl.selectedUser, host Function5)
    .flowOn(native bgDispatcher)
```

所有 Kotlin/coroutines 类都从宿主 ClassLoader 解析，Function5 是该 loader 的动态代理，transform 不挂起、不处理 continuation，仅返回 bootstrap Boolean。没有把模块 Kotlin/Flow 实例交给宿主，也不重写原生 readonly StateFlow、修改共享发射值或全局 Resources。组合保留原生 cold Flow 的收集/取消生命周期，不启动轮询、永久 worker 或新广播接收器。

原生 ambient Flow 在该 APK 中由两个 SecureSettings observer（`doze_always_on`、`doze_always_on_wallpaper_enabled`）与配置流 combine，经 unsafe map 读设置/资源后 flowOn；已读构造及 map/emit 链，**没有 distinctUntilChanged 或 stateIn**，因此同为 false 的 AOD 设置变化仍能触发重新判断，无需再搭一套 ContentObserver。两个壁纸 readonly 流已有 wallpaper-changed 与用户完成切换输入；另外组合用户仓库的原始 selectedUser StateFlow，切换开始也可撤回本模块额外能力，而不是等待过滤后的 SELECTION_COMPLETE 流。

**额外分支的 opt-in 是用户选用独立实验组件，不再以 `doze_always_on_wallpaper_enabled` 作为第二道开关。** 此设备该项为空且原生 wallpaper capability=false，没有可用的原生壁纸 AOD 入口；本模块不替用户写它。即使该项为 0，主动选择实验组件仍表示开启本组件的 AOD 壁纸；真正 `doze_always_on` 总开关始终有效。撤回该选择须切回普通 Alive 或其他壁纸；若系统另提供“息屏壁纸”开关，本实验不把它当自身的关闭入口。

## 仅额外分支的核验

1. 其他壁纸始终保留 native 能力结果。code144即使 native=true 也需在后台核验自有组件以建立窄 blur gate；不能再沿用历史“不新增 Binder 查询”的断言，核验失败仍返回 native 值。
2. native 为 false 时，当前 SelectedUserModel 必须仍是 StateFlow 的同一对象且 `selectionStatus == SELECTION_COMPLETE`，用户 ID 必须有效。用户切换中不添加能力。
3. 通过宿主 `UserSettingsProxy.getIntForUser(int defaultValue, int userId, String key)` 读取该用户 `doze_always_on == 1`，不写系统设置；该 APK 参数顺序已按 DEX 指令核对。
4. 在 native bgDispatcher 上重新查询 `WallpaperManager.getWallpaperIdForUser(which,userId)` 与 `getWallpaperInfo(which,userId)`，不把跨用户期间可能仍旧的 wallpaperInfo 缓存直接当授权。仅锁屏组件精确匹配实验服务、实际 metadata `supportsAmbientMode()` 为 true 时允许额外 true。
5. 独立静态锁屏的 info 也为 null，**不能直接回退主屏**。仅 LOCK ID 为负（没有单独配置）且 info 为 null 才查询 SYSTEM；ID 为 0/不一致、独立静态锁屏、未知异常均不添加能力。成功返回前再次核验实际壁纸 ID、LOCK ID、AOD 设置与当前 SelectedUserModel，降低查询过程切换造成的旧值复用。

读取到明确新组件后，切普通 Alive/其他壁纸、设独立静态锁屏、切用户或关 AOD，额外分支均恢复 native 结果。恢复是原生事件流的异步传播，不承诺跨进程事务级零延迟；系统原生壁纸/Doze 生命周期继续决定真实显示。

## ABI 与失败恢复

运行时精确预检构造、字段类型、host FlowKt 的四流 combine(Function5)/flowOn、host StateFlow.getValue、SelectedUserModel/UserInfo/SelectionStatus、设置 getter、两个 per-user WallpaperManager getter 与 WallpaperInfo.supportsAmbientMode。缺项不安装；constructor deopt 失败撤销 hook。单实例投影构建失败保持原 field，运行期间任何额外分支读取失败返回该次 native 值，不改变只读状态。当前构造期替换不用于热修已创建的 repository，模块由正常 SystemUI 启动安装。

本机 Android 36 SDK 源码可核 `getWallpaperIdForUser(int which,int userId):int` 的负值缺省语义以及 `getWallpaperInfo(int which,int userId):WallpaperInfo`；实际运行框架仍逐项预检，SDK 源码不是当前设备实现证据。

## code144：只消除两个 doze producer 的模糊贡献

code143只读现场显示：所选留光自身blur0，但系统稳定doze的 `wakeAndUnlockBlurRadius=52`、`lastAppliedBlur=52`。归档代码按 `lerp(minBlur,maxBlur/2,easedDoze)` 计算，现场min1/max104/doze1吻合；这不是证明所有样式模糊都来自系统，照片样式2..5仍有上游内容模糊。

新增投影只标记两个已核真实producer的同步调用范围：`NotificationShadeDepthController$statusBarStateCallback$1.onDozeAmountChanged(FF)` 与 `NotificationShadeDepthController$2$1.emit` 的classId1能力collector。ThreadLocal标记同一owner，只有它们调用 `setWakeAndUnlockBlurRadius`、ambient能力为true且doze量>0、当前repository仍匹配精确自有实验锁屏组件/当前已完成选择的用户/AOD条件时，才把**本次贡献参数**投影为0。不是全局setter清零，也不改BlurUtils、资源min/max或整个ambient Flow。

该匹配由前述后台Flow的实查结果建立，并在setter上核对当前用户及锁屏/主屏流对象身份；不在高频setter内作Binder查询。组件/用户/AOD变化由原生流撤销匹配，后续producer恢复原值；不承诺跨进程零延迟。setter的认证动画直接调用不在producer作用域，不投影；QS/shade/brightness mirror/应用启动及其他原生贡献仍由原计算合成。读取失败保留native参数；三处hook/逐个deopt失败撤销这组blur hook，不扩大作用域或阻止原生能力路径。

## code145历史：只跳额外淡出不足以接管视觉交接

[code144日志](<../../verification/alive-live-20261116-1400/manual-transition-logcat.txt>)曾定位scene释放至DOZE的1.913秒断层，145随后增加7800ms绝限内静态holding及额外fade投影。[145新只读证据](<../../verification/alive-live-20261116-1440/logcat-all.txt>)进一步显示14:38:52.987 sleep、53.029首swap、54.830窗口到期、57.066 DOZE，期间visible/draw=true；用户仍报告软件内/桌面都先原生熄屏动画/黑场再Alive。这排除了“只需晚释放”作为完整答案，但不凭日志武断认定每帧具体遮挡层。

code145曾对已核 `LightRevealScrimViewBinder$bind$1$1$2$1.emit(Object,Continuation)` 仅作host `Pair(nativeTarget,true)`→`Pair(同一个nativeTarget,false)`，跳额外333ms等待与733ms淡出，保留0.4/0.64原生目标暗度。以下门控记录为历史；**code146已删除这组投影，不与新Handoff叠加。**

当时门控必须全部成立：view所属默认显示器且ON/DOZE、非交互、后台已实查的精确自有实验锁屏组件/当前完成选择用户/AOD匹配仍有效；只在binder主线程收集中识别原生target=1开启一次identity-scoped episode，期限≤7800ms。随后0<target<1且原生animate=true时，还需真实KeyguardStateController showing、未occluded/goingAway/bouncer/fadingAway；user/lock/home对象身份仍相同才投影。该次分支无论安全与否都消耗token，不延后解锁后补播；唤醒、其他target、用户/组件失配与读取失败清理episode，过期不重放。不是逐帧hook，也不在binder里新增Binder查询。

当时该组ABI/deopt失败独立撤销，不影响能力Flow/doze blur；这段仅保留145实现历史，不再描述146为Hook LightReveal binder。两版都不打开全局AOD资源，不绕过 `DozeParameters.getAlwaysOn`、省电、接近保护或显示器暂停；system_server绑定例外与视觉交接是不同边界。

## code146：共用睡眠入口、首swap确认后才透出Alive

[AliveSleepHandoff](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSleepHandoff.java>)替代145额外fade投影，接已核 `KeyguardService$2.onStartedGoingToSleep`，软件内与桌面共用同一个入口，而非桌面补丁。原方法先执行，再开启一次episode，经原生 `WallpaperManager.sendWallpaperCommand` 向实际壁纸发PREPARE；重复睡眠信号不续期，绝对期限7800ms，不持wake lock。

1. 复用能力投影的实查MatchToken：当前完成选择用户、精确实验锁屏组件、AOD开关、真实壁纸ID及本包实际UID。仅默认显示器ON/DOZE、非交互、同一个native shade window/root且真实Keyguard showing、未occluded/goingAway/fading/bouncer时有效；窗口state也须一致。未知一律原生。
2. 在该窗口原生 `applyWindowLayoutParams` 提交范围临时OR `FLAG_SHOW_WALLPAPER`，原方法继续执行，随后恢复原pending flags；不提前空画系统遮罩，不改窗口输入/认证属性。
3. [Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)只在自有非预览实验服务、默认显示器ON、非交互、真实锁定、native visible且surface有效时，向 [renderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)请求一次真实成功swap回执。经raw Binder返回；接收端核 `Binder.getCallingUid()` 为该用户本包实际UID、interface descriptor、episode nonce、display0、状态/Parcel无多余数据及期限。旧回执不能启用新episode；成功swap不等于物理屏幕已呈现。
4. 回执之后且上述同一安全scope仍有效，才在同root `LightRevealScrim.onDraw` 临时空Canvas clip，**仍执行原onDraw并finally恢复clip**。不改native alpha、opaque回调、LightReveal controller或Keyguard认证，不吞原方法、不全局透明；也不修改显示器电源、亮度或输入。
5. timeout、wake、Engine取消/renderer失败/已确认后不可绘或隐藏、surface销毁、窗口detach、配置/尺寸/旋转、用户/壁纸/AOD切换、OFF/SUSPEND/移除或未知失败均停止局部呈现并请求原生重算。surface挂载后重新安排首swap确认，不复用被attach释放掉的旧回调。ABI/deopt安装失败撤销本组Hook，能力Flow与doze blur独立回退。无首swap则不clip，重复命令/回执不延长绝限，不能解锁后补开视觉scope。

这里控制的是自有Alive在一段安全睡眠窗口内的**壁纸可见与局部LightReveal绘制**，并未替换所有原生锁屏/熄屏动画，也未照搬OpenAlive的ColorOS专用屏幕/CPU窗口或全包权限例外。系统仍控制软件退出、认证、功耗/接近保护和最终DOZE；新交接尚未真机验证。

## 验证范围

目标ABI依据归档DEX；145只读现场不是146 Handoff实际生效或普遍连续性的证明。按用户少测试要求仅保留必要启动/Hook失败恢复、生命周期与构建/R8/原签名/身份/资源/产品边界核验，由Lead收口；本文不预报执行中的PASS/hash，不跑full suite或全功能回归，不自动安装、重启或写设置。

软件内与桌面熄屏交接、组件/用户/AOD/配置切换回退、省电/遮挡、指纹直达HOME、球体色块与补播修正仍交用户真机验证。照片burn-in位移未做、时钟避让延期，限短时测试；构建、单测及全屏预览不能代替物理DOZE/GPU/视觉验证，不称所有原生动画已替换、全面连续/无闪或其他ROM兼容已通过。
