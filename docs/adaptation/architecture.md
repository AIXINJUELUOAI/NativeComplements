# 现有架构与需要划清的边界

## 当前实施状态：0.11.2-alive-test2 / code145

现有功能规则/状态与原生执行解耦已在Alive前推送至 `5ee8e95`；code139小窗闭环与code143选择器均为历史。下文先记录code145当前边界，再保留138历史审计，不用旧“当前”表述覆盖新实现。当前代码实现不等于真机效果或设备/LOS兼容声明。

## 小窗：本包开关资格，不再额外选择后端

code143的额外selector没有解决“主版小窗关闭仍先占owner”的合理性问题，code144不再读取该选择决定启动。[WindowBackendEligibility](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendEligibility.java>) 由各包自身 `corner.enabled` 后台同步投影自己的非导出PM组件：ENABLED=1、DISABLED=0、DEFAULT/旧包缺marker=-1未知。主user设置保存先用 `DONT_KILL_APP|SYNCHRONOUS` 写并复核PM，再commit偏好，失败尝试恢复原资格；不把PM与偏好两次操作称跨存储原子事务。更新、已解锁boot、Provider/UI入口补迁移，先完成旧CE配置迁移，不读对方私有偏好、不写LSPosed数据库。

[WMS启动入口](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>) 固定user0读取**本包**资格，由 [Ownership](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>) 冻结：0不claim，1可claim；双开仍firstclaim防重复接管，不承诺固定优先级。-1只保留旧安装firstclaim兼容，不能据未知断言关闭。**首次升级/首次启动若迁移尚未运行，当前启动仍可能旧先到先占；迁移完成后的下一次完整重启才采用资格。**运行中关闭不会卸载系统Hook或热转交，不迁journal，资格变化只影响下次完整重启，不再要求用户额外选择后端。

ProductFeatures仍早装，小窗后端在已核WMS五参main before同步安装，deopt真实SystemServer.startOtherServices caller；失败保原生启动，不回抢已占owner。仅owner创建Engine，systemReady after仍attach。full被关闭而委托时只消费同SystemUI有界环境快照供展示/边缘保护，不借foreignBinder执行OTP/后台唤醒等fullEngine动作，过期/异常暂停。不是将整个full模块关掉，也不宣称任意ROM只装独立包即可兼容。

## 小窗：可选无障碍系统按钮，只换输入入口

code145新增两包共用的 [WindowAccessibilityService](<../../app/src/main/java/dev/local/sunwindowdemo/WindowAccessibilityService.java>)，由用户在系统设置手动开启并使用系统无障碍按钮；不复刻长侧滑，不提供自有全屏触摸捕获或手势识别。声明仅请求button，无事件订阅，内容读取/截图/手势模拟/触摸探索能力均关闭，事件回调为空；不遍历窗口或页面树。

点击只在本包enabled时发送指定SystemUI包的SHOW_MENU广播，携带protocol16并分享发送者身份；[SystemUiHost接收端](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)核本包实际UID、协议、既有enabled/backendReady/安全状态与openWait门控后才呼出现有菜单。任务/几何/恢复仍由原Xposed system_server后端执行，SystemUI仍拥有菜单与控件，独立身份不跨包借用。**Freeform＋无障碍本身不足以运行现有小窗，仍需Xposed后端及宿主适配生效**；本轮只扩大呼出入口，不声明所有ROM兼容或免Xposed。

## Alive：全屏编辑、静态交接与窄系统适配

- [AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)保持draft/applied完整快照与UUID不可变照片；旧style1..5兼容，新配置默认启航。同组件无普通/实验混用时页面确认应用，否则系统选择成功且核实目标才提交；取消/失败保draft。
- [全屏编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)顶部返回/编辑/设置壁纸，底部Sheet直接显示当前息屏/锁屏/桌面样式。[单TextureView](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)同scene真实0→1→2→0，关闭回执在EGL断开后才释放对应Surface/Texture；应用成功发HOME并只移除本模块task，不结束桌面/他人任务。
- [同一renderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)支持lens0启航及照片1..5，锁屏0..3/桌面6..9独立纹理，默认0/6，雾花ground slots1/2。固定APK补导10张上游PNG作样式示意，分别SHA校验放私有可选previews-v1；不是当前用户照片实时缩略，不bundled，缺图可重选固定APK补齐，不替换已加载不可变native generation。
- [Engine交接](<alive-platform.md>)依旧等native visible最多6s、首swap ack后1800ms收拢、hidden暂停三轨不绘不init、总绝限7800ms。code144自然日志证实59.832窗口结束仍ON即释放，至下一分钟01.745 DOZE相隔1.913s；code145动画完成后仅在同episode绝限内静态保留已呈现端点，不新建/续动画/延绝限。无scene只用真实LOCK/HOME种子；wake/ambient进入0/OFF/UNKNOWN/SUSPEND取消，ambient退出0仍真实展开。
- [SystemUI](<alive-systemui.md>)继续精确own/currentuser/AOD能力投影及两doze producer贡献0；新增默认显示器非交互、真实安全Keyguard门控的LightReveal binder一次episode≤7800ms投影：Pair(同native目标,true)→false，只跳额外333+733ms淡出，保留0.4/0.64原生暗度，不alpha0、不改原1300ms controller/auth/power。ABI/deopt失败保native，不能称全面连续或无闪已验证。

照片burn-in位移未做，时钟避让延期，仅短时测试。按用户少测试要求，本轮仅Phase/目标必要检查及R8/签名/身份/资源/产品边界，由Lead收口，不预报执行中的PASS/hash，不跑full suite或全功能回归。当前根因证据来自code144用户自然操作，code145视觉、GPU和物理DOZE仍待用户验证；不自动安装/重启/写设备设置。

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
