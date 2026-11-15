# Alive：阶段事实、AOD 实验与 Android 壁纸边界

全部现有功能解耦推送至 `5ee8e95` 后才开始 Alive。0.11.0/code140 的普通照片壁纸不具备真正 AOD；最新用户另授权仅自有 Alive 组件所需的模块绑定检查例外，0.11.1-aod-test1/code141 据此增加实验 AOD 接入。手机已断开，本轮离线开发，**尚未真机验证**。独立小窗版不包含 Alive。

## 两个明确选择的组件

- [AliveWallpaperService](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 是保留的普通壁纸回退，metadata `supportsAmbientMode=false`，`supportsOwnAmbient()` 默认 false。
- [AliveAmbientWallpaperService](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>) 复用同一 Engine，仅覆盖 `supportsOwnAmbient()` 为 true；单独 metadata 请求 ambient，并由用户明确选择。普通应用仅声明 true 仍会被系统签名级绑定检查拒绝；本模块的例外必须局限于该精确组件，不是全局 grant，也不授予应用其他签名权限。绑定适配由系统侧任务完成，Engine 不实现权限绕过。

两者均由原生壁纸选择器设置，不静默更换壁纸；切回普通组件使用原生普通壁纸路径。manifest、选择入口、系统绑定例外与 SystemUI 能力 Flow 属各自适配 scope，不在 Engine 内重复维护一套设置。

## 平台事实 → 纯阶段与绘制门控

[AlivePhase](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java>) 无 Android 对象、时钟或可变状态：

| 当前事实 | 场景目标 |
| --- | --- |
| 所属显示器非 ON，或系统不可交互，或真实 ambient 回调为 true | `AMBIENT = 0` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 当前锁定 | `LOCK = 1` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 当前未锁定 | `HOME = 2` |

Engine 将 `interactive && ownDisplay == ON` 作为阶段函数的可交互输入，避免其他显示器处于交互状态时把本显示器 DOZE 判为 HOME。唤醒的回调顺序若尚未退出 ambient，保留场景 0，直到新的真实事实到达；不把 wakingup 命令当作认证成功。`AMBIENT` 仅是场景编号，不是显示器已经可见 AOD 的证明。

独立绘制门控接收值事实 `ownAmbient / nativeVisible / interactive / ambient / DisplayState`：

| 所属显示器状态 | 允许条件与行为 |
| --- | --- |
| ON | 原生 visible 且系统可交互；正常有限转场 |
| DOZE | 仅实验 ambient 服务、真实 ambient=true、原生 visible；允许静态端点或有限转场 |
| DOZE_SUSPEND | 同 DOZE 门控，但强制 `mode(..., false)` 完成立即端点，不维持动画帧循环 |
| OFF／UNKNOWN／显示器已移除 | 停绘，不能用 ambient=true 绕过 |

Engine 从 `getDisplayContext().getDisplay()` 捕获所属显示器 ID，仅响应这个 ID 的 DisplayManager 事件，不按默认显示器猜测。显示器移除锁定为 UNKNOWN，不让后来同 ID 的显示器复活旧 Engine。listener 注册在创建时、销毁时注销；SCREEN_ON/OFF/USER_PRESENT 非导出 receiver、偏好监听与延后重新采样也在销毁时撤回。

原生 `onAmbientModeChanged` 的 `duration <= 0` 即使目标场景相同也发送不动画模式，依赖 renderer 完成现有 motion、frame 和 texture 转场，而不是同 target 直接丢弃。进入 DOZE_SUSPEND 同样如此；renderer 只提交必要端点，稳定后停止调度，不加 wake lock、不请求亮屏、不做周期轮询。引擎隐藏或 OFF 时释放自身 GL/native 资源；恢复可见重新建立 wake session，不承诺跨资源回收保持逐帧 current 连续。

## 原生命令与安全边界

`android.wallpaper.goingtosleep`、`android.wallpaper.wakingup`、`android.wallpaper.keyguardgoingaway`、`android.wallpaper.keyguardappearing` 仅触发当前事实重采样和一次 100ms 后复核。后两项是全部小写命令名，投递受平台签名权限限制；缺少回调不视为安装错误。取消解锁／重新锁定仍按当前 Keyguard，不保存粘性 HOME，也不增加自定义解锁广播。

- 不 Hook 认证、指纹、人脸或锁屏放行，不修改密码／生物认证策略；Keyguard 只读。
- 不强亮屏、不全局改 scrim、不改 AOD 用户设置、省电或接近传感器保护。暗光不等于遮挡；已读日志中 NEAR 约 0.2 秒即 FAR 会取消原生 10 秒暂停，不能据此判定保护失效，更不缩短延迟或禁用保护。
- SystemUI 仅当前用户实际锁屏精确选择实验组件时组合原生能力 Flow，保留原生 AOD 设置、省电与遮挡门控；不写 readonly Flow。此 Engine 自身只消费原生 ambient、visibility 与 display 事实。
- `shouldZoomOutWallpaper()` 保持 false，不 Hook Launcher 或改 DPI。`BIND_WALLPAPER` 是保护系统绑定者的服务权限，不是应用自动持有签名能力。
- UI／Engine 不进行素材 hash、图片解码、磁盘或 GL 操作；renderer worker 执行既有资源读取及渲染。主线程只采样平台值并投递状态。

## 最小证据与待验证

复用唯一 full-only [AlivePhaseTest](<../../app/src/testFull/java/dev/local/sunwindowdemo/features/alive/AlivePhaseTest.java>)，保留八种阶段事实及熄屏→锁屏→主屏→重锁检查，在同一用例补绘制事实组合：普通组件不得 DOZE 绘、ambient 服务也必须可见、OFF 始终拒绝。没有新增测试套件或依赖。

本任务未运行构建、测试或设备命令；必要集成核验由 Lead 统一执行。纯规则、编译和归档签名检查不能证明真机 AOD 可见、遮挡恢复、DOZE_SUSPEND 端点提交、锁屏动画连续性、多显示器行为或其他 ROM 兼容。用户仍需自行安装、选择实验组件并验证，普通组件留作回退。
