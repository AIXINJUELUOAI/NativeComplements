# Alive 平台、配置与 Direct Boot 合同

Alive 仅在 full 主模块，使用原生 WallpaperService，不接管认证、输入或显示器电源。当前维护行为以 code165/166 的运行源码为准；社区接入不改变 Alive 运行路径，不按 ROM 名称授予能力。资源、绑定例外、系统交接与渲染分别见[资源合同](<alive-assets.md>)、[绑定合同](<alive-module.md>)、[SystemUI 合同](<alive-systemui.md>)、[渲染合同](<alive-renderer.md>)。

## 组件、编辑与应用

[普通服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)保留 ambient=false 回退；[ambient 服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>)复用 Engine，由系统选择和生命周期决定是否运行。

[编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)顶部阶段卡片进入对应样式 Sheet。照片通过系统 `ACTION_GET_CONTENT`（`image/*`）单图选择后保存为 CE 草稿，再进入取景。可拖动、缩放、复位，也可从编辑页重新调整同一张照片。各阶段共用取景，旧配置沿用居中。编辑草稿后仍需明确应用。APK 导入仍用文件选择器，无额外照片权限。

[系统状态](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemState.java>)只读本用户 `doze_always_on`，通过 ContentObserver/页面恢复刷新。1 显示三卡，0 显示两卡，其他/拒绝/缺键为未知，不能当关闭；隐藏卡不丢已存样式。设置已知时应用 ambient 组件，关闭后重新开启 AOD 无须重选；未知优先保已有 ambient 选择，否则普通组件回退。

同一目标组件已选且无普通/ambient 混用时可直接发布；首次或切换须经原生选择器成功并核实组件才发布。取消/失败保草稿及旧应用配置；成功发 HOME，再只移除本模块任务。返回桌面失败不应将已应用误报未提交。

## 数据与隐私

[AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)把样式、照片和取景保存在同一不可变快照。实际 Engine 读 applied，系统预览读 draft，页内卡片接显式快照。

| 数据 | 权威与存储边界 |
| --- | --- |
| 已应用样式/照片引用 | 本用户应用私有 DE 的 `alive-applied.json` AtomicFile，完整写入、sync、读回核验 |
| 已应用照片 | 先复制并核验为 DE 不可变文件，再发布引用它的快照；失败保旧发布 |
| native/shader/mask 核心世代 | DE 固定来源/白名单，只读发布，不随 APK 打包 |
| 从未确认应用的草稿照片、编辑示意资源 | CE；锁前不访问 |
| revision/error 偏好 | DE 变化通知，不代替已应用快照权威 |

已有 DE 快照损坏时显式失败，不采用旧 CE 配置冒充恢复；没有 DE 快照且用户未解锁时失败回退。迁移在已解锁 worker 验证旧核心完整性并发布 DE；不覆盖已有代，不删 CE 源。已有 DE 权威快照不被 CE 旧 applied 刷回。

[启动接收器](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveBootReceiver.java>)私有、非 directBoot，在已解锁 BOOT_COMPLETED/MY_PACKAGE_REPLACED 执行 worker；Engine 初始化 worker 也准备资源。两个服务 directBootAware，但接收器不选壁纸、不重绑、不主动启动服务。新安装须导入并应用一次；旧版须先已解锁迁移，之后开机才具备锁前数据。manifest 声明不等于数据已准备。

**隐私取舍：**已应用照片/配置在首次凭据解锁前对本应用可读，仍为每用户私有、不共享不上传。从未应用草稿仍受 CE 保护。DE 保留历史已应用照片及确认应用中断候选，当前不自动回收或设总量上限，不能承诺只存当前一张；反复应用会增加占用。DE 不放宽系统锁屏或认证。

## 事实输入与场景

[Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)主线程采样所属显示器、原生 visibility/ambient、交互和真实 Keyguard；[AlivePhase](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java>)只作值规则。自有 ambient 资格须组件支持且本用户系统 AOD 明确为1；关闭/未知禁止 AMBIENT 进出动画及 sleep 准入，LOCK↔HOME 保留。

- 有 ambient 资格且睡眠、非交互或 native ambient 时，目标 `AMBIENT=0`；否则按真实锁定选择 `LOCK=1` / `HOME=2`，不保存粘性 HOME。场景编号不证明屏幕已呈现 AOD。
- ON：原生 visible 且交互，或自有有界 sleep/端点保留。DOZE/DOZE_SUSPEND：须自有 ambient、真实 ambient、visible；SUSPEND 强端点，不维持动画循环。OFF/UNKNOWN/移除停绘并结束 sleep。
- 只监听所属 display ID；移除后锁定 UNKNOWN，不靠复用 ID 复活。销毁注销监听与延迟任务。
- SCREEN_ON/USER_PRESENT、可见恢复/ambient 退出只触发事实重采样；合格时最多 100/300/600/1000ms 四次复核，非永久轮询，不申请签名 locked-state listener。

睡眠等待、真实 swap 回执、固定期限、隐藏 idle 保留与睡眠预绘见[渲染合同](<alive-renderer.md>)；控制消息采用[显式身份广播](<alive-systemui.md>)，不再依赖 WallpaperCommand 传送自有 PREPARE/CANCEL。

## 不变量与验证边界

不 Hook 指纹/人脸放行，不由壁纸路径写 AOD 开关、加 wake lock、强亮屏或禁省电/接近保护；不改 DPI、Launcher 或时钟布局。开发者工具的显式系统设置操作不是壁纸自动行为。AMBIENT 固定中心为45%屏高，不是动态时钟避让；照片防烧屏位移未实现，仅宜短时测试。

主机检查/预览不能证明物理 DOZE、GPU、面板功耗或所有 ROM 兼容。真实状态失败须保留原生回退，不把未知当已支持或失败，也不因社区署名自动禁用功能。不自动安装、重启或写设备设置。
