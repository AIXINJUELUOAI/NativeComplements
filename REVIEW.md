# NativeComplements 小窗修复与性能优化：评审说明

- 仓库：<https://github.com/AIXINJUELUOAI/NativeComplements>
- 基线：`main` @ `9e8f16e`（0.9.19-signal-test1，versionCode 96）
- 分支：`perf/report-items`，相对 `main` 共 4 个提交，13 个文件，+267 / −94
- 状态：已通过编译对比和主机检查，**未在真机上验证**，未运行 Robolectric 单元测试，未做完整 Gradle 构建

## 压缩包内容

| 路径 | 内容 |
|---|---|
| `REVIEW.md` | 本说明 |
| `patches/0001-*.patch` … `0004-*.patch` | 4 个提交的 `git format-patch` 补丁，可用 `git am` 按顺序应用 |
| `changes.diff` | `main..perf/report-items` 的完整合并 diff |
| `perf-report-items.bundle` | 包含这 4 个提交的 git bundle |
| `files/` | 改动后的 13 个文件全文，目录结构与仓库一致 |

应用方式（任选一种）：

```bash
# 方式一：补丁（必须加 --keep-cr，部分 legacy 文件保留了 CRLF 行尾）
git checkout -b review main
git am --keep-cr patches/*.patch

# 或直接应用合并 diff
git apply changes.diff

# 方式二：bundle
git fetch /path/to/perf-report-items.bundle perf/report-items:perf/report-items
```

## 需要评审者了解的项目约定

摘自仓库 `AGENTS.md`：

- `features/floatingwindow/legacy/` 下的小窗代码以 Demo8.2 为不可变基线。每处修改都要在 `tests/runtime-changes.json` 里登记原因，`tests/verify_runtime.py` 会核对登记和实际改动是否一致。本次改动已全部登记。
- 只使用三个系统作用域（`system`、SystemUI、Pixel 桌面），不增加普通应用作用域，也不按包名做特判。
- 所有 Hook 都是 `ExceptionMode.PROTECTIVE`，签名在启动时预检。本次**没有新增 Hook**，只修改已有 Hook 的判断逻辑，新用到的私有成员都是 `FrameworkAccess` 已经预检过的。
- 部分 legacy 文件保留 CRLF 行尾（`.gitattributes` 中设为 `-text`）。本次改动没有改变任何已有行的行尾。

## 背景：小窗的实现方式

小窗是 freeform 任务。system_server 通过 Surface 变换把它缩放、定位到屏幕上，并向客户端下发“逻辑全屏”的配置。小窗四周“点空白处关闭”以及挡住后台触摸，靠的是 SystemUI 里的透明覆盖窗口（`WindowControls`）。SystemUI 根据 system_server 推送的帧状态（`frameReport`，经 oneway Binder）布局这些窗口。

---

## 提交 1 `b07a310`：小窗获焦时跳转到被盖住的全屏应用，保持原生全屏

**现象**：前台已经有全屏应用 B，在小窗 A 里跳转到 B（或者点 B 的通知）时，B 的全屏任务被拉进小窗。

**原因**：每次启动 Activity 前，`ShareWindow` 的 `startActivityUnchecked` Hook 会调用 `WindowEngine.nativeForeground()` 记下前台快照。只有跳转目标正好是这个快照里的任务时，`keepNativeForeground()` 才保留原生全屏。但快照取的是 `getTopDisplayFocusedLeafTask`（获得焦点的任务）。用户刚点过小窗时，焦点在小窗任务上，快照里 `owned=true`、`mode=5`，判断恒为 false，后面真正全屏显示的 B 没被记到。

**修改**：
- `NativeForeground` 新增字段 `fullscreen`：跳转前最上层的、可见的、不属于任何小窗的标准全屏叶子任务。
  - 获焦任务本身就是未托管的全屏任务时，直接用它。
  - 否则只在存在小窗会话时，才用 `forAllTasks` 从上到下扫描该屏幕。遇到第一个可见的全屏任务就停下；如果它不是标准应用类型（例如桌面），结果为空。
- `keepNativeForeground()` 在原有的精确判断之外，增加 `WindowPolicy.coveredFullscreen()`：快照中被盖住的全屏任务与焦点任务不同、等于跳转目标，且在同一屏幕上，就保留原生全屏。
- 关联跳转（`activityStarting`）、通知（`shortcutActivityStarted`）、分享（`prepareShare`）三条路径都经过 `keepNativeForeground()`。分享路径传入的目标就是 `before.task`，新判断对它恒为 false，行为不变。

**请重点看**：
1. `forAllTasks(Consumer)` 在 Android 17 上是否从上到下遍历。代码依赖这一点来确定“最上层”。
2. 保留原生全屏时直接 `return null`，交给原生启动，复用已有的全屏任务（mode 1）。如果复用的任务存在，是否还可能被套上 freeform 启动参数？

## 提交 2 `ff30885`：打开默认小窗和播放动画期间，屏蔽后台应用的触摸

**现象**：打开默认小窗的一瞬间去碰屏幕，后面的应用会被滑动。

**原因**：后台能不能被触摸，取决于 SystemUI 的覆盖窗口。这些窗口是异步出现的（帧状态 IPC → `addView` → WM 布局 → 首帧绘制），而且在非打开类动画（`transitioning && !inputPending`）期间会被全部设为 `GONE`。这几段空档里，触摸会落到后台任务上。

**修改**：在 system_server 内部加一道屏蔽，和小窗显示在同一次输入窗口更新里生效：
- `WindowEngine.blockBackgroundTouch(window)`：活动的普通小窗正在打开或播放动画时（`entering || transitioning || 非全屏 cover`），同一屏幕上其他任务的窗口返回不可触摸。没有任务的系统窗口（状态栏、导航栏、输入法）不受影响，迷你窗、关闭中、停放等状态不屏蔽。
- 屏蔽结束后再保持 250ms（`TOUCH_GUARD_GRACE_MS`），留给 SystemUI 把覆盖窗口重新画出来。
- 屏蔽最长 3s（`TOUCH_GUARD_MAX_MS`），超时自动放开，防止打开流程卡住时后台一直点不动。
- 状态在每次 `frameReport` / `report` 时更新（`trackTouchGuard()`）。宽限期结束或超时时，post 一次 `requestTraversal`，重新计算输入窗口。
- 判断时间的纯逻辑放在 `WindowPolicy.backgroundTouchGuard()`，有主机检查覆盖。
- Hook 层面只是在已有的 `WindowState.canReceiveTouchInput` Hook 里多调用一次 `engine.blockBackgroundTouch()`。没有小窗时只多一次 volatile 读。

**请重点看**：
1. `requestTraversal` 是否一定会触发 `InputMonitor.updateInputWindowsLw`。代码依赖这一点在屏蔽结束时恢复后台触摸。原有的迷你窗屏蔽也依赖同一机制。
2. 小窗打开期间，如果同一屏幕上有独立任务里的系统对话框（例如权限申请），它会被屏蔽最多“动画时长 + 250ms”。这个影响能否接受？
3. `touchGuarded`、`touchGuardSince`、`touchGuardUntil` 是 volatile，在 WM 锁内外都会读写，是否有竞态会导致屏蔽一直不放开？（3s 超时是兜底。）

## 提交 3 `b57e8f3`：性能报告里的低风险优化，以及跳转、通知判断前台的修正

对应作者另附的《0.9.10 代码与性能分析报告》（基于反编译）中的条目。

### 3.1 STATUS 心跳（`SystemUiHost`、`WindowEngine`）
- **熄屏暂停**：SystemUI 每 750ms 的 STATUS 心跳在熄屏时停止，收到 `SCREEN_ON` 或 `USER_PRESENT` 后恢复（`resumePoll()`，用 `polling` 标志避免重复调度）。熄屏广播本身仍会照常发送一次 STATUS。
- **亮屏时间隔不变**：STATUS 的结果里带着前台应用、键盘、系统浮层和输入法顶部位置，角落手势规则、弹幕暂停和快捷建议都依赖它，没有别的推送通道。
- **服务端去重**：`pushHistory(false)` 只在隐藏任务列表变化时才发 `HISTORY` 广播。桌面主动查询（`HISTORY_QUERY`）和最近任务变化（`notifyRecents`）仍然强制发送。
- **能力缓存**：`capabilities()` 中 ATMS 的两个能力标志改为缓存，30s 刷新一次，不再每次心跳都拿 WM 锁。恢复记录是否存在仍然实时检查。
- **在线判断**：`uiSeen < 3000ms` 这个判断没有改。熄屏期间它会过期，亮屏后第一次 STATUS 会刷新。依赖它的 `prepareShare`、`prepareBack` 在熄屏时本来就会被 `screenBlocked()` 拦下。

### 3.2 Hook 入口在没有小窗时提前返回
- `blockContentTouch`、`keepVisibleResumed`、`retainMiniActivity`：`sessions.isEmpty()` 时直接返回 false。
- `canAffectSystemUiFlags` Hook 和 `scheduleClientTransactionItem` Hook：`engine.idle()` 时不做反射。
- 两个 `isResizeable` Hook：先判断 `engine.mayAllowResize()`，即存在会话，或者当前线程有预检候选任务 `resizeCandidate`。
- `ownsActivityToken`：没有会话且没有进行中的分享启动（`shareLaunch`）时直接返回 false。

### 3.3 反射缓存（`FrameworkAccess`）
- `method()` 和 `field()` 去掉了 `synchronized`，改为 `ConcurrentHashMap`。键用 `(Class, name, Class[])` 组合：按 `Class` 引用和 `Arrays.equals` 比较，不再每次拼字符串。用 `putIfAbsent` 处理并发解析。
- 无参调用复用静态空数组。
- `transform`、`presentation`、`opacity`、`style(Rect)` 里的 `setScale`、`setPosition`、`setCrop`、`setAlpha` 改为直接调用公开 API。`setCornerRadius`、`setWindowCrop`、`show`、`hide` 不是公开 API，仍用反射。

### 3.4 有小窗时的开销
- **窗口归属查找**：新增 `WindowEngine.clientOwner(client)`，一次遍历找出客户端窗口所属的小窗会话，找到就跳过后续窗口。`ServerViewport` 的 relayout、`WindowStateResizeItem` 构造、`insetsControlChanged` 三个 Hook 各调用一次，再把结果传给 `clientMode(owner)` 和 `ownerInsets(owner, …)`。原来这些路径最多要遍历三遍。旧的 `ownsClientWindow`、`clientInsets` 保留，内部改为调用新方法。
- **每帧配置读取**：`apply()` 每帧只读一次 `config(task)`，圆角和装饰共用，原来读两次。

### 3.6 次要项
- `Wire.validToken` 和 `allowedPackage` 改用预编译的 `Pattern`。
- `MODULE_LOADED`、`HOOKS_READY`、`BACK_READY` 日志里写死的旧版本号，改为 `BuildConfig.VERSION_NAME`。

### 跳转逻辑的修正（同一提交）
- **关联跳转目标**：`activityStarting` 只把标准应用类型（activityType 1）的任务转成关联小窗。桌面、最近任务、助手、屏保保持原生。
- **通知判断前台**：`SystemUiHost.notificationForegroundPackage()` 改为查 3 个运行中的任务，跳过普通小窗和迷你窗两个任务。迷你窗的任务 ID 从帧状态里的 `pinnedFrame` 读取。原来只查 2 个、只跳过普通窗，同时开着两种小窗时，会把迷你窗的应用当成前台应用。

**请重点看**：
1. `MethodKey` 的 `equals` 和 `hashCode` 是否与 `Arrays.equals` 保持一致，并发场景下能否正确命中缓存。
2. `clientOwner` 的“找到即停”改成取第一个匹配，原逻辑取的是最后一个。一个客户端窗口只属于一个任务，理论上两者等价。
3. 所有“提前返回”的条件，是否漏掉了某个在 `sessions` 为空时仍需生效的场景。已知的两个特例，`resizeCandidate` 和 `shareLaunch`，都已保留。

## 提交 4 `ca6f7ed`：快捷建议跳转落在当前全屏前台任务时，保持原生全屏

**现象**：例如正在全屏用淘宝，点了“打开淘宝口令”的快捷建议，淘宝当前页面会被转成小窗。

**原因**：通过 PendingIntent 发出的跳转在 `open()` 里共用一个 `WindowNavigation.Launch` 票据，但只有通知会记录跳转前的前台快照。`Launch.keepNativeForeground()` 还要求 `expectedPackage == null`，也就是只对通知生效。

**修改**：
- 所有 PendingIntent 跳转都在发送前记录前台快照（`ticket.foreground = nativeForeground()`）。
- `keepNativeForeground()` 改为只看 `nativeTarget`。它仍然要求跳转落在跳转前的精确全屏任务里（包括提交 1 的“被盖住的全屏任务”）。
- 跳转目标是其他应用时，行为不变：照常打开小窗。

**请重点看**：原单元测试注释写着“Explicit shortcuts still open their requested window”。这次修改改变了作者原先的设计，只适用于“目标就是当前全屏前台任务”的情况。请确认这样做是否符合产品预期。

---

## 有意没改的部分

- **从扇形菜单或应用列表主动选择当前全屏应用**：仍然会把它转成小窗（`morphEntry`）。这是用户明确要求的操作，不是跳转造成的。
- **R8**：作者在 `build.gradle.kts` 里特意注释了关闭 R8 的原因：保留反射目标、Hook 入口和运行时字节码语义。
- **心跳改成变化时推送**、**拆分 `WindowEngine`**：改动面太大，不属于这次的低风险范围。
- **从小窗发起的、被排除在关联小窗之外的原生启动**（拨号、设置、选择器等）：新任务可能继承 freeform 窗口模式。这是现有行为，本次没有改，可以作为后续评估项。

## 已完成的验证

1. **编译对比**：下载 Android 37 的 `android.jar`（platform-37.0_r02），用 JDK 17 编译模块全部 Java 源码，加上一个 `BuildConfig` 桩，与 `main` 对比。**没有新增编译错误**。两边各有 44 处相同的报错，原因是缺少 Gradle 生成的 `R`、Kotlin 类和 AndroidX 依赖。Kotlin 源码没有编译。
2. **主机检查**：`tests/verify_runtime.py` 全部通过，包括基线完整性、变更登记一致性，以及全部主机检查，含新增的 `coveredFullscreen` 和 `backgroundTouchGuard` 用例。原脚本写死了 Windows 的 `javac.exe` 路径，运行时在临时副本里换成了 Linux 路径，仓库里的脚本没有修改。
3. **单元测试**：`WindowBehaviorTest`、`WindowNavigationTest` 补了用例，但没有运行，因为本机没有 Gradle 和 Robolectric 环境。

## 建议的真机验收

1. 全屏应用 B 在前台，打开 A 的小窗并点一下小窗，再从小窗里跳转到 B、点 B 的通知：B 应保持全屏，被带到前面。
2. 打开默认小窗的瞬间滑动后台：后台不应有反应。普通窗和迷你窗互相切换时，也做同样检查。
3. 小窗打开动画期间触发权限申请对话框，看能否正常点击。
4. 全屏用某个应用时，点击指向这个应用的快捷建议：应保持全屏。
5. 同时开着普通窗和迷你窗时，点全屏应用的通知。
6. 熄屏 1 分钟再亮屏：角落手势、弹幕和快捷建议应立即恢复。用 `dumpsys activity broadcasts history | grep -c nativecomplements` 对比熄屏期间的广播数量。
7. 拖动、缩放小窗，弹出键盘，切换横竖屏：画面、圆角、输入法位置和触摸都应正常。这一项覆盖反射缓存、公开 API 和每帧处理的改动。
8. 分享到小窗、关联跳转、通知打开小窗等原有功能各走一遍，确认没有回归。
