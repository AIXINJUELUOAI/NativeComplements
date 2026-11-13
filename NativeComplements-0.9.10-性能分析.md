# NativeComplements 0.9.10 代码与性能分析报告

> 分析日期：2026-11-12
> 分析对象：真机（PixelOS 17 / Android 17，设备 `dada`）上已安装的 `dev.nativecomplements.module` 0.9.10（versionCode 87）
> 分析方式：`adb pull` 提取 base.apk，jadx 1.5.3 反编译后静态分析；广播频率在真机上实测
> 参考：GitHub 仓库 [AIXINJUELUOAI/NativeComplements](https://github.com/AIXINJUELUOAI/NativeComplements) 停留在 0.2.0-preview20，没有 0.9.10 源码

---

## 结论摘要

整体架构合理：基于 libxposed API 101，在 system_server 里 Hook 窗口管理，SystemUI 承载操作层；所有 Hook 都设为 `ExceptionMode.PROTECTIVE`，启动时先检查接口签名，失败就全部撤销。

性能上有几处明确可以优化，最严重的一处已在真机上确认：**SystemUI 不论当前有没有小窗，都每 750ms 向 system_server 发一次 `STATUS` 广播，一直不停。**

| 优先级 | 问题 | 影响范围 | 收益 |
|---|---|---|---|
| 1 | 常驻 750ms STATUS 轮询 | 始终存在 | 待机功耗，减少全局锁竞争 |
| 2 | Hook 入口没有“无会话”提前返回 | 始终存在 | 没有小窗时系统几乎零开销 |
| 3 | 热路径反射拼键、加 `synchronized` | 始终存在 | 降低输入和动画路径的延迟 |
| 4 | relayout 等 Hook 每次遍历窗口 | 有小窗时 | relayout 的开销 |
| 5 | 未开启 R8，22MB dex 加载进三个系统进程 | 始终存在 | 三个进程的内存占用 |

---

## 一、基本信息

| 项目 | 值 |
|---|---|
| 包名 | `dev.nativecomplements.module`（旧版为 `dev.local.sunwindowdemo`） |
| 版本 | 0.9.10 / versionCode 87 |
| minSdk / targetSdk | 34 / 37 |
| APK 大小 | 23.25 MB |
| dex | `classes.dex` 14.4 MB、`classes3.dex` 8.2 MB、`classes2.dex` 560 B |
| Xposed API | minApiVersion=101，targetApiVersion=101，staticScope=true |
| 作用域 | `system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher` |
| 宿主协议 | 16（`Wire.PROTOCOL`） |
| 反编译规模 | 共 5382 个类；模块自身代码 170 个文件、约 5.2 万行 |

`java_init.list` 中的入口类：

```
dev.local.sunwindowdemo.SunWindowModule
dev.local.sunwindowdemo.LauncherGuard
dev.local.sunwindowdemo.BackBridge
dev.local.sunwindowdemo.features.callblocking.CallBlockingModule
dev.local.sunwindowdemo.features.iconlayers.IconLayerModule
dev.local.sunwindowdemo.features.iconlayers.SystemIconModule
```

相比仓库里的 0.2.0（只有小窗），0.9.10 增加了弹幕、来电拦截、边缘触控、图标图层、体感、横屏通知栏、建议卡片等功能。

代码规模最大的几个文件：

| 文件 | 行数 |
|---|---|
| `WindowEngine.java` | 6599 |
| `core/ui/SettingsComponentsKt.java` | 2232 |
| `SystemUiHost.java` | 1817 |
| `features/iconlayers/IconLayerScreenKt.java` | 1672 |
| `navigation/ModuleAppKt.java` | 1667 |
| `AppList.java` | 1622 |
| `SuggestionHost.java` | 1469 |
| `WindowControls.java` | 1337 |

---

## 二、实现方式概览

### 2.1 进程分工

- **system_server**（`SunWindowModule`）：在 `onSystemServerStarting` 里构造 `FrameworkAccess`（一次性解析并校验约 150 个私有方法和字段），然后创建 `WindowEngine`，挂约 30 个 Hook。
- **SystemUI**（`SystemUiHost`）：承载小窗操作条、mini 窗、手势、长按菜单等 UI。
- **桌面**（`LauncherGuard`、`IconLayerModule`）：最近任务过滤、图标图层。

### 2.2 进程间通信（`Wire`）

- SystemUI 到 system_server：显式广播 `dev.nativecomplements.module.COMMAND`（`setPackage("android")`，`setShareIdentityEnabled(true)`）。
- system_server 到 SystemUI 和模块应用：广播 `dev.nativecomplements.module.RESULT`。
- 高频控制：oneway Binder 通道（`controlChannel` / `frameChannel`，`Wire.transmit` / `frameReport`），失败时回退到广播。
- 存活租约：SystemUI 在 Bundle 里传 `LEASE` Binder，服务端用 `isBinderAlive()` 加 `uiSeen < 3000ms` 判断宿主是否在线。

### 2.3 system_server 主要 Hook 点

| 类 | 方法 | 用途 |
|---|---|---|
| `WindowManagerService` | `systemReady` | 挂接 `WindowEngine` |
| `Task` | `prepareSurfaces` | 每帧应用小窗缩放、位置、裁剪、圆角 |
| `Task` | `onConfigurationChanged` / `computeFreeformBounds` | 几何刷新、逻辑尺寸 |
| `Task` | `moveTaskToBack` / `moveTaskToBackInner` / `isResizeable` | 返回关闭、强制可缩放 |
| `RecentTasks` | `isVisibleRecentTask` | 最近任务隐藏 |
| `InsetsPolicy` | `updateSystemBars` | 保持沉浸 |
| `WindowState` | `finishDrawing` / `canReceiveTouchInput` / `canReceiveKeys` / `canAffectSystemUiFlags` | 首帧遮罩、mini 输入屏蔽 |
| `ActivityRecordInputSink` | `getInputWindowHandleWrapper` | 屏蔽输入 |
| `TaskFragment` / `ActivityRecord` | `canBeResumed` / `shouldBeResumed` / `shouldPauseActivity` | 可见 mini 多 resume |
| `Transition` | `onTransactionReady` / `buildFinishTransaction` / `collect` | 转场接管 |
| `TransitionController` | `requestStartTransition` | 转场请求观察 |
| `BackAnimationAdapter` | `isAnimatable` | 关联返回预览 |
| `ActivityStarter` | `handleStartResult` | 启动路由、快捷方式启动 |
| `WindowManagerService` | `relayoutWindow` | 下发逻辑视口配置和 Insets（`ServerViewport`） |
| `WindowProcessController` | `scheduleClientTransactionItem` | 进程级配置改写 |
| `IWindow$Stub$Proxy` | `insetsControlChanged` | Insets 改写 |
| 各 `ClientTransactionItem` | 构造函数 | Launch / Relaunch / Configuration 改写 |
| `PowerManagerService` | `userActivityNoUpdateLocked` | 唤醒建议 |

另外对 `makeActiveIfNeeded`、`shouldResumeActivity`、`relaunchActivityLocked` 调用了 `deoptimize`。

### 2.4 优点

- 每个 Hook 都是 `PROTECTIVE` 模式，Hook 内部异常不会让 system_server 崩溃。
- `FrameworkAccess` 构造时做完整签名预检，任一失败就整体撤销（`DISABLED signature-preflight`），避免半装载状态。
- `sessions` 是 volatile 的不可变 List（`List.copyOf`），读路径无锁。
- `Wire` 的帧通道做了合并投递（`pendingFrame` + `framePosted`），避免主线程消息堆积。
- 每帧 Surface 修改合并到一个复用的 `SurfaceControl.Transaction`（`beginFrame` / `endFrame`）。
- 单次反射结果有缓存，且服务端 Binder 入口校验 `callingUid == 1000`。

---

## 三、问题与优化建议

### 3.1 常驻 750ms STATUS 轮询（优先级 1，真机已确认）

`SystemUiHost.java`：

```java
this.poll = new Runnable() {
    public void run() {
        if (SystemUiHost.this.cleaned) {
            return;
        }
        Wire.send(SystemUiHost.this, "STATUS");
        SystemUiHost.this.main.postDelayed(this, 750L);
    }
};
// initialize() 末尾
this.main.post(this.poll);
```

这个轮询只在 SystemUI 宿主被清理（`cleaned = true`）时才停，熄屏、没有小窗时都照常运行。

**真机实测**（`dumpsys activity broadcasts history`）：

```
enq=2026-11-12 20:33:18.120
enq=2026-11-12 20:33:17.368
enq=2026-11-12 20:33:16.613
enq=2026-11-12 20:33:15.860
enq=2026-11-12 20:33:15.107
enq=2026-11-12 20:33:14.354
enq=2026-11-12 20:33:13.601
enq=2026-11-12 20:33:12.848
```

COMMAND 间隔稳定在约 753ms；历史缓冲中 COMMAND 和 RESULT 各 403 条，一一对应。

**每轮在 system_server 中的开销**（`WindowEngine.command` 处理 `STATUS`）：

1. `pushHistory()`：在 `mGlobalLock` 下计算 `hiddenIds()`，然后发一条 `HISTORY` 广播给桌面。
2. `report("STATUS")`：
   - `capabilities()`：获取 `mGlobalLock`，反射读两个字段，拼字符串，检查 journal 文件是否存在。
   - `environment()`：获取 `mGlobalLock`，约 12 次反射调用，包括 `getTopDisplayFocusedLeafTask`、`getImeWindow`、`getInsetsStateController().getRawInsetsState().calculateInsets(...)`。
   - `backgroundImmersive()`：有会话时再获取一次 `mGlobalLock`，加上 4 次反射。
   - `journal.getBaseFile().exists()` 调用两次，是文件系统 stat。
   - 构造包含约 40 个 extra 的 Intent。
   - 发两条 RESULT 广播：一条给模块应用（带权限），一条给 SystemUI。

合计每 750ms 约 4 条广播，即每秒 5 条以上、每天约 46 万条。system_server（AMS 广播队列、WMS 全局锁）和 SystemUI 主线程被持续唤醒，会影响待机耗电，也会和窗口动画争抢 `mGlobalLock`。

**优化建议：**

1. **心跳改用 Binder 死亡通知。** SystemUI 已经通过 Bundle 传了 `LEASE` Binder，服务端直接调用 `lease.linkToDeath(...)`，不需要靠 `uiSeen < 3000` 判断连接。
2. **状态改为变化时推送。** 现有的 oneway Binder 通道（`frameSink` / `controlChannel`）已经被 `frameReport` 使用，`report` 也可以走这个通道，不再用广播。
3. **如果暂时保留轮询：**
   - 熄屏（`SCREEN_OFF`）和没有活动会话时暂停，`USER_PRESENT` 或有会话时再恢复。`screenReceiver` 已经注册，直接接入即可。
   - 间隔放宽到 5–10 秒。
   - `pushHistory` 只在 `hiddenIds` 变化时发送。
   - `capabilities()` 的结果在 `systemReady` 后不会变，缓存起来即可。

### 3.2 Hook 入口没有“无会话”提前返回（优先级 2）

很多 Hook 在确认“当前没有小窗”之前就先做反射。例如 `WindowEngine.blockContentTouch`：

```java
boolean blockContentTouch(Object obj) {
    try {
        Session sessionSessionFor = sessionFor(this.a.call(obj, "getTask"));
        if (sessionSessionFor != null) {
            ...
```

它挂在 `WindowState.canReceiveTouchInput`、`WindowState.canReceiveKeys`、`ActivityRecordInputSink.getInputWindowHandleWrapper` 上。这些方法在每次更新输入窗口时（动画期间几乎每帧）都会对每个窗口调用。

同类问题：

| 位置 | 无会话时仍执行的操作 |
|---|---|
| `canAffectSystemUiFlags` Hook | `frameworkAccess.call(this, "getTask")` |
| `ActivityRecord.isResizeable` Hook | `frameworkAccess.call(this, "getTask")` |
| `keepVisibleResumed` / `retainMiniActivity` | `call(obj, "getTask")`、`call(obj, "getTaskFragment")` |
| `ServerViewport` 构造函数 Hook → `ownsActivityToken` | 获取 `synchronized(mGlobalLock)` 并反射调用 `forTokenLocked` |
| `scheduleClientTransactionItem` Hook | `field.get` 加 `call(obj, "getTask")` |

**优化建议：** 每个 Hook 第一行加一句：

```java
if (windowEngine.sessions.isEmpty()) return chain.proceed();
```

`sessions` 已经是 volatile 的不可变 List，读取没有成本。没有小窗时，整套 Hook 的开销就降到只剩一次 volatile 读。`ownsActivityToken` 还要同时检查 `shareLaunch.get() == null`。

### 3.3 热路径反射开销（优先级 3）

所有动态反射都经过 `FrameworkAccess.method()`：

```java
synchronized Method method(Class<?> cls, String str, Class<?>... clsArr) throws Exception {
    String str2 = cls.getName() + "#" + str + Arrays.toString(clsArr);
    Method method = this.methods.get(str2);
    if (method != null) {
        return method;
    }
    ...
}

Object call(Object obj, String str) throws Exception {
    return method(obj.getClass(), str, new Class[0]).invoke(obj, new Object[0]);
}
```

问题：

1. **每次调用都拼缓存键。** `Arrays.toString(clsArr)` 会把每个参数类型转成 `"class android.view.SurfaceControl"` 这样的字符串；即使命中缓存，每次调用也会分配 3–5 个临时字符串和一个 StringBuilder。
2. **整个方法加了 `synchronized`。** Binder 线程、`android.anim`、`android.display` 等线程上的所有反射调用都在同一个对象锁上串行执行；而且经常是在已持有 `mGlobalLock` 的情况下再获取这把锁。
3. **按运行时类查找。** `obj.getClass()` 取到的是具体子类，同一方法会按子类重复缓存，第一次查找还要沿父类链抛出并捕获多次 `NoSuchMethodException`。
4. **每次调用都分配。** `new Class[]{...}`、`new Object[0]`、装箱的 `Float` / `Integer`。

**优化建议：**

- 热路径上用到的 `Method` / `Field` 在 `FrameworkAccess` 构造时就存成 `final` 字段（构造函数里已经全部解析过一遍，只是没保存），例如 `WindowState#getTask`、`WindowContainer#getBounds`、`getDisplayContent`、`isAttached`、`mSurfaceControl`、`mSurfaceAnimator`、`mTransitionController`。
- 如果仍需要动态查找，改用 `ConcurrentHashMap`，键用 `(Class, name, paramTypes)` 组合对象或者每个调用点一个静态缓存，并去掉 `synchronized`。
- 无参调用复用一个静态空数组。

### 3.4 有小窗时的额外开销（优先级 4）

#### relayout 等 Hook 每次遍历窗口

`ServerViewport` Hook 了 `relayoutWindow`、`insetsControlChanged` 和各类 `ClientTransactionItem` 构造函数。有小窗时，系统里**任意**窗口每次 relayout 都会：

1. `ownsClientWindow(iwindow)`：在 `mGlobalLock` 下对每个会话反射调用 `forAllWindows`，遍历小窗任务的所有窗口，对每个窗口反射读 `mClient`。
2. 命中后，`clientMode(...)` 再遍历一遍。
3. `clientInsets(...)` 再遍历一遍。

一次 relayout 最多遍历三遍。`clientInsets` 还会通过反射复制 `InsetsState`，并逐个 source 调用 `setVisible` / `setFrame` 等，全部是反射。

**优化建议：** 在 `Session` 里维护一个 `IdentityHashMap<IBinder, WindowState>`，在 `WindowState` 添加和移除时更新（或者在 `finishDrawing`、`relayoutWindow` 首次命中时懒加载）。这样归属查找变成 O(1) 且不需要加锁；`ownsClientWindow`、`clientMode`、`clientInsets` 可以合并为一次查找，返回 `Session`。

#### 每帧 `apply()` 分配较多

`prepareSurfaces` 每帧都会触发 `prepared()` → `apply()`，一次大约 20 次反射调用：

- `bool(task, "isAttached")`、`presentationLeashed`（2–3 次）、`call(parent, "getBounds")`
- `transform()`：`field(mSurfaceControl)`、`transaction()`、反射调用 `setScale` 和 `setPosition`
- `config(task)` **调用了两次**（计算圆角和 density 各一次）
- `getTopVisibleAppMainWindow`、`getFrame`、`getBounds`
- `style()`：反射调用 `setCrop` 和 `setCornerRadius`
- `opacity()`：反射调用 `setAlpha`

其中 `SurfaceControl.Transaction` 的 `setScale`、`setPosition`、`setCrop`、`setCornerRadius`、`setAlpha` 都是公开 API，可以直接调用。代码里 `presentation()` 已经直接调用了 `transaction.setAlpha(...)`，而 `opacity()` 还在用反射，写法不统一。

**优化建议：**

- Transaction 操作改为直接调用公开 API。
- `config(task)` 在一帧内只取一次，`densityDpi` 缓存到 `Session` 里，配置变化时再更新。
- `new Rect(...)` 改为复用 `Session` 上的临时 Rect。
- `prepared()` 中 `rect2.equals(new Rect(0, 0, iRound, iRound2))` 可以改为直接比较宽高。

### 3.5 包体与内存（优先级 5）

APK 有 23MB，其中两个 dex 合计约 22MB，类名完全没有混淆，说明发布版仍是 `isMinifyEnabled = false`（与 0.2.0 仓库的 `build.gradle.kts` 一致）。

影响比看上去大：LSPosed 会把模块 dex 加载进 **system_server、SystemUI、桌面** 三个进程，而 Compose、Material3、Kotlin 协程、material-icons 这些库只有设置界面才用得到。

**优化建议：**

- **开启 R8**，keep 规则需要覆盖：
  - `java_init.list` 中列出的入口类
  - 所有 `XposedInterface.Hooker` 实现
  - 反射访问模块自身成员的地方（例如 `BuildConfig`、通过名字查找的内部类）
  
  预计 dex 能缩小到几 MB。
- **或者拆分模块**：把 Hook 代码放进一个不依赖 Compose 的独立 Gradle 模块，或者独立 dex，入口只加载它。

### 3.6 次要问题

| 问题 | 位置 | 建议 |
|---|---|---|
| 永久 deoptimize 三个生命周期方法 | `makeActiveIfNeeded`、`shouldResumeActivity`、`relaunchActivityLocked` | 这是为了让 Hook 生效的取舍，开销可以接受，建议记录原因 |
| 每次调用都重新编译正则 | `Wire.validToken`、`Wire.allowedPackage` 使用 `String.matches` | 预编译为 `static final Pattern` |
| 日志版本号过时 | `MODULE_LOADED ... version=0.4.0-preview1`、`HOOKS_READY ... nativecomplements-0.3.0-preview6` | 改用 `BuildConfig.VERSION_NAME`，避免排查时误导 |
| 每次发命令都 new `Config` | `Wire.send` / `sendRequest` | 开销小（复用 `hostPreferences`），但可以复用一个实例 |
| `note()` 同时写 `Log.i` 和 LSPosed 日志 | `SunWindowModule.note` | 高频路径（例如 `VISIBLE_WINDOW_CROP`）建议加开关 |
| `WindowEngine` 单类 6600 行 | `WindowEngine.java` | 状态标志多达几十个，后续优化和维护风险高，建议按会话状态机、Surface 呈现、输入、转场拆分 |

---

## 四、建议的验证方式

以上结论中，除广播频率外，其他都来自静态分析，没有在真机上做性能采集。量化收益时建议：

1. **Perfetto 追踪**（开启 `sched`、`binder_driver`、`am`、`wm`、`view`、`gfx`）：
   - system_server 的 binder 线程、`android.anim`、`android.display` 线程
   - `mGlobalLock` 的 monitor contention（`lock contention` slice）
   - SystemUI 主线程的唤醒频率
2. **待机对比**：熄屏 30 分钟，对比修改前后 `dumpsys batterystats` 中 system_server 和 SystemUI 的 CPU 时间、唤醒次数。
3. **广播计数**：`adb shell dumpsys activity broadcasts history | grep -c nativecomplements`
4. **内存**：`adb shell dumpsys meminfo system_server` / `com.android.systemui`，对比开启 R8 前后的 `.dex mmap`。

---

## 五、说明

- 反编译工具：jadx 1.5.3（`--deobf-min 2 --show-bad-code`），共 38 处反编译错误；`SunWindowModule.onSystemServerStarting` 等少数方法反编译不完整，控制流以其中较完整的分支为准。
- GitHub 仓库停留在 0.2.0，没有 0.9.10 源码，以上问题只能反馈给作者，或在拿到新源码后再修改。
- 文中代码片段均来自反编译结果，变量名为 jadx 自动生成。
