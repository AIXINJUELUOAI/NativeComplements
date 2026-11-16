# 现有架构与需要划清的边界

## 当前收口范围：0.11.5-alive-test1 / code149

本轮只做[AMBIENT固定下移和单页UI](<alive-platform.md>)，沿用147安全准入及148 CE/DE发布分层。顶部本用户系统AOD开三卡关两卡、点卡片开对应样式Sheet；外层照片/设置、资源收至更多。显式设置才发布，同组件无混用直接更新，首次/切换仍系统选择器成功核实；不新增认证/电源/亮度Hook，不把固定下移宣称动态时钟避让。阶段卡片各自独立GL/native实例，按phase聚合错误并随生命周期关闭；规则/状态与宿主执行恢复分层不变。验证及产物见[收口索引](<README.md>)，真机位置/视觉待用户验证。

## 历史收口范围：0.11.4-alive-test1 / code148

本轮仅增加Alive Direct Boot数据准备，保留147产品和动画边界；不改变规则/状态与平台执行恢复分层，不恢复独立小窗，不新增解锁、时钟或通知功能。验证与产物由Lead收口，此处不预报PASS/hash或设备效果。

### Alive：CE编辑与DE已应用运行时分离

- [AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)保留纯值Snapshot；CE纯编辑草稿照片/编辑缩略图不迁移。用户确认应用时才向本用户应用私有DE发布照片及完整样式，AtomicFile JSON是权威，先准备不可变照片再发布并读回校验快照。DE偏好只通知revision/error，不以SharedPreferences提交失败后的内存值冒充已发布状态。
- [AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)统一核心运行时DE路径；固定来源SHA/native/shader/mask白名单不变。解锁后的迁移先完整核验CE旧代，仅复制指定集合与manifest，readonly/fsync/暂存再校验/rename，不覆盖旧DE代、不删除CE源、不复制额外文件。真实renderer首次native加载前准备同一DE代，不能同进程换已加载库路径；预览仍使用CE草稿。
- [私有BootReceiver](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveBootReceiver.java>)只消费已解锁BOOT_COMPLETED/MY_PACKAGE_REPLACED并派worker，Engine初始化worker也准备；receiver非directBoot，不自动启动、重绑或选择壁纸。两个壁纸service声明directBootAware只表示可被系统锁前启动，不保证已有数据；新安装须应用一次，旧版须已解锁迁移后下一次开机才有效，首次未迁移仍回退。
- DE保留历史已应用照片及确认应用中断候选，本轮无自动回收/总量上限；不能承诺只存当前照片。从未确认应用的纯编辑草稿仍只在CE。DE中的这些照片在首次凭据解锁前对本应用可读，仍每用户应用私有、不外部共享、不跨用户读取。此隐私取舍不授予锁屏认证能力、不放宽147视觉safe/UID/nonce/期限门，不改AOD/电源/亮度。三种解锁现象仍在诊断；时钟下移/避让、照片burn-in与通知/跨应用扩展未实现，首解锁前真机行为仍待验证。

## 保留范围：0.11.3-alive-test2 / code147

code147按用户授权只在主模块提供小窗：恢复原生Hook左右下角滑动与扇区，不做无障碍触发，不再构建或发布独立小窗variant。**产品合回不等于回退规则分层，更不等于其他ROM免适配可用。**本节覆盖下方146“当前”及更早历史；实施和核验仍由Lead收口，不预报执行中的PASS/hash/真机效果。

### 小窗：产品收拢与平台边界各自处理

- [构建配置](<../../app/build.gradle.kts>)保留`full` flavor和原主包身份，只停止`window` variant；Alive及其他主功能继续使用原`full`源集。旧独立源集暂留Git但不编译，既有未提交修改不覆盖；不整提交revert或旧文件restore，避免混退Alive、Monet删除及安全修复。
- 仅接回角落输入、扇区/热区、相关设置与安全取消，不恢复长侧滑、通知/分享/shortcut、快捷建议小窗或全屏跨应用自动进入。无障碍按钮/区域不再作为入口，小窗/遮罩Monet实际恒关仍保留，抽屉Monet与主版About不变。
- **平台事件输入 → 共用规则/状态 → 平台执行与恢复**继续成立：[CornerGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>)与[Rules](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Rules.java>)只处理值与输入流状态；[BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)/[SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)承载native输入、pilfer、宿主扇区与保护区域；[WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>)与[AndroidWindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowAdmission.java>)区分值决策与原生事实读取；WM锁、Task/Surface/Insets、Binder、journal及恢复继续留平台执行侧。
- 其他ROM贡献者仍按[小窗接入文档](<floating-window.md>)核Framework/SystemUI/Shell/Launcher的精确ABI、资源与调用时机，保输入取消、真实安全界面、UID/protocol16/token/lease及恢复边界，复用既有规则和最小定向检查。fork/adapt/test后提PR，经review和现设备回归决定合入；Freeform或LSPosed激活不能单独证明所有操作兼容。

### 旧独立包：停止产物，不删除防双接管保护

[WindowBackendOwnership](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>)保留原key和first-claim，[Eligibility](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendEligibility.java>)仍投影本包enabled作为下次启动资格，[Conflict](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowBackendConflict.java>)使非owner不建Engine；[旧选择marker](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendSelection.java>)仅兼容墓碑，不再提供选择器。不强抢、不换key、不热转移、不跨包迁journal，也不自动修改LSPosed或对方数据。旧版先取得owner时主版安全拒绝重复接管，用户须先在旧版恢复窗口，再手动停用/卸载旧独立模块并完整重启。主版其他功能仍独立早装，不能把整个[ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>)移进小窗owner分支。历史身份/隔离依据保留在[独立小窗历史](<standalone-window.md>)。

### Alive：原生动画入场选择与视觉交接分离

code146的能力投影、球体混合轨/禁止补播及有界交接保留。147因原生动画延迟锁屏的确证，新增仅合格自有episode的原Unlocked.start动态范围入场选择，让原start自己走不播放分支写FALSEcache，原Fold循环/认证/唤醒仍执行，非全局谓词替换。admission尚未showing不等于允许视觉透出；后者仍需双真实showing安全门和成功swap rawBinder UID/nonce/期限确认。Engine仅符合严格准备条件时保native duration0既有episode，早DOZE不强保ON、不保证1800完整。原生1300ms状态流/opaque/亮度仍保留，失败不补播已跳过的旧动画，不把回调/首buffer当物理呈现或承诺动画已消除。具体ABI及限制见[Alive系统专题](<alive-systemui.md>)及[接入总览](<README.md>)。

## 历史实施状态：0.11.3-alive-test1 / code146

现有功能规则/状态与原生执行解耦已在Alive前推送至 `5ee8e95`；code139闭环、code143选择器及code145可选呼出/额外淡出均为历史。本节以code146覆盖旧“当前”，下方138审计仅按历史阅读。当前代码实现不等于真机效果或设备/LOS兼容声明；新Handoff尚未真机验证。

## 小窗：本包开关资格，不再额外选择后端

code143的额外selector没有解决“主版小窗关闭仍先占owner”的合理性问题，code144不再读取该选择决定启动。[WindowBackendEligibility](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendEligibility.java>) 由各包自身 `corner.enabled` 后台同步投影自己的非导出PM组件：ENABLED=1、DISABLED=0、DEFAULT/旧包缺marker=-1未知。主user设置保存先用 `DONT_KILL_APP|SYNCHRONOUS` 写并复核PM，再commit偏好，失败尝试恢复原资格；不把PM与偏好两次操作称跨存储原子事务。更新、已解锁boot、Provider/UI入口补迁移，先完成旧CE配置迁移，不读对方私有偏好、不写LSPosed数据库。

[WMS启动入口](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>) 固定user0读取**本包**资格，由 [Ownership](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>) 冻结：0不claim，1可claim；双开仍firstclaim防重复接管，不承诺固定优先级。-1只保留旧安装firstclaim兼容，不能据未知断言关闭。**首次升级/首次启动若迁移尚未运行，当前启动仍可能旧先到先占；迁移完成后的下一次完整重启才采用资格。**运行中关闭不会卸载系统Hook或热转交，不迁journal，资格变化只影响下次完整重启，不再要求用户额外选择后端。

ProductFeatures仍早装，小窗后端在已核WMS五参main before同步安装，deopt真实SystemServer.startOtherServices caller；失败保原生启动，不回抢已占owner。仅owner创建Engine，systemReady after仍attach。full被关闭而委托时只消费同SystemUI有界环境快照供展示/边缘保护，不借foreignBinder执行OTP/后台唤醒等fullEngine动作，过期/异常暂停。不是将整个full模块关掉，也不宣称任意ROM只装独立包即可兼容。

## 小窗：仅无障碍呼出，仍使用自有系统后端

code146两包仅允许当时的 `WindowAccessibilityService`（code147已删除）通过系统无障碍按钮呼出菜单，由用户手动开启；启用服务本身不弹菜单。仅请求button，无事件订阅，不读内容、不截图、不模拟手势/触摸探索。移除长侧滑、角落/扇区与返回手势呼出Hook；通知点击、分享转小窗、shortcut、快捷建议小窗、全屏跨app自动转小窗均不再作为旁路，旧偏好不能重新启用这些入口。菜单内既有操作、已有窗口导航及必要后端不属于被删除的呼出手势。

点击仅在本包enabled时发指定SystemUI包SHOW_MENU、protocol16并分享发送者身份；[SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)核本包UID及既有enabled/backendReady/安全/openWait门控。任务/几何/恢复仍由原Xposed system_server后端执行，保认证、安全窗口、输入优先级与恢复保护；不能借另一版本后端。**无障碍只承担入口，Freeform＋无障碍本身不足以替代系统后端**，不声明免Xposed或通用ROM兼容。

[ShadeStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeStore.java>)使小窗/遮罩Monet实际恒关并忽略旧值，相关UI与执行接入移除，主版抽屉Monet保留；不是只藏开关。[独立导航](<../../app/src/window/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)移除About但保留开源许可入口，主版About不删。[full入口](<../../app/src/full/resources/META-INF/xposed/java_init.list>)为7个，[window入口](<../../app/src/window/resources/META-INF/xposed/java_init.list>)为3个，独立版不再注册LauncherThemeModule；静态三个系统作用域与两包身份不改。

## Alive：全屏编辑、静态交接与窄系统适配

- [AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)保持draft/applied完整快照与UUID不可变照片；旧style1..5兼容，新配置默认启航。同组件无普通/实验混用时页面确认应用，否则系统选择成功且核实目标才提交；取消/失败保draft。
- [全屏编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)顶部返回/编辑/设置壁纸，底部Sheet直接显示当前息屏/锁屏/桌面样式。[单TextureView](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)同scene真实0→1→2→0，关闭回执在EGL断开后才释放对应Surface/Texture；应用成功发HOME并只移除本模块task，不结束桌面/他人任务。
- [同一renderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)支持lens0启航及照片1..5，锁屏0..3/桌面6..9独立纹理，默认0/6，雾花ground slots1/2。固定APK补导10张上游PNG作样式示意，分别SHA校验放私有可选previews-v1；不是当前用户照片实时缩略，不bundled，缺图可重选固定APK补齐，不替换已加载不可变native generation。
- [renderer](<alive-renderer.md>)按实际AOD ratio选快速唤醒轨，避免0→LOCK尚未展开完就到HOME时误tr4，造成decorator默认1000ms色块拖尾；固定native/shader不改。非sleep隐藏、visibility恢复及scene重建只接当前端点，不保存旧解锁种子补播；纯HOME不重新采球。
- [平台证据](<alive-platform.md>)保留6000ms等可见、首swap后1800ms收拢与7800ms绝限/静态holding。145只读日志14:38:52.987 sleep、53.029首swap、54.830窗口到期、57.066 DOZE，期间visible/draw=true，用户仍报告软件内和桌面熄屏都是系统动画后晚出现Alive；这不是renderer未画，也不是完整视觉交接已通过。
- [SystemUI交接](<alive-systemui.md>)用共用KeyguardService睡眠事件→原生WallpaperCommand PREPARE→安全锁屏临时FLAG_SHOW_WALLPAPER→自有visible/有效surface成功swap的raw Binder实际UID/nonce/display/7800ms校验→同root LightRevealScrim原onDraw空clip。保native opaque/alpha/controller、认证/电源/输入/亮度，不加wake lock，失败/超时/状态变化恢复原生；替代145 Pair淡出投影，不是所有原生动画替换，新Handoff尚未真机验证。

照片burn-in位移未做，时钟避让延期，仅短时测试。仅最小必要生命周期/失败保护及构建/R8/签名/身份/产品边界核验由Lead收口，不预报执行中的PASS/hash，不跑full suite或全功能回归；code145只读证据不是146 Handoff真机PASS，不自动安装/重启/写设置。

## 138 历史审计（以下正文）

审计基线 `930a22c` / 138；以下保留当时的架构、未实现项与约束，不作为当前实施结论。源码链接已迁移到现路径并移除不可靠行锚，仅供定位，不是旧 snapshot 的精确函数内容；含“目前/当前”的历史表述均按 138 阅读。

## 1. 一个 APK，四类运行环境

目前只有一个 app 构建模块，没有独立小窗 flavor；namespace 保留 `dev.local.sunwindowdemo`，applicationId 为 `dev.nativecomplements.module`，最低安装 API 34、compile/target API 37，依赖现代 libxposed API 101。参见 [构建配置](<../../app/build.gradle.kts>)、[模块元数据](<../../app/src/main/resources/META-INF/xposed/module.prop>)。

| 环境 | 现有职责 | 入口 |
|---|---|---|
| 管理器进程 | Compose 页面、权限、持久配置、应用目录、诊断和手动恢复请求；不直接操纵系统任务对象 | [MainActivity](<../../app/src/main/java/dev/local/sunwindowdemo/MainActivity.kt>) → [ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)、[WindowController](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowController.kt>) |
| system_server | WMS/ATMS 小窗任务生命周期、布局、Surface、输入与恢复；并安装独立来电和其他系统能力 | [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)、[CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>) |
| SystemUI 主进程 | 小窗手势/菜单/控制层、系统状态及其他 SystemUI 功能；宿主不是另启无障碍服务 | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)、[SystemIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java>)、[NavbarModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarModule.java>) |
| Pixel Launcher | 原生手势冲突保护、任务列表与返回呈现、图标、系统主题状态，以及该版本 Taskbar 导航源 | [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>)、[IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>)、[NavbarModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarModule.java>) |

准确的 libxposed 注册列表是 [java_init.list](<../../app/src/full/resources/META-INF/xposed/java_init.list>)，不是文件名带 Module 的所有类。静态作用域固定为 system/SystemUI/Pixel Launcher；发行版改用其他桌面时，代码里的目标包、UID 授权及模块作用域都需一并评审，不能只替换类名。

**注意：入口不是严格的功能边界。** [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>) 同时安装常亮建议、边缘输入过滤和传感器控制；[BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>) 同时安装面板、状态栏、旋转、建议等。保留一个入口不代表只保留小窗，删除一个入口也可能误删共用状态链。

## 2. 配置、命令与帧通信

```text
管理器页面
  ├─ 本包持久配置 ─→ UID 校验 Provider
  │                       ↓ 宿主 worker 读取/完整快照发布
  │                   SystemUI/Launcher/server 各功能消费者
  └─ WindowController → Wire 请求广播 → system_server WindowEngine
                                            ↓
                 SystemUI 控件 ← 结果/状态、Binder帧通道
                       └─ 拖动等控制 → 已认证 Binder endpoint
```

这张图是功能链，不是每个功能都走同一个 Provider 或同一线程。

### 小窗配置

- [ConfigProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ConfigProvider.java>) 仅授权本包实际 UID 和 SystemUI 实际 UID，提供 `read` 与窄 `targets` 写入操作；当前不能拿它给任意第三方/独立包读设置。
- Provider 会在导出前触发旧配置迁移。[copy](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ConfigProvider.java>) 在宿主 worker 完整写入，失败抛错，不应把未完成复制的配置发布为 ready。
- [SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>) 交互期间每 750ms 发送 STATUS，它同时供小窗、弹幕和建议使用；熄屏停止。当前它不只是“小窗是否开启”的心跳。
- 当前 [toggleControl](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowController.kt>) 会读取 Freeform 设置并按 correlated STATUS 检查能力，但按用户最新要求仅作提示：检查异常也可明确“继续开启”，取消不保存；确认开启只表示用户选择，不把未知能力伪装成已验证。不开系统设置、不强抢接管权、不删后端任务安全预检。STATUS 的协议兼容与宿主连接仍不等于全部运行能力。

### 命令与数据边界

- [Wire](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Wire.java>) 固定包/action/channel，协议为 16；帧接收要求 system UID、interface token、协议匹配，经 main 合并派发最新帧。
- [请求发送](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Wire.java>) 使用显式目标包并分享发送者身份；不能在接收侧只信 Intent extra 自报身份。
- [WindowEngine 的广播入口](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>) 核验来自实际管理器/SystemUI UID；[Binder 控制入口](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>) 还检查 interface、合法 op、数据边界，随后检查协议和受控 session。普通 op 与预测返回的同步事务路径不同。
- [WindowController 的结果接收](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowController.kt>) 同时要求 RESULT action 与 system UID，注册时要求 CONTROL 权限。修改进程/包身份时必须重新逐端核对，不能认为每条广播都天然有签名权限保护。
- 待接入的输入源应复用受控请求和任务归属路径，不直接操作任意任务。包名、user、taskId、session token、display、请求/Surface 生命周期都不是可省略的数据。

### 其他功能配置与快照

[Manifest](<../../app/src/main/AndroidManifest.xml>) 声明多个独立 Provider，授权策略并不相同；不要统一改成“所有系统 UID 可读”。[HostSnapshot](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>) 保留宿主最近成功读取的完整配置，Provider 暂不可达时可沿用，成功读到关闭值后整体覆盖；这不等于所有异常立即关闭。其 [directory](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>) 位于宿主存储，未来多产物需要隔离，主版历史目录要保留。

## 3. 逻辑与平台执行：已有基础，不是已经完全分离

| 现有部分 | 相对可复用的内容 | 仍需遵守的边界 |
|---|---|---|
| [Geometry](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Geometry.java>)、[Rules](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Rules.java>)、[GestureMath](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/GestureMath.java>) | 尺寸、位置、阈值和数值运算 | 可使用 Android 值类型，不应借此引入 ROM 类名、Binder 或反射；坐标单位与逻辑/物理空间仍须由平台侧明确 |
| [WindowPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowPolicy.java>)、[CornerGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>)、[MiniMagnet](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/MiniMagnet.java>) | 所有权判断、触摸决策、状态阈值 | 部分仍接收 Object 身份或平台常量；“不反射”不等于完整平台无关模型。已有应用特殊策略也不是 ROM 适配器 |
| [WindowNavigation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowNavigation.java>)、[LaunchCoverFlow](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LaunchCoverFlow.java>)、[WindowHistory](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowHistory.java>) | 导航/遮罩阶段和历史状态规则 | 先保留原有状态与超时语义；不要把任务执行成功前的意图当作成功状态 |
| [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)、[BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)、[LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>) | 捕捉原生事件与局部行为修正 | 属于平台输入/Hook 安装层，before/after、deopt、原生取消和回调都需留在对接边界 |
| [FrameworkAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>) | 集中精确反射、预检与缓存，便于核对成员 | **不是完整平台抽象**；构造时解析大量原生类型与成员，外部仍传递 native 对象、直接发事务 |
| [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>) | 当前会话协调、任务归属及恢复链 | 同时混合原生读取、规则调用、状态推进、锁、Surface 与任务操作，是后续拆分重点而不是可以原封不动叫“纯核心”的部分 |
| [SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)、[WindowControls](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowControls.java>)、[TaskCover](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskCover.java>)、[TaskDecoration](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/TaskDecoration.java>) | 具体 UI/Surface 展示与输入执行 | 必须保留窗口信任、任务子树、遮罩所有权、捕获流及 remove/dispose 语义；不硬塞进数值规则类 |

### 目标方向：先给现有边界命名，不先创造框架

```text
平台输入：原生回调/受控用户请求
    ↓ 规范化事实（含任务身份、用户/显示器、几何/Insets、转场/安全状态）
共用决策：规则、窗口状态、期望呈现
    ↓ 操作意图（语义而非反射方法名）
平台执行：任务/配置/Surface/输入/焦点操作
    ↓ 完成、拒绝或中断事实
共用状态推进；平台恢复原生对象和释放自有资源
```

这是后续 PR 的审查模型，不是已经存在的 DTO/API 名称。一次只提取一条真实闭环：先保留原生对象和锁在线程边界内，再把可独立验证的决策留在共用部分；不要一次把几千行引擎改成事件总线，也不要用 `Object + methodName` 的万能接口冒充解耦。

当出现第二个真实系统实现时，再把共同语义收成最小接口；发行版特有类名、字段、资源解析与 Hook 安装应集中在相应对接部分，业务代码不遍布 `isPixel/isLOS/isMIUI`。接口必须表达动作成立条件、失败和恢复，而不只是 `open()/close()` 两个名字。

## 4. 线程、锁和所有权不能“重构掉”

- [attach](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>) 取得 WMS `mGlobalLock`，建立 display-priority worker，并在其上建立 SF Choreographer（不可用则普通 Choreographer）。现有 Hook 也可能在原生 Binder、display 或动画线程进入；不能把所有回调假定为同一个 worker。
- [反射缓存说明](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>) 明确查找常在 WM 锁下执行；只换到“统一适配器”不会自动消除阻塞和死锁风险。
- Provider、PackageManager/Binder、磁盘和图标转换应在允许的工作线程完成，不在持有 WM 锁的帧路径临时读包/转换。核对每条调用，不把“所有东西 post 一次”当成修复：某些原生事务必须同步追加。
- 预测返回 PREPARE/FINISH 包含同步结果和事务所有权；普通拖动控制多为 oneway 后排队。接口重组不得擅自把同步返回变异步、丢掉取消或 release。
- Surface 是借用还是自有必须明确；任务原生 Surface 不能由模块释放，自有背板/cover/handle 需要替换、全屏恢复和 detached dispose 三路清理。透明背板跟随任务而不是 handle，重构时不把它重新绑定到装饰 hide。
- 关闭功能、宿主死亡、配置读失败、任务消失、旋转中断、权限确认和锁屏分别有不同政策，不应该压成一个“错误都 close”分支。

具体 Hook/能力及证据见 [小窗清单](<floating-window.md>) 和 [其他功能清单](<system-features.md>)。

## 5. 发行版接入与独立产物不是同一件事

目标系统档案至少包含：发行版/Android 构建、SystemUI/Launcher/framework/Shell 的确切组件、类与成员签名、资源和实际调用链、已有原生小窗及任务组织方式。先判断 native 路径语义，再选择适配；不靠 build fingerprint 字符串决定安全。

Freeform 支持仅是基础能力。现有 [FrameworkAccess 预检](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>) 还涉及任务、转场、Insets、输入、焦点和恢复；一个设置值或 mode 5 可用不足以证明全功能兼容。无障碍可以是未来输入入口选项，但不会替代这些系统执行能力，本轮不增加。

独立版的包身份、权限、状态通道、系统存储、宿主配置、Monet 上报与非小窗入口裁剪见 [拆分清单](<standalone-window.md>)。同源产物可共用规则和引擎；不要为了两个 APK 复制两套状态机，也不要在身份未隔离时允许同时接管。

## 6. 保持兼容的改动纪律

- 保留原主包 applicationId/签名、静态作用域、protocol16 以及旧偏好/恢复含义；迁移需要单独设计并测试，不顺手批量替换命名。
- [R8 规则](<../../app/proguard-rules.pro>) 当前保留所有模块类和宿主反射 ABI 名称；仅删页面/加常量不会自动裁掉其他代码，不能把“未执行”宣传成“未打包”。
- [runtime-changes](<../../tests/runtime-changes.json>) 是相对原始基线的差异登记，不是可重置的测试快照。后续运行代码变更需登记真实原因。
- 有最小逻辑检查还要有平台归档/预检和相关真机场景；测试层级见 [接入总览](<README.md>)。本轮不利用文档整理顺便修旧失败或变更已交付行为。
