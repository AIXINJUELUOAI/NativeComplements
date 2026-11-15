# Alive 首版：阶段事实与 Android 壁纸边界

全部现有功能解耦已推送至 `5ee8e95` 后才开始本功能。首版仅主版通过普通 `WallpaperService` 接入，独立小窗版不增加壁纸服务或资源。用户通过系统动态壁纸选择器设置，不静默替换当前壁纸，不新增系统 Hook 或认证权限。

## 事实 → 场景目标

[AlivePhase](../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java) 是无 Android 对象、无时钟与无可变状态的阶段规则：

| 当前平台事实 | 目标 |
| --- | --- |
| 不可交互，或真实 ambient 回调状态为 true | `AMBIENT = 0` |
| 可交互、非 ambient、Keyguard 当前锁定 | `LOCK = 1` |
| 可交互、非 ambient、Keyguard 当前未锁定 | `HOME = 2` |

`AMBIENT` 是素材场景编号，不是显示器处于可见 AOD 的证明。普通熄屏也选场景 0，但 surface 原生不可见时停止帧调度；不把屏幕关闭后的一张目标画面称为真正 AOD 动画。`interactive`、ambient、Keyguard 和当前显示器状态由 Engine 采样，规则不猜解锁动画是否会成功。

原生 `onCommand` 收到的 `android.wallpaper.goingtosleep`、`android.wallpaper.wakingup`、`android.wallpaper.keyguardgoingaway`、`android.wallpaper.keyguardappearing` 只触发 Engine 重新采样，不在规则中永久记录 HOME/LOCK。后两项是全部小写命令名；平台发送受签名权限限制，普通壁纸不能期待收到，缺少回调不视为安装错误。尤其 `keyguardgoingaway` 不等于认证完成：取消解锁或后续更新仍以真实 Keyguard 状态决定阶段。

Engine 可监听系统保护的 `SCREEN_OFF`、`SCREEN_ON`、`USER_PRESENT` 广播作为重新采样时机，并采用非导出 receiver；不增加可供普通应用发送的自定义解锁广播，不根据广播名称直接置 HOME。原生 `onVisibilityChanged`、surface 生命周期和当前显示器状态仍决定是否绘制，阶段不代替可见性。renderer 场景仍存活时，阶段切换或反向可从当前动画值继续；首版在隐藏或熄屏时回收原生场景与 GL 资源，恢复可见后重新建立 wake session，已观察到熄屏阶段的唤醒会以场景 0 为 seed 展开。快速灭亮也可能经过该回收路径，不承诺跨不可见周期保持动画 current 连续；实际画面衔接需用户真机验证。

## 必须遵守的平台限制

- 普通应用不能声明 `supportsAmbientMode=true` 规避限制：系统对支持 ambient 的壁纸执行签名级权限检查，未经授权会拒绝绑定。首版壁纸 metadata 保持 **false**。`onAmbientModeChanged` 的签名正确也不赋予支持 AOD 的权限；本版不保证任何 OEM 的 AOD 壁纸可见或回调投递。
- 首版不要求系统认证能力，不 Hook Keyguard、指纹、人脸或锁屏放行，不修改密码/生物认证策略；普通 `KeyguardManager.isKeyguardLocked()` 仅作为场景事实。
- 原生 `shouldZoomOutWallpaper()` 默认返回 false 是平台行为，不是待修故障。保留原生缩放契约，不以“修复 zoom”为由 Hook Launcher/系统壁纸宿主、改 DPI 或假造认证进度。
- WallpaperService 的 manifest 绑定权限用于要求系统绑定者具有 `BIND_WALLPAPER`，不等于应用持有它或获得签名级 ambient/Keyguard 命令权限。系统锁屏/主屏是否共同展示及裁剪仍由系统壁纸宿主决定。

## 最小检查与未验证项

仅增加一个 full-only 的 [AlivePhaseTest](../../app/src/testFull/java/dev/local/sunwindowdemo/features/alive/AlivePhaseTest.java)，覆盖八种事实组合和熄屏→锁屏→主屏→重新锁屏，无粘性解锁状态。测试放在 AGP `full` flavor 对应的 `testFull` 源集，避免独立版引用仅主版存在的类；不增加测试套件或新的测试依赖。

本规则/文档任务未执行构建、测试或设备操作。Engine、renderer、manifest 与素材的集成由各自 scope 完成后由 Lead 做必要核验；编译与纯规则检查不能证明真机壁纸选择、锁屏动画连续性、显示器可见性、AOD 或其他 ROM 兼容。设备安装与选择由用户操作。
