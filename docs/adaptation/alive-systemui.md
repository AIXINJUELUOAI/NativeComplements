# Alive 实验 AOD：SystemUI 能力 Flow 的窄投影

此为用户追加授权的模块例外，不是普通应用获得签名权限，也不宣称真机 AOD 已验证。仅主版安装 [AliveSystemUi](../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemUi.java)。用户在原生壁纸选择器明确选择 `dev.nativecomplements.module/dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService` 才可能产生额外能力；普通 Alive 壁纸仍为原生 ambient=false 回退。无需另存全局 enable 偏好或新增 Provider。

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

1. native 值为 true 时原样返回，不新增 Binder 查询，不改变其他壁纸的原生能力。
2. native 为 false 时，当前 SelectedUserModel 必须仍是 StateFlow 的同一对象且 `selectionStatus == SELECTION_COMPLETE`，用户 ID 必须有效。用户切换中不添加能力。
3. 通过宿主 `UserSettingsProxy.getIntForUser(int defaultValue, int userId, String key)` 读取该用户 `doze_always_on == 1`，不写系统设置；该 APK 参数顺序已按 DEX 指令核对。
4. 在 native bgDispatcher 上重新查询 `WallpaperManager.getWallpaperIdForUser(which,userId)` 与 `getWallpaperInfo(which,userId)`，不把跨用户期间可能仍旧的 wallpaperInfo 缓存直接当授权。仅锁屏组件精确匹配实验服务、实际 metadata `supportsAmbientMode()` 为 true 时允许额外 true。
5. 独立静态锁屏的 info 也为 null，**不能直接回退主屏**。仅 LOCK ID 为负（没有单独配置）且 info 为 null 才查询 SYSTEM；ID 为 0/不一致、独立静态锁屏、未知异常均不添加能力。成功返回前再次核验实际壁纸 ID、LOCK ID、AOD 设置与当前 SelectedUserModel，降低查询过程切换造成的旧值复用。

读取到明确新组件后，切普通 Alive/其他壁纸、设独立静态锁屏、切用户或关 AOD，额外分支均恢复 native 结果。恢复是原生事件流的异步传播，不承诺跨进程事务级零延迟；系统原生壁纸/Doze 生命周期继续决定真实显示。

## ABI 与失败恢复

运行时精确预检构造、字段类型、host FlowKt 的四流 combine(Function5)/flowOn、host StateFlow.getValue、SelectedUserModel/UserInfo/SelectionStatus、设置 getter、两个 per-user WallpaperManager getter 与 WallpaperInfo.supportsAmbientMode。缺项不安装；constructor deopt 失败撤销 hook。单实例投影构建失败保持原 field，运行期间任何额外分支读取失败返回该次 native 值，不改变只读状态。当前构造期替换不用于热修已创建的 repository，模块由正常 SystemUI 启动安装。

本机 Android 36 SDK 源码可核 `getWallpaperIdForUser(int which,int userId):int` 的负值缺省语义以及 `getWallpaperInfo(int which,int userId):WallpaperInfo`；实际运行框架仍逐项预检，SDK 源码不是当前设备实现证据。

不 Hook LightRevealScrim、不强改 scrim alpha，不打开全局 AOD 资源，不绕过原生 `DozeParameters.getAlwaysOn`、省电、近距离遮挡及显示器暂停。这里只提供自有组件的能力投影；system_server 的专用绑定例外由另一适配边界负责，认证/解锁策略始终不在本任务范围。

## 验证范围

本任务仅进行了源代码/归档 DEX 读取与参数语义核对，未运行构建、测试、设备操作或 Git 写操作。Lead 负责集成入口、最小检查及实验包核验。最终必须由用户真机验证组件选择、用户切换/静态锁屏恢复、AOD 开关、省电/遮挡保护和实际 ambient→锁屏→桌面效果；这些不能由编译通过替代。
