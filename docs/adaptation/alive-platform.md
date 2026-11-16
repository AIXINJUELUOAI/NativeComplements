# Alive：阶段事实、AOD 实验与 Android 壁纸边界

## 当前增量：0.11.5-alive-test1 / code149

本轮只修改AMBIENT固定位置与Alive单页编辑，不修改147睡眠准入、解锁认证或系统电源逻辑。

- [SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>)和[FrameMotion](<../../app/src/full/java/org/aliveclean/FrameMotion.java>)的AMBIENT锚点统一`.05f`，屏幕中心位置为`H*(.5-.05)=.45H`；收拢终点与展开起点共用该值，LOCK/HOME仍全屏居中。不读取真实时钟bounds，固定下移不等于动态避让，也不保证所有时钟尺寸绝无重叠；原曲线、时长、native和shader未改。
- [Alive单页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)复用SettingsPage标题及安全区，顶部阶段卡片为同一真实renderer静态端点，点击只弹对应阶段的ModalBottomSheet；无独立预览页/阶段切换栏/完成按钮，样式点选保存CE草稿后更新卡片，主题色描边加轻微提亮表示选中。下方只保留照片与设置壁纸；渲染资源/许可/失败状态收至更多，不丢失首次导入入口。样式小图仍是导入的官方示意，不伪造用户照片效果。
- [系统AOD输入](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemState.java>)只读本应用用户`doze_always_on`，ContentObserver及页面恢复时刷新。开启显示三卡，关闭两卡；保留原aod样式，省电/遮挡等瞬态不改变板块。缺键/拒绝/非0或1均为未知，明确提示而非误报关闭；未知暂隐藏息屏卡，应用时优先沿用既有实验组件，否则普通回退。不写系统设置，不强亮屏。
- 系统值已知时应用AOD能力组件，关闭AOD仅由系统暂停，重新开启不必再次切换组件；普通false元数据组件继续作为未知状态/旧安装的回退，不删其入口。移除模块重复确认层，点击设置对已选同组件无混用时直接发布完整快照；首次或切换仍使用原生壁纸选择器，仅成功并核实目标组件后发布。取消/失败不提交草稿，沿用148 DE发布及成功HOME/仅移除本模块任务契约。
- 本轮验证结果在[收口索引](<README.md>)记录；照片防烧屏与三种解锁现象仍未解决，不以主机检查宣称真机位置/视觉通过。

## 历史增量：0.11.4-alive-test1 / code148

### code148：Direct Boot 数据边界

本轮只处理首次凭据解锁前的数据可用性，保留147睡眠admission与严格视觉交接，不把Direct Boot当作解锁认证或动画修复。

| 数据 | 存储与发布边界 |
| --- | --- |
| 已应用aod/lock/home/photo | 本用户应用私有DE的AtomicFile JSON为权威；不依赖SharedPreferences成功更新内存来宣布应用成功 |
| 当前已应用照片 | 先复制并核验为DE不可变文件，再原子发布引用它的完整快照；旧发布失败不采用新配置 |
| pinned native/shader/mask | 核心运行时代统一DE，固定来源SHA、资源白名单及验证不变，不随APK打包上游素材 |
| 仅编辑、从未确认应用的草稿照片与编辑缩略图 | 仍留CE，未解锁不访问；预览不迁成锁前常驻数据 |
| revision/error通知 | DE偏好只作变化通知，不是已应用配置权威 |

[AliveAssets.runtime/migrate](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)在worker执行。DE已就绪可快返，未解锁不读取旧CE；旧CE核心代必须复用完整hash验证，再仅复制REQUIRED集合与manifest到新DE暂存目录。只读文件、文件及目录fsync、暂存完整验证后rename；不覆盖损坏或已有发布代，不删旧CE、不复制未核额外文件。真实renderer首次native加载使用DE，不能在同进程将已加载的native库切换路径；预览仍读取CE草稿，但核心库也使用同一DE代。

[AliveSettings.prepareRuntime](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)先准备核心资源，再按已应用照片发布DE快照；有DE权威快照时不从CE旧applied刷新覆盖。缺失、损坏或迁移失败按不可用回退，不加载半份配置。[AliveBootReceiver](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveBootReceiver.java>)是私有且非directBoot的已解锁BOOT_COMPLETED/MY_PACKAGE_REPLACED worker；Engine初始化worker也准备资源，避免依赖用户先打开管理页。普通/实验两个service均directBootAware，接收器不会选壁纸、重绑或启动服务；仍由系统选择与生命周期决定运行。

**首次使用/升级限制：**新安装须导入资源并应用一次；旧版须先在已解锁状态迁移，之后下一次开机才有锁前可用数据。未迁移的首次开机仍可能回退，不能把manifest声明当作迁移成功。

**隐私变化：**用户应用的当前照片与已应用样式进入DE，首次凭据解锁前本应用即可读取，仍是每用户应用私有目录，不外部共享、不上传、不读取其他用户数据。从未确认应用的纯编辑草稿仍受CE保护；DE另保留历史已应用照片及确认应用中断后的候选。本轮不自动回收旧DE照片或设总量上限，以免删除活动旧Engine所需文件，因此不能承诺DE只存当前一张；应用越多照片占用会增加。DE可用不等于绕过系统锁屏、认证或AOD设置。

code148主机定向检查、R8/原签名与Direct Boot服务声明打包核验通过，见[收口证据](<README.md>)；尚未验证首次解锁前真机显示。三种解锁现象仍在诊断；时钟下移/避让、照片防烧屏及通知/跨应用扩展未实现。147动画安全门、原生电源/亮度/认证边界不变。

## 历史增量：0.11.3-alive-test2 / code147

产品收回主模块：仅full构建，不再交付独立小窗；Alive仍在full源集。146只读证据已确证原Unlocked动画延迟真实锁屏约1300ms，见[系统侧147契约](<alive-systemui.md>)；新实现仅合格自有睡眠episode、原start动态范围选择原生不播放旧动画分支，不全局改电源/亮度/认证。视觉仍必须真实locked安全且自有可见有效Surface成功swap，保绝限7800和原生DOZE/OFF/SUSPEND，不保证完整1800ms。

[Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)对native ambient duration0只在本Engine结构有效未过期PREPARE、已active/defaultON/非interactive时保留已有有界等待，不要求此刻已经visible/locked/有效Surface；实际绘制/ACK/透出仍分别校验这些真实条件，schema不冒充发送者认证。仅保持已有episode，不新建或重启。其他0时长继续endSleep；早DOZE只有ambient且已有成功sleep.start并未绝限才允许帧确认，不恢复隐藏scene、不强保ON。成功sleep.start回调补一次ack，保持render成功先于确认。初始化仍真实可见，旧首次safe布局可重发同nonce，所有UID/身份限制不变。早DOZE或native事件先后不同仍可能截短/安全回退，新包必须真机复核，不用146日志宣称修复已生效。

## 历史实现：0.11.3-alive-test1 / code146

全部现有功能解耦推送至 `5ee8e95` 后才开始Alive。code140普通照片、141实验AOD、142用户反馈可见、144启航/草稿/首帧确认、145静态端点保留均为历史。本节覆盖145“当前”表述：用户明确软件内及桌面熄屏仍先走系统动画/黑场，稍后才出现Alive。code146新增共用系统睡眠交接与唤醒轨/禁止补播修正，**新Handoff尚未真机验证**，不能把旧版可见反馈或主机检查当连续性PASS。独立小窗版不含Alive。

## 明确选择的组件与配置

- [普通服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 保留 `supportsAmbientMode=false`，作为原生普通壁纸回退。
- [实验服务](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>)复用Engine并声明自身ambient能力；[绑定例外](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientAccess.java>)只在有效用户、该精确组件的单次bind同步作用域内，消费一次metadata检查，不是全包/全局权限grant，不授予其他签名权限。
- [AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>) 分离 `alive_wallpaper_draft` 与 `alive_wallpaper` 已应用快照。照片使用不可变 UUID 文件名，编辑不覆盖当前照片；旧 `style` 1..5 与旧照片仍可读取，新编辑默认启航0。历史版本以一次偏好提交发布；code148起运行时改用上文DE AtomicFile完整快照，CE草稿仍独立。
- [单页编辑](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)以顶部两/三张真实阶段卡片进入对应样式Sheet，不再保留历史全屏编辑页。编辑只改草稿，缩略图仍是固定上游示意而非当前用户照片的实时渲染；缺缩略时保留名称，不伪造图片，可从更多补导。
- 同一目标组件已选且无普通/实验混用时，点击设置即提交，不再额外模块确认；否则打开原生选择器，仅返回成功且核实目标组件后提交。取消/失败留在编辑页并保留草稿。应用成功后发 HOME Intent，再仅结束/移除本模块所属 app task；不结束桌面或其他任务。返回桌面失败会提示，不能把已应用误报成未提交。

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

## code144/145 历史证据与仍保留的有界窗口

[用户自然操作捕获](<../../verification/alive-live-20261116-1400/manual-transition-logcat.txt>)显示：13:59:57.714 收到 goingtosleep，57.722 首帧呈现；59.112 隐藏、59.420 恢复；59.832 sleep-expired 时显示器仍 ON，随即 release；14:00:01.745 才进入 DOZE 并允许重新初始化。**从释放到 DOZE 有1.913秒空档**，所以不能再把“首帧后有1800ms动画”解释为已覆盖原生交接。

1. goingtosleep、SCREEN_OFF、真实交互下降或有时长的 ambient 进入开始 episode，重复信号不续期；等待原生 visibility 最多 **6000ms**，隐藏不绘制、不初始化。
2. 可绘后只发一次 sleep 请求，完整起始帧成功 swap 的 ack 才启动三轨收拢与 **1800ms** 动画窗口。临时隐藏暂停三轨与帧调度，恢复续播；从首次信号起绝对上限仍为 **7800ms**。
3. code145 把“动画结束”和“丢弃已呈现 native buffer”分开：仅自有实验 Engine、仍 ON/非交互/ambient、同 episode 且尚未绝对到期时，`holding` 静态保留已有 scene 的 AOD 端点，桥接原生 ON→DOZE。**不隐藏初始化、不为 holding 新建 scene、不延长动画、不延长7800ms期限**；可见时仅按需提交静态端点，稳定后停帧。
4. wake、进入 ambient 的 `duration<=0`、DOZE_SUSPEND、OFF/UNKNOWN/移除取消该保留；旧 generation 回调不能重启。退出 ambient 即使 duration=0 仍按真实 awake 事实展开，不把它作为跳过几何动画的指令；不强设 HOME、不等待认证。

code145历史还曾只把LightReveal binder的Pair animate=true投影false，跳额外333+733ms淡出、保留原生目标暗度；这不足以消除前半段原生视觉遮挡，code146已移除该投影，不能与新Handoff混写为同时生效。

[code145只读日志](<../../verification/alive-live-20261116-1440/logcat-all.txt>)中，14:38:52.987收到goingtosleep，53.029成功首swap，54.830 sleep-expired（54.836记录AOD端点），57.066才DOZE；这段Engine持续visible/draw=true。结合用户“软件内和桌面熄屏都先系统动画/黑场后Alive”的视觉报告，证明仅保scene/缩短额外fade未解决问题；日志只能证已提交buffer与平台状态，不能单凭它确定每帧被哪个系统层遮住，更不能再误报成renderer一直没画。

## code146：软件内与桌面共用系统交接

[AliveSleepHandoff](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSleepHandoff.java>)从同一个原生KeyguardService睡眠事件开一次有界episode，发原生WallpaperCommand PREPARE。仅精确自有实验锁屏、当前用户/AOD匹配及真实安全锁屏时，临时保原窗口 `FLAG_SHOW_WALLPAPER`；Engine须native visible、有效surface且成功swap，经实际UID/nonce/display/期限校验的raw Binder确认后，才让同root LightRevealScrim的原onDraw在临时空clip下执行，使Alive可透出。原native opaque回调、alpha/controller、认证、电源/亮度及输入不改，不加wake lock；这不是替换全部系统动画。

绝限7800ms不续期，未确认不clip；超时、隐藏/销毁、wake、配置/用户/壁纸/AOD或显示状态不再满足时恢复原生。实查selection/Window scope和ABI/deopt失败均保原路径，详见 [系统交接](<alive-systemui.md>)。新Handoff尚未真机验证，不能把145已swap或旧静态holding当146视觉PASS。

## 唤醒复核与安全边界

原生命令只触发事实重采样，不代表认证成功。SCREEN_ON/USER_PRESENT、可见恢复、ambient退出在可见交互ON时最多安排100/300/600/1000ms四次去重复核；睡眠、隐藏、ambient、非交互、非ON及销毁取消。不永久轮询、不申请签名级locked-state listener、不保存粘性HOME。code146普通隐藏、visibility返回或scene缺失只同步当前端点，重建不保留AOD解锁种子；因此解锁回软件再返回桌面不应补播，详见 [渲染边界](<alive-renderer.md>)。

- 不 Hook 认证/指纹/人脸放行，不写 AOD 开关，不强亮屏、不加 wake lock、不禁省电/接近传感器保护，不改显示器电源或亮度。
- SystemUI仅当前用户实际锁屏精确实验组件时投影能力；两个doze producer模糊贡献与新Handoff是不同边界，认证、QS与其他原生路径保留。
- 磁盘、照片解码与 GL 在相应 worker；主线程采样事实。`shouldZoomOutWallpaper()` 仍为 false，不改 Launcher、DPI 或时钟布局。
- 照片防烧屏位移未做，时钟避让/位置微调延期；系统时钟移动不代表照片也移动，只宜短时测试。

## 检查与证据限制

按用户少测试要求只保留必要生命周期/失败保护及构建、R8、签名、身份/资源/产品边界核验，由Lead收口；不跑full suite或新增全功能回归。本文不预报执行中的PASS/hash/交付状态；144/145归档用于定位，不是146新Handoff真机效果证据。

页面真实预览与主机检查不能证明物理 DOZE、熄屏/指纹展开、遮挡恢复、GPU或其他 ROM 行为；本轮视觉由用户验证，无自动安装、重启或设备设置变更。
