# Alive 实验 AOD：SystemUI 能力、doze 模糊与额外淡出的窄投影

当前为0.11.2-alive-test2/code145。此为用户追加授权的模块例外，不是普通应用获得签名权限，也不宣称本轮真机 AOD 已验证。仅主版安装 [AliveSystemUi](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemUi.java>)。用户明确选择 `dev.nativecomplements.module/dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService` 才可能产生额外能力；普通 Alive 壁纸仍为原生 ambient=false 回退。无需另存全局 enable 偏好或新增 Provider。

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

## code145：保留原生目标暗度，仅跳一次额外淡出

[自然操作日志](<../../verification/alive-live-20261116-1400/manual-transition-logcat.txt>)已定位code144在动画窗口结束、显示器仍ON时释放native scene，1.913秒后DOZE才重建的断层；Engine现用同7800ms绝限静态保留已呈现端点，见 [平台交接](<alive-platform.md>)。这不等于系统遮挡也已统一，故新增独立的LightReveal binder窄投影。

目标为已核 `LightRevealScrimViewBinder$bind$1$1$2$1.emit(Object,Continuation)`，只把原生 host `Pair(nativeTarget,true)`替换为 **`Pair(同一个nativeTarget,false)`**，让原生binder立即提交自身暗度目标，跳过其额外333ms等待与733ms淡出。保留原生0.4/0.64等目标，不写alpha=0、不硬编码新暗度，不改原生1300ms screen-off controller、LightReveal几何、电源或认证。

门控必须全部成立：view所属默认显示器且ON/DOZE、非交互、后台已实查的精确自有实验锁屏组件/当前完成选择用户/AOD匹配仍有效；只在binder主线程收集中识别原生target=1开启一次identity-scoped episode，期限≤7800ms。随后0<target<1且原生animate=true时，还需真实KeyguardStateController showing、未occluded/goingAway/bouncer/fadingAway；user/lock/home对象身份仍相同才投影。该次分支无论安全与否都消耗token，不延后解锁后补播；唤醒、其他target、用户/组件失配与读取失败清理episode，过期不重放。不是逐帧hook，也不在binder里新增Binder查询。

精确ABI/类型/emit返回值及deopt失败撤销这一组新增hook，运行异常保原生参数；不影响能力Flow与doze blur各自的失败回退。不打开全局AOD资源，不绕过 `DozeParameters.getAlwaysOn`、省电、接近保护或显示器暂停。system_server专用绑定例外仍在另一边界，认证/解锁策略不改；不能再用“完全不Hook LightReveal binder”描述本轮，也不能把本次局部投影称接管整套系统熄屏动画。

## 验证范围

目标ABI依据归档DEX；code145能力/blur/额外淡出源码和失败回退不等于实际生效或普遍连续性验证。按用户少测试要求，本轮仅Phase/目标相关必要检查与R8/原签名/身份/资源及产品边界，由Lead收口；本文不预报仍在执行的PASS、hash或交付状态，不跑full suite/全功能回归，不自动安装、重启或写设备设置。

实际组件/用户切换、静态锁屏恢复、AOD开关、省电/遮挡、一次性淡出门控、指纹直接AOD→HOME及其他blur保留仍需用户验证。照片burn-in位移未做、时钟避让延期，限短时测试；构建、单测和全屏真实预览不能代替物理DOZE、GPU或视觉验证，不称全面连续/无闪/其他ROM兼容已通过。
