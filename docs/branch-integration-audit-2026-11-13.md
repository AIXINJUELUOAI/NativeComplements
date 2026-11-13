# 分支与交付遗漏核对，2026-11-13

前文记录补回前的分支核对结果。用户随后要求以上午分支完整状态为优先，补回实施结果见末节。

## 结论与核对范围

当前 checkout 为 main，HEAD 为 9e8f16e38ff760c117112011ba22143c52d3364c。本轮导航栏等变更仍为未提交工作，已交付的 0.9.21-test1 由当前工作目录构建。不能将 HEAD 的版本直接当作当前 APK 的完整源码状态。

核对了全部 16 个本地分支、13 个已登记 worktree，并检查每个工作目录的已修改和未跟踪文件。以当前 main 工作目录的实际代码为比较对象；对旧分支同时核对祖先关系、补丁等价性和后续替代实现。

确定的主要遗漏集中在 build/wt-review-0920：其 4 个提交及多项未提交修复未进入当前交付。review/upload-0920 与 fix/review-0920-touch-guard 指向同一个 ca6f7ed，并非两套不同成果。

## 仍缺少或仅部分带回的内容

下表“缺少”指当前源码缺少上午对应实现，不代表每个场景都已在真机复现。来源均为 fix/review-0920-touch-guard 工作目录。

| 项目 | 上午已有的内容 | 当前状态与依据 |
|---|---|---|
| R8 | release 启用 R8，保留模块类及宿主 Kotlin/AndroidX 反射名称；另有最终 DEX 名称检查脚本 | 缺少。app/build.gradle.kts:28 仍为 false；app/proguard-rules.pro、tests/verify_r8_host_names.py 均不存在。上午采用保守规则，不是直接照抄根目录原始 R8 补丁。 |
| 打开及变换时的后台触摸保护 | 在系统输入判断中挡住普通小窗背后的任务，含 250ms 宽限和 3s 上限；未提交部分进一步按窗口会话重置计时 | 缺少。SunWindowModule 未接入 blockBackgroundTouch；WindowEngine、WindowPolicy 中对应方法缺失。当前遮罩优化没有补上这套输入保护。 |
| 被小窗盖住的全屏任务保留 | 小窗获得焦点后，跳转目标若正是被盖住的全屏任务，继续走原生全屏 | 缺少。NativeForeground 仍没有 fullscreen 字段，也没有 visibleFullscreen、coveredFullscreen 判断。来源提交 b07a310。 |
| 快捷建议跳回当前全屏应用 | PendingIntent 快捷建议也记录启动前全屏任务，命中同一任务时不拉进小窗 | 缺少。WindowEngine:577 仍只对 notificationLaunch 记录 foreground；WindowNavigation 仍保留 expectedPackage 限制。来源提交 ca6f7ed。 |
| 反射和 Surface 热路径优化 | ConcurrentHashMap 与结构化键缓存，复用无参数组；公开 Surface API 直接调用 | 缺少。FrameworkAccess 与 main HEAD 相同，上午该文件的修改未带入。 |
| Hook 空闲快速返回与归属查找复用 | 无会话提前返回；保留 resizeCandidate/shareLaunch 特例；一次 clientOwner 查找供 mode 与 Insets 共用 | 缺少。ServerViewport、SunWindowModule 未带入；WindowEngine 没有 idle、mayAllowResize、clientOwner、ownerInsets。每帧配置读取这一小部分已被本轮新的遮罩/装饰实现合并，不能整文件覆盖。 |
| 心跳及广播开销优化 | 熄屏暂停 STATUS，亮屏恢复；隐藏任务历史变化后才广播；静态能力缓存 30s | 缺少。SystemUiHost:60 仍每 750ms 无条件调度；WindowEngine STATUS 仍直接 pushHistory，能力查询仍走旧实现。 |
| 通知前台识别 | 查 3 个任务，跳过普通小窗与迷你窗，定位后方真正的全屏应用 | 缺少。SystemUiHost:498 仍只查 2 个任务，没有 pinnedTask 跳过逻辑。main 已有较早的普通小窗排除修复，上午是在其上补充迷你窗情况。 |
| 宿主初始化失败恢复 | 部分注册失败逐个清理；失败后复位 live/connected；BackBridge 3s 重试且避免重复注册/重试 | 缺少。BackBridge.connect 仍在 context 非空时返回；SystemUiHost 保留旧 cleanup 路径。长侧滑开关与派发取消已带回，但这一配套恢复逻辑没有带回。 |
| 自定义图标持久化与清理 | 图标导入/生成/发布串行，保存成功后才清理未引用图片，读取与清理互斥 | 缺少。IconPolicy:60 仍 apply；IconOverrides 无 prune；IconAppScreen、IconLayerProvider 均未带回相关修改。 |
| 弹幕历史保存失败处理 | commit 结果沿 Provider 返回；只有历史保存成功后才取消原通知 | 缺少。BarrageProvider:23 仍直接返回 saved=true，BarrageStore 未返回 commit 结果。缺少的环节是保存结果回传，现有宿主已有基于 saved 的取消判断。 |
| 快捷建议读配置失败的常亮释放 | 读取失败时清空 awakeUntil，并 releaseAwake，再撤掉界面和恢复旋转 | 缺少。SuggestionHost:160 仍没有 awakeUntil=0、releaseAwake。 |
| 日志版本号同步 | MODULE_LOADED、HOOKS_READY、BACK_READY 使用 BuildConfig.VERSION_NAME | 缺少。相关 legacy 入口仍有写死的旧版本字符串，可能干扰后续定位。 |

以上缺失既包含 4 个已提交修复，也包含上午 R8、图标清理、异常恢复等未提交工作。只合并 ca6f7ed 及其历史，仍会漏掉后者。

上午对应的 WindowBehaviorTest、WindowNavigationTest、WindowPolicyChecks、图标/管理页测试增量，及 PersistenceFailureTest、R8 DEX 检查等也未完整带回。ConfigMigrationTest 已带回，并补了长侧滑派发取消检查；BackHandoffTest 的初始化重试测试仍缺少。此前 195 项测试通过覆盖的是当前集成内容，没有覆盖这些缺失实现。

## 已带回、已集成或明确被后续替换

| 分支或工作目录 | 核对结果 |
|---|---|
| main | 当前导航栏独立页、采样与截图连续性、横屏透明开关、主页图标、通知图标尺寸、遮罩缓存与替换处理均保留在工作目录。 |
| fix/review-0920-touch-guard | longBackWindow 配置、旧安装迁移、设置页、Provider 读取、提示、触发准入与排队后的取消已带回。宿主失败重试仍缺少，见上表。 |
| review/upload-0920 | 与上述评审分支同一提交，仅为别名，没有独立工作目录或额外成果。 |
| feature/release-call-enhancements | 来电增强已集成。4 个生产文件与旧目录完全相同；CallBlockingModule 当前还增加了诊断接入。 |
| feature/release-diagnostics | 诊断功能和测试已集成；当前版本还改为设置变化通知刷新，并补充时间标记。旧目录的轮询实现不应覆盖回来。 |
| feature/release-task-swap | 旧互换方案在 8f4bb84 集成，随后 875f0d1（0.9.17）明确删除 SwapPolicy，迷你窗双击改为 FULL。属于后续替代，不列作遗漏。 |
| feature/simple-rotation-no-swap | 删除互换及简化旋转的后续方案已进入 main。当前旋转图标还有后续修改，不能按字节差异判成遗漏。 |
| feature/landscape-shade | 原提交经等价补丁集成；git cherry 标记为等价。 |
| fix/auto-mini-switch | 自动最小化开关经等价补丁集成。 |
| fix/menu-suggestions | 两个提交均有等价补丁。 |
| fix/window-behavior | 窗口行为修复有等价补丁。 |
| feature/call-blocking | 分支提交已在 main 历史中，工作目录干净。 |
| feature/icon-adaptation-059 | 分支提交已在 main 历史中，工作目录干净。 |
| feature/window-stability | 分支提交已在 main 历史中，工作目录干净。 |
| fix-signal-display | 已在 main 历史中，无独立未提交工作目录。 |
| refactor/unified-window-ops | 已在 main 历史中，无独立未提交工作目录。 |

## 实际安装包交叉核对

读取两个 APK 的 DEX，而非只比较分支名。上午 0.9.20-r8-test4 存在 WindowEngine.blockBackgroundTouch、clientOwner、WindowPolicy.coveredFullscreen、IconOverrides.prune；当前 0.9.21-test1 四者均缺失。上午 APK 的 verify_r8_host_names.py 检查也重新执行通过。

| 安装包 | 大小 | SHA-256 |
|---|---:|---|
| build/wt-review-0920/deliverables/NativeComplements-0.9.20-r8-test4.apk | 6,133,236 bytes | 1323cda79774aa01a8354c96bb12774300a189efdfa527a92eee4e4bbdc64195 |
| deliverables/NativeComplements-0.9.21-test1.apk | 23,492,811 bytes | 67aca76467678e88f8010dd372a724999e6fc8f03889bc0bc075d8559994a57c |

两者还包含其他功能差异，不能把全部体积差直接归因于 R8，也不能据此推导真机内存或性能收益。

## 后续整合约束

保留当前 main 的导航栏、遮罩和通知图标变更，逐项带回评审分支及其未提交修复，并同步相应测试与 runtime-changes 登记。先做兼容整合和必要验证，再从统一目录打包。根目录 0001 至 0006 是原始建议补丁，与上午评审后的实现有差别，不宜整批直接应用。特别是原始触摸补丁含立即抢焦点，而上午实际采用了带超时的输入保护；两者不应重复叠加。

## 0.9.21-test2 补回实施结果

已在 main 工作目录整合上午 fix/review-0920-touch-guard 的 4 个提交和全部未提交修复，包括 R8 保留规则、图标持久化与清理、弹幕保存结果、宿主失败恢复、常亮释放及配套测试。版本代码为 107。

按上午工作目录枚举的 33 个文件逐一核对，28 个文件内容完全一致。其余 5 个文件分别为新版本号所在的 Gradle 配置、额外接入本轮遮罩更新的 WindowEngine、保留本轮新增测试的 ConfigMigrationTest、累计修改原因的 runtime-changes.json，以及扩展最终 DEX 恢复方法检查的 verify_r8_host_names.py。WindowEngine 的差异已逐段核对，上午的触摸保护、前台任务判定、缓存、快速返回、归属查询和心跳优化均已接入。

216 项测试全部通过，失败、错误、跳过均为 0。Demo8.2 基线与主机检查、归档目标接口检查、导航栏接口与模板检查通过。发布构建实际执行 minifyReleaseWithR8，生成 R8 mapping；最终 APK 的 Kotlin/Compose 宿主反射名称、恢复的运行方法、导航栏入口、原签名和三个系统作用域检查通过。发布脚本已接入上述 R8 和最终 DEX 检查。

交付 deliverables/NativeComplements-0.9.21-test2.apk，SHA-256 为 47a354b650af8b17c2eb8ff14becc8a9db8d2bf4141f3c1a0c663d3009b0f72e。构建日志位于 verification/package-0921-test2.log，测试日志位于 verification/test-0921-test2.log。真机运行效果仍待用户覆盖安装并手动重启后验证。
