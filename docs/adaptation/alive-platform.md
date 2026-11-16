# Alive：阶段事实、AOD 实验与 Android 壁纸边界

## 当前实现：0.11.2-alive-test1 / code144

全部现有功能解耦推送至 `5ee8e95` 后才开始 Alive。code140 仅普通照片壁纸；code141 增加自有组件绑定例外与实验 AOD；code142 用户已反馈 AOD 照片可见、亮屏正常。code143 真机日志随后证实固定 1800ms 窗口早于 native visibility/DOZE，出现静态重建，不能沿用其“已修复”表述。code144 改为下面的可见交接与首帧确认，**不是替代系统熄屏动画，也尚未真机验证无闪或连续性**。独立小窗版不含 Alive。

## 明确选择的组件与配置

- [普通服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 保留 `supportsAmbientMode=false`，作为原生普通壁纸回退。
- [实验服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>) 复用 Engine，只覆盖自身 ambient 能力；系统绑定例外只匹配该精确组件，不是全局 grant，也不授予其他签名权限。
- [AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>) 分离 `alive_wallpaper_draft` 与 `alive_wallpaper` 已应用快照。照片使用不可变 UUID 文件名，编辑不覆盖当前照片；旧 `style` 1..5 与旧照片仍可读取，新编辑默认启航0。完整 aod/lock/home/photo 在一次偏好提交发布。
- [编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>) 只编辑草稿；同一目标组件已选且无普通/实验组件混用时，由页面显式确认提交。否则打开原生选择器，仅返回成功且核实目标组件后提交；取消不提交草稿。不静默设置壁纸，不把每次应用都描述成必经系统选择器。

## 平台事实 → 阶段与绘制门控

[AlivePhase](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java>) 不依赖 Android 对象；Engine 仅采样当前所属显示器、交互、ambient、真实 Keyguard 状态。

| 当前事实 | 场景目标 |
| --- | --- |
| 所属显示器非 ON，或不可交互，或真实 ambient=true | `AMBIENT=0` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 锁定 | `LOCK=1` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 未锁定 | `HOME=2` |

`AMBIENT` 是场景编号，不证明系统已呈现 AOD。睡眠交接期间目标为0；缺少既有场景时，只能以最近真实采样的 LOCK/HOME 作为起点，不捏造 HOME；没有真实种子则静态端点。

| 显示器状态 | 允许条件 |
| --- | --- |
| ON | 原生 visible，且可交互；实验组件另允许本次有界 sleep 交接 |
| DOZE | 实验组件、真实 ambient=true、原生 visible |
| DOZE_SUSPEND | 同 DOZE，但立即完成端点，不维持动画循环 |
| OFF／UNKNOWN／已移除 | 停绘并结束 sleep，ambient=true 不能绕过 |

Engine 从 `getDisplayContext().getDisplay()` 捕获所属显示器，仅响应同 ID；移除后锁定 UNKNOWN，不能被重用 ID 复活。创建时注册显示器/非导出屏幕 receiver/偏好监听，销毁时注销并取消全部延迟任务。

## code144 有界睡眠交接

1. `goingtosleep`、SCREEN_OFF、真实交互下降边沿或有时长的 ambient 进入开始本次 episode，重复信号不续期。先等待原生 visibility，最多 **6000ms**；隐藏不绘制、不初始化。
2. 可绘时向 renderer 发一次 sleep 请求；先呈现完整解码的起始帧，**成功首 swap 的 ack** 才启动收拢和 **1800ms** 动画窗口，不把照片/遮罩加载时间算作已播放。
3. 临时隐藏仅保留已有 scene，暂停公共、纹理、照片三轨并停止帧调度；恢复再续。暂停不无限延长：从初始信号起绝对上限 **7800ms**，窗口到期结束保留。ack 使用 episode generation，旧回调不能重启已唤醒/终止的 sleep。
4. 唤醒取消 sleep；进入 ambient 的 `duration<=0`、DOZE_SUSPEND 强制端点，OFF/UNKNOWN/移除停止。**退出 ambient 即使 duration=0 仍按真实 awake 事实播放展开**，不能把该 duration 当作跳过几何动画的指令。本轮已定位并修正指纹直接 AOD→HOME 跳过展开的路径，但尚未真机复测。

上述窗口是自有资源与动画上限，不修改原生 screen-off controller、LightRevealScrim、显示器电源或亮度。系统仍可能遮住自有过渡；不承诺完全替代系统熄屏或全部无闪。

## 唤醒复核与安全边界

原生 sleep/wake/keyguard 命令只触发事实重采样，不代表认证成功。SCREEN_ON/USER_PRESENT、可见恢复、ambient 退出在可见交互 ON 时最多安排100/300/600/1000ms四次去重复核；睡眠、隐藏、ambient、非交互、非ON及销毁取消。不永久轮询，不申请签名级 locked-state listener，不保存粘性 HOME。

- 不 Hook 认证/指纹/人脸放行，不写 AOD 开关，不强亮屏、不加 wake lock、不禁省电/接近传感器保护。
- [SystemUI 适配](<alive-systemui.md>) 仅当前用户实际锁屏精确实验组件时投影能力，并只消除两个原生 doze producer 的该项模糊贡献；认证、QS 与其他原生模糊保留。
- 磁盘、照片解码与 GL 在相应 worker；主线程采样平台事实。`shouldZoomOutWallpaper()` 仍为 false，不改 Launcher、DPI 或时钟布局。
- 照片防烧屏位移未做，时钟避让/位置微调延期。系统时钟移动不代表照片也移动，只宜短时测试。

## 检查与证据限制

按用户少测试要求复用现有检查，不新增套件、不重跑全功能回归。Lead 已完成一轮双 flavor unit 检查；**其后新增首帧交接、preview race 与 ambient-exit 修正未全部重测**，不能沿用先前结果覆盖它们。剩余构建、签名/身份/边界检查由 Lead 收口，本文不预报 PASS、产物 hash 或交付状态。

主机检查不能证明真机熄屏/指纹直达桌面、遮挡恢复、DOZE_SUSPEND、多显示器、GPU 或其他 ROM 行为；页面真实渲染预览也不能代替物理 DOZE。无自动安装、重启或设备设置变更。
