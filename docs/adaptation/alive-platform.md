# Alive：阶段事实、AOD 实验与 Android 壁纸边界

全部现有功能解耦推送至 `5ee8e95` 后才开始 Alive。0.11.0/code140 的普通照片壁纸不具备真正 AOD；最新用户另授权仅自有 Alive 组件所需的模块绑定检查例外，0.11.1-aod-test1/code141 据此增加实验 AOD 接入。code142 后用户重新连接设备，只读抓取及一次人工重试已确认 AOD 照片可见、亮屏动画正常，用户指出缺的是熄屏收拢过渡。code143 修复下述有限睡眠窗口，**新包的过渡效果尚未真机验证**。独立小窗版不包含 Alive。

## 两个明确选择的组件

- [AliveWallpaperService](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 是保留的普通壁纸回退，metadata `supportsAmbientMode=false`，`supportsOwnAmbient()` 默认 false。
- [AliveAmbientWallpaperService](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>) 复用同一 Engine，仅覆盖 `supportsOwnAmbient()` 为 true；单独 metadata 请求 ambient，并由用户明确选择。普通应用仅声明 true 仍会被系统签名级绑定检查拒绝；本模块的例外必须局限于该精确组件，不是全局 grant，也不授予应用其他签名权限。绑定适配由系统侧任务完成，Engine 不实现权限绕过。

两者均由原生壁纸选择器设置，不静默更换壁纸；切回普通组件使用原生普通壁纸路径。manifest、选择入口、系统绑定例外与 SystemUI 能力 Flow 属各自适配 scope，不在 Engine 内重复维护一套设置。

## 平台事实 → 纯阶段与绘制门控

[AlivePhase](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java>) 不依赖 Android 对象；阶段函数为纯值规则，新增 `SleepWindow` 只持有由 Engine 注入 uptime 的本次睡眠截止状态：

| 当前事实 | 场景目标 |
| --- | --- |
| 所属显示器非 ON，或系统不可交互，或真实 ambient 回调为 true | `AMBIENT = 0` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 当前锁定 | `LOCK = 1` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 当前未锁定 | `HOME = 2` |

Engine 将 `interactive && ownDisplay == ON` 作为阶段函数的可交互输入，避免其他显示器处于交互状态时把本显示器 DOZE 判为 HOME。唤醒的回调顺序若尚未退出 ambient，保留场景 0，直到新的真实事实到达；不把 wakingup 命令当作认证成功。`AMBIENT` 仅是场景编号，不是显示器已经可见 AOD 的证明。

独立绘制门控接收值事实 `ownAmbient / nativeVisible / interactive / ambient / DisplayState`：

| 所属显示器状态 | 允许条件与行为 |
| --- | --- |
| ON | 原生 visible，且可交互；另仅实验组件在真实 sleep 信号后的 1800ms 有限窗口内可在非交互时播放已有收拢转场 |
| DOZE | 仅实验 ambient 服务、真实 ambient=true、原生 visible；允许静态端点或有限转场 |
| DOZE_SUSPEND | 同 DOZE 门控，但强制 `mode(..., false)` 完成立即端点，不维持动画帧循环 |
| OFF／UNKNOWN／显示器已移除 | 停绘，不能用 ambient=true 绕过 |

Engine 从 `getDisplayContext().getDisplay()` 捕获所属显示器 ID，仅响应这个 ID 的 DisplayManager 事件，不按默认显示器猜测。显示器移除锁定为 UNKNOWN，不让后来同 ID 的显示器复活旧 Engine。listener 注册在创建时、销毁时注销；SCREEN_ON/OFF/USER_PRESENT 非导出 receiver、偏好监听与延后重新采样也在销毁时撤回。

原生 `onAmbientModeChanged` 的 `duration <= 0` 即使目标场景相同也发送不动画模式，依赖 renderer 完成现有 motion、frame 和 texture 转场，而不是同 target 直接丢弃。进入 DOZE_SUSPEND 同样如此；renderer 只提交必要端点，稳定后停止调度，不加 wake lock、不请求亮屏、不做周期轮询。OFF/UNKNOWN/移除时释放自身 GL/native 资源；隐藏必须立即取消自有帧调度，不绘制、不初始化。只有上述有限睡眠窗口内允许暂留已有 scene，使原生短暂隐藏恢复时可沿真实时间继续已有曲线；不重造 HOME，不热重放假起点。到期强制当前端点并解除保留；重复 sleep 信号不续期，唤醒取消截止回调。最长现有玻璃曲线 1500ms，1800ms 含有界派发余量；不是常亮或无限动画。普通生命周期释放后仍不承诺逐帧连续。

## 原生命令与安全边界

`android.wallpaper.goingtosleep` 启动有限 sleep 窗口、`android.wallpaper.wakingup` 取消；四个 `android.wallpaper.goingtosleep`、`android.wallpaper.wakingup`、`android.wallpaper.keyguardgoingaway`、`android.wallpaper.keyguardappearing` 均触发当前事实重采样；唤醒/锁屏显隐命令、SCREEN_ON/USER_PRESENT、原生 visible 恢复与 ambient 退出在可见交互 ON 时去重触发 100/300/600/1000ms 四次有界复核。每次仍读取真实 Keyguard，不凭命令直接设 HOME；睡眠、隐藏、ambient、非交互、非ON与销毁取消未执行复核，不使用永久轮询或申请签名级 locked-state listener 权限。SCREEN_OFF、真实交互下降边沿或有时长的 ambient 进入亦可开始本次窗口，重复信号不续期；不是依赖必然收到签名命令。后两项是全部小写命令名，投递受平台签名权限限制；缺少回调不视为安装错误。取消解锁／重新锁定仍按当前 Keyguard，不保存粘性 HOME，也不增加自定义解锁广播。

- 不 Hook 认证、指纹、人脸或锁屏放行，不修改密码／生物认证策略；Keyguard 只读。
- 不强亮屏、不全局改 scrim、不改 AOD 用户设置、省电或接近传感器保护。暗光不等于遮挡；已读日志中 NEAR 约 0.2 秒即 FAR 会取消原生 10 秒暂停，不能据此判定保护失效，更不缩短延迟或禁用保护。
- SystemUI 仅当前用户实际锁屏精确选择实验组件时组合原生能力 Flow，保留原生 AOD 设置、省电与遮挡门控；不写 readonly Flow。此 Engine 自身只消费原生 ambient、visibility 与 display 事实。
- `shouldZoomOutWallpaper()` 保持 false，不 Hook Launcher 或改 DPI。`BIND_WALLPAPER` 是保护系统绑定者的服务权限，不是应用自动持有签名能力。
- UI／Engine 不进行素材 hash、图片解码、磁盘或 GL 操作；renderer worker 执行既有资源读取及渲染。主线程只采样平台值并投递状态。

## 最小证据与待验证

复用 full-only [AlivePhaseTest](<../../app/src/testFull/java/dev/local/sunwindowdemo/features/alive/AlivePhaseTest.java>)，保留八种阶段事实及熄屏→锁屏→主屏→重锁检查，在同一用例补绘制门控及睡眠窗口到期/防续期/唤醒取消：普通组件不得 DOZE 绘、ambient 服务也必须可见、OFF 始终拒绝。[现有绑定检查](<../../app/src/testFull/java/dev/local/sunwindowdemo/features/alive/AliveAmbientAccessTest.java>)另加一项锁屏0/桌面6和三阶段全零纹理包检查，防无选择又启用玻璃；未新增测试套件或依赖。

code143 由 Lead 统一执行上述两类最小定向检查并通过；未安装新包或写设备设置。纯规则、编译和归档签名检查不能证明真机 AOD 可见、遮挡恢复、DOZE_SUSPEND 端点提交、锁屏动画连续性、多显示器行为或其他 ROM 兼容。用户仍需自行安装、选择实验组件并验证，普通组件留作回退。
