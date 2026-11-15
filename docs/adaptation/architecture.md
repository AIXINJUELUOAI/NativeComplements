# 现有架构与需要划清的边界

## 当前实施状态：0.10.0 / code139

已 push：`3afdb01` 的纯 [WindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowPresentation.java>) / [AndroidWindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowPresentation.java>) 执行分界；`58f39f9` 的真实 Freeform correlated STATUS gate 与 single Properties owner 隔离；`bd3aafb` 的独立 [LauncherThemeReporter](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java>)。已 push 阶段仅引用 345 pass 单测证据。

未 commit 工作区：C1 [WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>) / [AndroidWindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowAdmission.java>)；`full` / `window` productFlavors 共用同一 Engine；[HostExtras](<../../app/src/main/java/dev/local/sunwindowdemo/HostExtras.java>) 与两源集 ProductFeatures 隔离非小窗实现。导航分为 [full ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>) / [window ModuleApp](<../../app/src/window/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)，[Page](<../../app/src/main/java/dev/local/sunwindowdemo/navigation/Page.kt>) 共享。注册为 [full 7 entry](<../../app/src/full/resources/META-INF/xposed/java_init.list>) / [window 4 entry](<../../app/src/window/resources/META-INF/xposed/java_init.list>)（3 window + LauncherThemeModule），main 三系统 scope 不变。

当前双 flavor 完整单测 full 353 / window 348 均 0 失败，双 release/R8、原签名/身份/scope/入口/快捷方式及禁止非小窗 Hook 的 DEX 核验通过；仍为阶段产物，未做设备回归。C2 controls / group / navigation / recovery / input 待后续，不能把以上边界称为全部解耦、独立版可用或 LOS 兼容。当前状态与贡献顺序以 [接入总览](<README.md>) 为准：我们完成 refactor 后，LOS 贡献者再 fork / adapt / test PR，我们 review + 现设备回归后才决定 merge。

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
- 当前 [toggleControl](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowController.kt>) 直接持久化 enabled，尚无用户新提议的“开总开关前实际 Freeform 能力校验”。STATUS 的协议兼容与宿主连接不等于全部运行能力。

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
