# Alive：阶段事实、AOD 实验与 Android 壁纸边界

## 当前实现：0.11.2-alive-test2 / code145

全部现有功能解耦推送至 `5ee8e95` 后才开始 Alive。code140 仅普通照片壁纸；code141 增加自有组件绑定例外与实验 AOD；code142 用户反馈 AOD 照片可见、亮屏正常。code144 增加启航、草稿与首帧确认，但新自然熄屏日志仍证实动画结束至原生 DOZE 之间提前释放。本轮针对该断层增加静态端点交接，不把旧构建结果或用户旧版反馈当作 code145 连续性已通过。独立小窗版不含 Alive。

## 明确选择的组件与配置

- [普通服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 保留 `supportsAmbientMode=false`，作为原生普通壁纸回退。
- [实验服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>) 复用 Engine，只覆盖自身 ambient 能力；系统绑定例外只匹配该精确组件，不是全局 grant，也不授予其他签名权限。
- [AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>) 分离 `alive_wallpaper_draft` 与 `alive_wallpaper` 已应用快照。照片使用不可变 UUID 文件名，编辑不覆盖当前照片；旧 `style` 1..5 与旧照片仍可读取，新编辑默认启航0。完整 aod/lock/home/photo 在一次偏好提交发布。
- [全屏编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>) 顶部返回/编辑/设置壁纸；“设置壁纸”是应用，不是参数设置。编辑只改草稿，底部 Sheet 直接显示当前阶段真实可选样式与固定上游缩略图，背景保持同一真实预览。缩略图不是当前用户照片的实时渲染，缺少时明确提示补导，不伪造效果。
- 同一目标组件已选且无普通/实验混用时，页面显式确认后提交；否则打开原生选择器，仅返回成功且核实目标组件后提交。取消/失败留在编辑页并保留草稿。应用成功后发 HOME Intent，再仅结束/移除本模块所属 app task；不结束桌面或其他任务。返回桌面失败会提示，不能把已应用误报成未提交。

## 平台事实 → 阶段与绘制门控

[AlivePhase](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java>) 不依赖 Android 对象；Engine 仅采样所属显示器、交互、ambient、真实 Keyguard 状态。

| 当前事实 | 场景目标 |
| --- | --- |
| 所属显示器非 ON，或不可交互，或真实 ambient=true | `AMBIENT=0` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 锁定 | `LOCK=1` |
| 所属显示器 ON、可交互、非 ambient、Keyguard 未锁定 | `HOME=2` |

`AMBIENT` 是场景编号，不证明系统已呈现 AOD。睡眠目标为0；缺少既有场景时，只能用最近真实 LOCK/HOME 起点，没有种子则静态端点，不捏造 HOME。

| 显示器状态 | 允许条件 |
| --- | --- |
| ON | 原生 visible，且可交互；实验组件另允许有界 sleep 动画/既有端点交接 |
| DOZE | 实验组件、真实 ambient=true、原生 visible |
| DOZE_SUSPEND | 同 DOZE，但立即完成端点，不维持动画循环 |
| OFF／UNKNOWN／已移除 | 停绘并结束 sleep，ambient=true 不能绕过 |

Engine 从 `getDisplayContext().getDisplay()` 捕获所属显示器，仅响应同 ID；移除后锁定 UNKNOWN，不能被重用 ID 复活。销毁时注销监听并取消全部延迟任务。

## code144 自然熄屏证据与 code145 交接

[用户自然操作捕获](<../../verification/alive-live-20261116-1400/manual-transition-logcat.txt>)显示：13:59:57.714 收到 goingtosleep，57.722 首帧呈现；59.112 隐藏、59.420 恢复；59.832 sleep-expired 时显示器仍 ON，随即 release；14:00:01.745 才进入 DOZE 并允许重新初始化。**从释放到 DOZE 有1.913秒空档**，所以不能再把“首帧后有1800ms动画”解释为已覆盖原生交接。

1. goingtosleep、SCREEN_OFF、真实交互下降或有时长的 ambient 进入开始 episode，重复信号不续期；等待原生 visibility 最多 **6000ms**，隐藏不绘制、不初始化。
2. 可绘后只发一次 sleep 请求，完整起始帧成功 swap 的 ack 才启动三轨收拢与 **1800ms** 动画窗口。临时隐藏暂停三轨与帧调度，恢复续播；从首次信号起绝对上限仍为 **7800ms**。
3. code145 把“动画结束”和“丢弃已呈现 native buffer”分开：仅自有实验 Engine、仍 ON/非交互/ambient、同 episode 且尚未绝对到期时，`holding` 静态保留已有 scene 的 AOD 端点，桥接原生 ON→DOZE。**不隐藏初始化、不为 holding 新建 scene、不延长动画、不延长7800ms期限**；可见时仅按需提交静态端点，稳定后停帧。
4. wake、进入 ambient 的 `duration<=0`、DOZE_SUSPEND、OFF/UNKNOWN/移除取消该保留；旧 generation 回调不能重启。退出 ambient 即使 duration=0 仍按真实 awake 事实展开，不把它作为跳过几何动画的指令；不强设 HOME、不等待认证。

[SystemUI 窄适配](<alive-systemui.md>)另对精确自有 AOD 的 LightReveal binder 额外333ms等待+733ms淡出作一次性投影，保留原生目标暗度；不是把 scrim 强制透明，也不替换1300ms原生 controller。静态端点保留与该投影都不是对所有系统遮挡、闪烁或连续性的保证。

## 唤醒复核与安全边界

原生命令只触发事实重采样，不代表认证成功。SCREEN_ON/USER_PRESENT、可见恢复、ambient 退出在可见交互 ON 时最多安排100/300/600/1000ms四次去重复核；睡眠、隐藏、ambient、非交互、非ON及销毁取消。不永久轮询、不申请签名级 locked-state listener、不保存粘性 HOME。

- 不 Hook 认证/指纹/人脸放行，不写 AOD 开关，不强亮屏、不加 wake lock、不禁省电/接近传感器保护，不改显示器电源或亮度。
- SystemUI 仍仅当前用户实际锁屏精确实验组件时投影能力；两个 doze producer 的模糊贡献与 LightReveal 的局部时序是不同边界，认证、QS 与其他原生路径保留。
- 磁盘、照片解码与 GL 在相应 worker；主线程采样事实。`shouldZoomOutWallpaper()` 仍为 false，不改 Launcher、DPI 或时钟布局。
- 照片防烧屏位移未做，时钟避让/位置微调延期；系统时钟移动不代表照片也移动，只宜短时测试。

## 检查与证据限制

按用户少测试要求，本轮只保留 Phase/目标相关必要检查及 R8、签名、身份/资源/产品边界核验，由 Lead 收口；不跑 full suite 或新增全功能回归。本文不预报仍在执行的检查 PASS、hash 或交付状态。code144 的自然操作捕获用于定位根因，不是 code145 真机效果证据。

页面真实预览与主机检查不能证明物理 DOZE、熄屏/指纹展开、遮挡恢复、GPU或其他 ROM 行为；本轮视觉由用户验证，无自动安装、重启或设备设置变更。
