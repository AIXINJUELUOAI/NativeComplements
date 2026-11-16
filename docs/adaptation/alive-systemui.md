# Alive SystemUI 能力与有界视觉交接合同

[AliveSystemUi](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemUi.java>)只对用户实际选中的精确 ambient Alive 锁屏组件增加局部能力。当前行为保持 code165/166 运行合同；ROM署名不参与授权。普通壁纸/普通 Alive 保原生。绑定签名检查例外独立见[绑定合同](<alive-module.md>)，数据与生命周期见[平台合同](<alive-platform.md>)、[渲染合同](<alive-renderer.md>)。

## 宿主能力投影

精确预检 `WallpaperRepositoryImpl` 构造与字段、宿主 FlowKt 四流 combine(Function5)/flowOn、StateFlow 用户模型、设置 getter及 per-user WallpaperManager 接口。构造先运行原生，再仅替换新 repository 的 cold `wallpaperSupportsAmbientMode: Flow` 投影：

```
combine(nativeAmbient, lockscreenWallpaperInfo, wallpaperInfo, selectedUser, hostFunction5)
    .flowOn(nativeBackgroundDispatcher)
```

Kotlin/coroutines/代理均来自宿主 ClassLoader，不混入模块 Flow，不写 readonly StateFlow、共享发射值或全局资源；沿用宿主收集/取消生命周期。

额外能力须用户选择完成、本用户 `doze_always_on==1`，后台实查锁屏壁纸ID/info/完整组件及真实 ambient metadata。独立静态锁屏不能回退主屏；仅 LOCK ID负值且 info为空才允许查 SYSTEM。返回前再验壁纸ID、AOD与选中用户身份。其他壁纸始终保 native 结果，读取失败不添加能力。

opt-in 是实际选用 ambient 组件，不额外以 `doze_always_on_wallpaper_enabled` 作模块开关；本路径不写这两个设置。用户/AOD/组件变化由原生 Flow 撤销额外分支，非跨进程事务级零延迟。constructor/deopt失败撤本组Hook；单实例投影失败保原 field，不能热修已创建实例。

## doze 模糊贡献

只标记 `NotificationShadeDepthController` 两个已预检 producer 的同步 ThreadLocal 范围：`statusBarStateCallback.onDozeAmountChanged`及 classId1能力 collector的 `emit`。仅同 owner、ambient能力 true、doze量>0、当前自有 MatchToken仍有效时，把其 `setWakeAndUnlockBlurRadius` 本次贡献投影为0。

setter内不新增Binder查询；认证动画、QS、brightness mirror、应用启动等其他贡献保持原计算。读取失败保 native 参数；三处Hook/deopt失败只撤该组。不是全局setter清零、资源min/max修改或所有样式去模糊。

## 控制传输与回执

[AliveHandoffTransport](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveHandoffTransport.java>)采用显式本包、`shareIdentity`广播传送 PREPARE_SLEEP/PREPARE_WAKE/CANCEL。仅已存在非preview Engine动态注册，验同用户 SystemUI实际UID及精确包名；不唤醒/创建Engine，不授权限。公开 wallpaper `onCommand`只保原生事件，不能接入自有控制。**自有控制不再经依赖 wallpaperTarget 的 WallpaperCommand。**

[AliveSleepHandoff](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSleepHandoff.java>)每episode创建随机nonce、实际Engine UID、default display及固定 uptime期限。消息结构校验不是发送者认证的替代。raw Binder回执核实际调用UID、interface descriptor、nonce、display0、完整Parcel且无多余数据，主线程再验当前episode/期限；状态0取消、1首swap、2真实端点swap。旧nonce/旧render epoch或重复回执不重开内容门、不续期限。

Engine只有自有ambient/AOD开启、非preview、未销毁、native visible、default display、有效Surface、对应交互/显示状态下的真实 `eglSwapBuffers` 成功才能确认。sleep可在ON，或真实ambient且已成功sleep的DOZE确认；wake须ON/interactive。成功swap不等于物理屏幕已经显示。

## 睡眠与唤醒边界

- sleep在原系统睡眠回调前建立一次episode，首信号起绝限7800ms；重复不续期。wake独立PREPARE_WAKE及2500ms绝限，不复用sleep nonce。
- MatchToken包含实际选中用户/组件/AOD/壁纸身份；只在default display附着的同native shade window/root工作。occlusion、bouncer、用户/组件/设置失配、配置/尺寸/旋转变化、detach、超时与未知异常撤回。
- sleep `applyWindowLayoutParams`提交期间临时OR `FLAG_SHOW_WALLPAPER`，原方法继续，随后恢复原pending flags。当前 `beforeLayout`使用非interactive安全门，不要求已showing；不把该早期布局资格当作壁纸首帧/认证证明。wake不强加此flag。
- `ScreenOffAnimationController`保原动画循环/Fold候选；仅同episode、精确native窗口/动画实例、默认ON/非交互、无遮挡/认证过渡且原生尚未动画/decision为空时，在原 `UnlockedScreenOffAnimationController.startAnimation`同步范围内将原true谓词投影false。由原start写自身FALSEcache，不手写认证/电源状态、不global禁动画。取消后恢复绘制/布局，不重放已被原生选择不播放的旧动画。
- 同root `LightRevealScrim.onDraw`仅在当前视觉安全门及已确认帧后临时空clip，仍调用原方法并finally还原Canvas。保原alpha、opaque/reveal/controller、animator及亮度/电源。既有已呈现sleep视觉可有条件继承到wake，随后仍受新episode的身份/状态/期限约束。
- wake只控制纯装饰输出，不要求locked/双showing/非going/fading；真实指纹可AOD→GONE→应用，Engine按真实锁定选择LOCK/HOME。occlusion/bouncer/用户/AOD/root/display等门仍在；不修改认证、输入或强开应用。wake端点swap且原生reveal到1才撤门，仍受2500ms上限，不按通常动画时长猜测完成。

## 精确内容绘制与恢复

[AliveVisualContent](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveVisualContent.java>)只在sleep安全非interactive门内抑制精确clockface小/大View与通知目标，不等待首帧ACK或双showing；这不提前透出未确认壁纸。捕获后刷新gate并invalidate父/child，防重用View/父DisplayList漏刷新。

父必须走已核基类 `ViewGroup.drawChild`，未知override/布局目标保持原生。空clip仍原链恰一次/finally恢复，不写时钟doze/tick、通知多贡献alpha、visibility，不藏整root/UDFPS/auth。sleep内容抑制最多2000ms，首帧ready后最多再等250ms，端点完成可更早释放；正常sleep释放以300ms局部绘制淡入恢复，wake/cancel/失败立即恢复。wake时钟/通知仍原生，不是自制Alive时钟。

仅同root有界视觉期裁指纹装饰涟漪，不改UDFPS输入/认证。无帧确认不启用新的壁纸clip；renderer失败、Surface销毁、实际隐藏超150ms grace等取消交接并请求原生重算。隐藏idle保留与睡眠起始预绘的资源取舍见[渲染合同](<alive-renderer.md>)，不能沿用“所有隐藏路径绝不初始化/绘制”的断言。

## 失败、诊断与非目标

各组精确ABI/deopt预检、保护执行，失败撤本组Hook并保原生，能力/blur/handoff/只读诊断互不冒充成功。`debug.nc.alive_native=1`在SystemUI启动时读一次，仅跳过handoff/doze blur，保能力投影；模块不自动写此属性。

[AliveTransitionTrace](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveTransitionTrace.java>)只在同一有界episode记录白名单转场/数值边沿，独立随机诊断短标识，不输出nonce/Binder凭证，不逐帧、不记录通知内容或截图，不网络上传；后台事件转主线程后重验身份/时效。诊断失败不改变原参数/Flow或交接。

不改系统认证、root/UDFPS、输入、电源、亮度或接近保护，不加wake lock；开发者工具显式设置操作不属于自动壁纸路径。无照片防烧屏位移、无动态时钟避让。主机/打包检查不证明硬件裁剪、真实Hook命中、GPU/功耗或全ROM视觉验收，不声称全部原生像素已替换或零闪。
