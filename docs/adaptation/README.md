# 跨系统接入与小窗拆分索引

## 当前状态：全部功能解耦，随后接入 Alive 的 0.11 开发

用户追加授权顺序为：**现有全部功能规则/状态与系统执行分离、文档及 push 完成后，再开始 Alive 壁纸研究实现**。本轮非小窗规则边界已完成集成：相关既有功能检查与 full/window 双源集编译通过，既有通话增强（含 Android javac/安全不变量）和抽屉莫奈归档检查通过，10 篇接入文档 378 处本地链接/源码范围通过；独立安全审查 P0/P1 为 0。本阶段没有运行四百余项全套，也未操作设备，不能沿用下方 0.10.0 的历史测试结果声称本轮已全测。提交推送后继续 Alive，不在这里提前称 0.11 已交付。复用既有检查，不新增成批测试或反复全套；必要启动安全、权限、失败恢复与原签名/R8 核验保留。

### 非小窗功能接入入口

| 范围 | 规则/状态与原生执行边界 |
|---|---|
| 导航、双栏、状态栏外观、信号 | [系统外观接入](<appearance-boundary.md>) |
| 桌面、抽屉、通知与小窗图标 | [图标接入](<icon-boundary.md>) |
| 弹幕与快捷建议 | [通知与建议接入](<notification-boundary.md>) |
| 来电拦截、传感器、边缘防误触 | [系统策略接入](<system-policy-boundary.md>) |
| 诊断、配置快照与产品组成 | [共享宿主接入](<shared-boundary.md>) |

已有纯策略复用，native Hook、精确 ABI/资源、Binder 身份、线程、IO、View/Surface 与回滚留在各适配侧；不为单实现堆通用接口，不把页面代码迁移当作业务解耦。其他系统可复用规则层并实现真实平台事件/执行链，仍需核实自己的组件构建与设备行为。

## 已交付：0.10.0 / code139 第一轮代码闭环完成

第一轮授权的小窗规则/状态与原生执行分界、同源独立小窗产品隔离已完成，版本为 `0.10.0` / versionCode `139`，以[构建配置](<../../app/build.gradle.kts>)为准。**这是代码与主机验证交付，不是设备效果或 LOS 兼容声明，也不是任意系统可替换的完整平台接口。** 该次交付时 Alive 与其他功能重构尚未实施。

### 最新进度：恢复与生命周期闭环

- 恢复阶段 `912070f` 已 push，receipt：`cbd207b..912070f`。纯 [WindowRecoveryPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowRecoveryPolicy.java>)承载 boot/live 资格、100 次重试与就绪一次复查；[WindowHistory](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowHistory.java>)承载 120ms 稳定观察，native snapshot 仍先于历史删除。该阶段完整单测 full 405 / window 400，零失败。
- 生命周期阶段 `a0f3a7b` 已 push，receipt：`912070f..a0f3a7b`。纯 [WindowLifecycle](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowLifecycle.java>)承载 tick、priority/input、continuation 与 visible-resume 值规则；原生 lease 最后采样、隐藏分支无 lease、WM 锁内重验及执行顺序保持。
- 最终完整单测 **full 415 / window 410，均零失败、零错误**；基线 **4 unchanged / 22 documented changes / 37 additions**、全部 host checks、无 Android 编译依赖的标准 javac 纯类检查及归档接口检查通过。current/QPR2 入口归档签名检查通过，不能替代真机回归。
- 最终双 Release/R8 与双 APK 原签名、独立 IDs、三个静态 scope、full 七/window 四入口、快捷方式及禁止非小窗 Hook 的 DEX 边界核验通过。导航、恢复、生命周期独立审查 P0/P1 均为 0；纯规则均有实际生产调用。
- [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)保留 Android 协调、原生对象身份、WM/事务、AtomicFile/Parcel 与恢复副作用；不为单实现新增通用平台接口，不以文件大小衡量解耦。当前计划中的五个闭环已收口，设备回归按[设备验收](<../../DEVICE_ACCEPTANCE.md>)执行，安装/重启/LSPosed 配置由用户操作。

### 最终双 APK

版本均为 `0.10.0` / code `139`，使用原覆盖升级证书；只交付双 APK，不交付源码 ZIP 或额外报告。

| 产物 | SHA-256 |
|---|---|
| 主模块（本地交付产物，不入库） | `d422c90bcb2e5c73630a017ca77726fcefd38c9c298b0b5d7f9dc971702e62d2` |
| 独立小窗（本地交付产物，不入库） | `0db253d3dde78c781c33dc8c63b5ebdccb39c5c60a59598b963b243ab6a4ccbb` |

APK 在本地生成且不入 Git；以上核验对应 `a0f3a7b` 的运行源码。安装/重启/框架配置由用户操作，建议只启用一个小窗后端；主版覆盖升级，独立版使用隔离身份、配置和恢复记录，不自动迁移主版设置。

### 历史阶段：a8d6d60 已提交并 push

最新阶段 `a8d6d60` 已 commit 并 push main，receipt：`00b6db6..a8d6d60`。本节集中记录最新证据；后面的提交与数字均是对应阶段历史，不覆盖后续未提交改动。

- `WindowNavigation` 已为纯 value，native 票据由 `AndroidWindowLaunch` 持有；`WindowPolicy` 的 5 个 Object 签名改为 boolean facts，Host 各入口统一使用 `WindowHostPolicy` admission。
- `BackHandoffState` 提取 once、generation、idle 与 timeout 状态；**同时修复既有 late/duplicate callbacks 可能导致多次 commit 的漏洞**，不是只搬代码。原生取消、Shell 与主线程 IPC 顺序保留。
- 完整双单测 **full 396 / window 391，均 0 失败**；`verify_runtime` 的原基线 5 unchanged / 21 documented changes 与 35 additions、全部 host checks，以及无 Android 编译依赖的标准 javac 纯类检查通过。
- 双 release/R8、双 APK 打包的原签名、IDs、scope、entries、shortcuts 与 forbidden DEX 检查通过；独立 review 的 P0/P1 为 0。以上均是阶段静态/主机/构建证据，**不是设备或 LOS 兼容结论**。

该阶段当时剩余恢复、窗口生命周期两闭环；现均已在上方最新阶段完成。该阶段的 APK/hash 只作历史证据，不作为最终交付包。当时 Alive、其他功能重构暂缓；native AtomicFile/Parcel/WM/Binder 留在适配侧。

### 此前已提交并 push 的边界

- `3afdb01`：呈现规则提取为纯 [WindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowPresentation.java>)，原生对象、锁与事务保留在 [AndroidWindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowPresentation.java>) 执行侧；并非整个引擎平台无关。
- `58f39f9`：启用前使用真实 Freeform 能力与 correlated STATUS 请求/响应门控，不自动修改系统设置；以 single Properties owner 隔离重复接管，保留主包身份和恢复含义。这不是任意 OEM 原生小窗的通用共存协议。
- `bd3aafb`：[LauncherThemeReporter](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java>) 独立，不再把系统主题状态上报绑定到抽屉莫奈安装。

### `a6da12a` 已提交并 push 的边界

- C1 接管规则已落 [WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>) 与 [AndroidWindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowAdmission.java>)；共用同一 [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)，不是复制引擎。
- Gradle 增加 `full` / `window` productFlavors；[full ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>) 从 main 迁出，[Page](<../../app/src/main/java/dev/local/sunwindowdemo/navigation/Page.kt>) 仍共享，[window ModuleApp](<../../app/src/window/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>) 是窄小窗入口。
- 注册表改为 [full java_init.list](<../../app/src/full/resources/META-INF/xposed/java_init.list>) 与 [window java_init.list](<../../app/src/window/resources/META-INF/xposed/java_init.list>)；window 为 4 entry（3 个小窗入口 + [LauncherThemeModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeModule.java>)），[scope.list](<../../app/src/main/resources/META-INF/xposed/scope.list>) 仍在 main、保留原三个系统作用域。
- [HostExtras](<../../app/src/main/java/dev/local/sunwindowdemo/HostExtras.java>) 契约配合 [full ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>) / [window ProductFeatures](<../../app/src/window/java/dev/local/sunwindowdemo/ProductFeatures.java>) 真实源集隔离非小窗宿主实现，不只是隐藏页面或加运行时布尔量。window 的 [WindowIconProvider](<../../app/src/window/java/dev/local/sunwindowdemo/features/iconlayers/WindowIconProvider.java>) 是 native mono / Monet 的窄桥接，不带抽屉、图标库或生成入口；不是 full 图标能力等价品。

推送 receipt 已确认 `bd3aafb..a6da12a` 到 main。该阶段完整单测 **full 353 / window 348，均 0 失败**，双 release/R8 及 APK 原签名、身份、三系统 scope、四/七入口、快捷方式与禁止非小窗 Hook 的 DEX 边界核验通过；345 pass 是更早阶段证据。未操作设备，生成包仍是阶段检查产物。

### `004aa43` 已提交并 push 的规则状态边界

- C2 [WindowControl](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowControl.java>)：控制路由、形态/分组与几何规则，6 组测试。
- C3a [LongBackGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LongBackGesture.java>)：长返回识别状态，5 组测试；原生接纳/取消仍在适配侧。
- [OpenWaitState](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/OpenWaitState.java>)：等待请求与取消 token 归属，4 组测试。

推送 receipt：`a6da12a..004aa43` 到 main。完整双全测 **full 368 / window 363，均零失败**；基线/全部主机规则与归档接口检查通过。此阶段未重新发布 APK；之后未提交改动不得沿用该结果。

`004aa43` 当时剩余导航/Policy、恢复、tick 调度、Host admission 与 Back handoff 五闭环；其中导航/Policy、Host admission、Back handoff 已在后续阶段推进，其余恢复与窗口生命周期也已在最新阶段收口，完整证据以[最新进度](<#最新进度恢复与生命周期闭环>)为准。编译、单测、R8、当前设备回归和目标系统验证分开记录。

未来 LOS 贡献顺序：**先由我们完成 refactor → 贡献者再 fork / adapt / test 并提交 PR → 我们 review 并做现设备回归 → 最后决定 merge**。不在当前未冻结边界上承诺适配完成，不凭 ROM 名或 Freeform 开关宣称兼容。

## 138 历史审计的范围与读法

以下审计、原 PR 路线与历史检查结论保留自 `main` 提交 `930a22c`（标签 `v0.9.48-fixes-test2`，versionCode 138）；它们不是当前实施状态。各专项页也在页首区分当前进展与历史正文。**源码链接已迁移到现路径，仅供定位参考，不是 138 snapshot 的精确函数内容或行号证据**；移除可能因迁移（含 full 导航行数减少 7 行）或后续提取失准的源码行锚，旧内容请按历史提交核对。

设计讨论来源：[Issue #3：针对小窗的一些个人建议](https://github.com/AIXINJUELUOAI/NativeComplements/issues/3)。采纳其“功能逻辑与具体 Hook/执行分离”的方向，不据此承诺 LineageOS、MIUI 或任意发行版已兼容，也不默认禁用系统内置小窗。

## 阅读顺序

| 你要做什么 | 先看哪里 |
|---|---|
| 理解进程、事件、规则、执行和配置的现有关系 | [架构与边界](<architecture.md>) |
| 为其他系统接入小窗或核对 Hook | [小窗系统接入清单](<floating-window.md>) |
| 适配导航栏、图标、横屏面板、来电等功能 | [其他系统功能清单](<system-features.md>) |
| 判断独立小窗版还需要什么、如何与主版隔离 | [独立小窗依赖与拆分路线](<standalone-window.md>) |
| 提交前核对开发约束与已有设备场景 | [开发约定](<../../AGENTS.md>)、[设备验收](<../../DEVICE_ACCEPTANCE.md>) |

## 接入原则

1. **共用行为，不复制引擎。** 先明确系统事件输入、规则状态决策、平台执行与恢复，再在实际出现差异的边界增加对接；不要先给每个类创建接口。
2. **按发行版组织证据，按实际能力决定安装。** 发行版名称只是检索信息；Android 版本、组件版本、精确 ABI、调用语义、线程及转场所有权都需核验。同 API 不代表私有实现相同。
3. **把 Hook 与普通反射依赖分开。** Hook 是事件或行为入口；被调用的隐藏 API、字段、资源和原生事务同样属于适配依赖。反射统一放一个类，不等于核心已解耦。
4. **失败边界与能力同样重要。** 核心链不满足时不接管；可选子能力保持原生或局部关闭。`PROTECTIVE` 不是完整回滚保证，具体清理必须核对安装代码。
5. **只操作模块明确拥有的任务和输入流。** 系统自带小窗、PiP、权限确认、锁屏、IME 等不是可随意屏蔽的对象。先核对所有权及冲突策略，不全局关闭 Freeform。
6. **打包边界与系统适配边界分开。** 独立小窗可复用同一套核心；单改 applicationId、删首页入口或只加构建常量，均不等于独立版已完成。

## 138 原建议：最小 PR 顺序

| 阶段 | 改动边界 | 验收条件 |
|---|---|---|
| 0：目标证据 | 补目标系统的版本、组件、差异及精确签名 | 不含设备隐私；区分源码推断、归档检查、真机结果 |
| 1：一个实际差异 | 选一个可选功能或小窗输入入口，局部隔离其目标解析/安装 | 当前路径不变；目标不存在时可原生回退；有最小可运行检查 |
| 2：小窗单条闭环 | 启动请求 → 任务归属 → 呈现/输入 → 全屏恢复 | 核对调用时机、锁、事务及清理，不只改方法名字 |
| 3：扩展平台边界 | 在第二个系统的真实差异上提炼共用输入/执行契约 | 核心决策不依赖 ROM 名或反射成员；两端验收记录齐全 |
| 4：独立产物 | 身份/路径隔离、入口裁剪、冲突策略、同源构建 | 不安装其他功能 Hook，不串配置/任务恢复，不双重接管；主包覆盖兼容 |

每个 PR 只做一类事情：行为保持的边界整理、目标适配、功能改动尽量分开。不要把整库搬家、类重命名、协议改动和新功能塞进同一 PR。现有 Pixel 风格系统与新增 LOS 各自需要维护者实机验证，任何一侧只有编译通过都不算兼容完成。

## 贡献者需要提供的证据

- ROM/Android 版本、构建标识及所涉及组件的版本或摘要；包名与运行进程。设备序列号、账户、通知内容及私有截图不提交。
- Hook 的声明类、完整参数/返回、字段类型、before/after/替换语义、上游调用者；是否存在内联而需 deopt，缺失如何退出。
- 正常输入和异常输入的结果、线程/锁、原生操作完成回调、任务和 Surface 的所有权及恢复路径。
- 最小测试、当前实现回归结果、目标真机结果；未验证的部分写明“未验证”。不把源码中存在某类当成运行调用证据。
- 对于小窗：至少核对打开/缩放/mini/返回全屏、旋转与 IME、锁屏/安全确认、任务结束与恢复；按改动扩充已有 [设备场景](<../../DEVICE_ACCEPTANCE.md>)。

## 检查及证据等级

```powershell
python -X utf8 tests/verify_adaptation_docs.py
python -X utf8 tests/verify_runtime.py
# 具备本地目标归档时再执行：
python -X utf8 tests/verify_target.py
python -X utf8 tests/verify_navbar_target.py
```

- 文档检查只保证本轮 Markdown 的相对文件链接、源码行号范围和关键入口可解析，不证明描述语义或 Hook 兼容。
- [基线检查](<../../tests/verify_runtime.py>) 维护原始 Demo8.2 摘要及已登记差异，不得改基线掩盖重构。
- [归档接口检查](<../../tests/verify_target.py>) 与 [导航宿主检查](<../../tests/verify_navbar_target.py>) 依赖本地归档，缺归档不是通过；精确签名通过也不代替线程、时序及真机验收。
- 138 历史运行基线（不代表当前结果）：全量单测 311 项，307 通过，4 项失败均在既有 [SurfaceFrameTest](<../../app/src/test/java/dev/local/sunwindowdemo/SurfaceFrameTest.java>)；本轮不修它们，不宣称全量绿。新背板/莫奈针对性测试与原签名 release 检查已通过。用户对 137 其余修复的正面反馈也不等于所有系统/场景验证。

### 138 文档审计当轮 Lead 复核结果（历史）

- 五份接入文档的本地链接与源码行号检查通过；人工核对进程入口、通信授权、运行逻辑未改及各子审计关键结论。
- Demo8.2 基线与主机规则检查通过；原基线摘要、差异登记未改。
- 本地目标归档接口检查通过；导航归档检查明确 current Launcher 路径可核验、QPR2 Launcher setter 不支持，不隐去此结果。
- 没有运行新的 Gradle 构建或设备测试，因此不产生新的功能验证结论；前述 138 单测失败仍为历史基线信息。

本轮只改文档及文档自检，不重打 APK、不安装、不重启、不更改设备设置或 LSPosed 范围。
