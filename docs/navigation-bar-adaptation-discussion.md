# 导航栏适配讨论记录

更新日期 2026-11-13。

## 已确认方向

- 通用适配与 Activity 独立规则结合。用户选择应用及 Activity，为不同页面选择取色、保留系统原有、悬浮三种模式。
- 取色模式从导航栏上方的应用内容获取代表色。颜色复杂时优先考虑主色，结果不可靠时深色页面使用黑底、浅色页面使用白底，再根据最终背景决定小白条明暗。
- 保留系统原有模式沿用页面原本的导航栏处理。
- 悬浮模式期望页面内容延伸到底部，导航栏背景透明，小白条浮在内容上。需要验证 Insets、底部控件遮挡和键盘切换。
- 提供热门应用的内置默认模板，开放用户按 Activity 配置和覆盖模板，减少提交适配请求、等待开发者更新发布的环节。
- 匹配优先级为用户 Activity 规则、内置 Activity 模板、通用默认策略。
- 一个 Activity 可能包含多个页面，Activity 粒度的覆盖能力存在边界。
- 三个系统作用域是早期设计选择，用户允许根据实际需要评估扩大作用域，实施时需同步项目约定。

## 待确认提议

- 应用默认模式与 Activity 跟随选项、当前页面 Activity 识别、模板导入导出均为助手提议，具体界面与格式尚待确定。

## 当前阶段

当前为 0.9.20-navbar-test3 / versionCode 102。用户明确要求本轮实现普通应用免勾选，因此运行链路切换至 system_server 与 SystemUI，恢复 staticScope=true，保留三个既有默认系统作用域。首次升级需用户手动重启设备加载系统宿主，此后规则修改经 Provider 通知实时下发。

系统框架侧从前台 WindowState 取得 Activity、原始 Insets 与 Surface，普通应用进程没有新增模块 Hook。使用系统原生 IWindow DUMP_ENCODED 接口取得几何层级，按可见性、宽度、高度、贴底位置及滚动容器排除规则识别固定底栏；层级解析失败时使用底部像素兜底。层级获取和 GPU 读回均在独立 worker 执行，WM 锁内只读取快照与通知 Insets 更新。

取色通过 API37 ScreenCaptureInternal.captureLayers 读取应用 Surface 的 1 像素高细线，输出 48 个颜色样本，沿用稳定确认。固定底栏采用其底部上方约 3px 的线并避开左右 29px，兜底采用导航栏上方 2px 的线、左右各缩进 0.2%。采样间隔 400ms，原有模式及熄屏停止颜色读回。SystemUI 原生导航窗口绘制背景，原生小白条明暗入口按最终背景处理。悬浮在系统侧复制每窗口 InsetsState，移除导航栏来源；键盘、横屏、多窗口、锁屏与受保护窗口保留原有处理。后台消息超过 1.5 秒失效时，SystemUI 恢复原背景和小白条原明暗值。

模板合并自已提取 ROM 的 212 包 / 926 条 Activity 规则与 HyperNavBar Rules 社区的 399 包 / 562 条规则，最终数据为 399 包 / 1352 条 Activity 规则，版本信息与源文件 SHA256 在 assets/navbar/templates.json 内。22 条不支持的固定透明色或异常规则被跳过。view、sf、default 统一映射至本模块系统侧底栏识别与细线取色；小米专有 Dialog、Popup、viewName 及提示线显隐字段未引入。数据的转换覆盖与当前手机上每个页面的验证分开。

用户 Activity 规则优先于用户应用默认规则，之后匹配内置模板的精确 Activity、具体通配符、星号默认项，再使用通用默认模式。可关闭内置模板，查看规则来源。社区 MIT 许可已随资产保存并登记 NOTICE 和应用开源许可页。来源 [HyperNavBar Rules](https://github.com/HyperNavBar/HyperNavBarRules)。

已有取色、稳定性、配置隔离、模板匹配、原生层级解析与 Insets 克隆测试，API37 方法签名通过当前归档检查。系统侧完整运行效果仍需升级并手动重启后验证。

### 早期验证记录

用户于本聊天后续确认开始。已实现 0.9.20-navbar-test1 最小验证版，入口放在开发者工具，默认关闭。包含通用默认模式和 Activity 独立规则，提供取色、保留系统原有、悬浮。三个默认系统作用域保留，staticScope=false 允许用户额外选择目标应用。
取色验证采用应用 View 底部 48 个采样像素、16 级每通道量化和主色簇统计；主色占比不足时按平均亮度退回黑白。采样随绘制触发、间隔至少 400ms。遇到独立 SurfaceView/TextureView 或无有效颜色时恢复原有处理。
test2 调整采样横线左右各缩进宽度的 5%，两端颜色接近时优先使用两端代表色。已应用背景与候选颜色每通道差值不超过 16 时保持原色；更大变化需要两次接近的候选结果确认。存在待确认颜色时延后 400ms 再取一次，关闭或恢复时取消确认并清空状态。
用户真机反馈 test1 开启通用取色后已正常生效。淘宝商品详情页圆角两侧黑边在保留系统原有模式下仍存在，用户已确认，该处属于应用页面自身表现。test2 的跳色改善仍待真机验证。
悬浮验证使用透明背景、decorFitsSystemWindows=false，并在根 DecorView 的 Insets 分发阶段清零 navigationBars 底部当前及稳定 Insets。暂停、失焦、键盘、锁屏、多窗口、横屏、受保护窗口时恢复。具体设备效果尚待验证。
热门应用默认模板、模板导入导出与当前页面识别尚待后续实现和验证，初版没有加入未经验证的应用规则。
分析材料位于 verification/hyperos-navbar。静态分析结论与真机验证分别记录。

## HyperOS 已核对机制

- MiuiNBIManagerImpl 解析应用内的 Activity 规则，控制器支持精确类名、通配符与默认规则。
- Activity 模式包括禁用处理、固定颜色或视图取色、强制沉浸，另有采样开关和 Dialog、Popup 规则。
- 背景颜色通过 PhoneWindow 与 DecorView 更新。手势小白条通过独立区域亮度采样调整明暗。
- 强制沉浸由 NavigationBarImmersiveController.setNavigationBarForceImmersive 设置状态并调用 DecorView.updateColorViews。
- DecorView.calculateNavigationBarColor 在强制沉浸时返回透明色。updateColorViews 的强制沉浸分支将 navigationBars 与 captionBar 的底部 Insets 清零。
- 已进一步用 DecorView.smali 核对关键分支。calculateNavigationBarColor 在强制沉浸时直接返回 0；updateColorViews 在该状态下构造底部为 0 的 captionBar Insets 和 navigationBars Insets，并写回 mLastInsets。因此透明背景与释放底部布局空间有字节码依据。Java 反编译存在重复分支及类型恢复异常，具体应用的布局、遮挡与恢复效果仍需真机验证。
- 小米像素颜色采样依赖 NBI 服务转发至 CompositionSamplingListener 的 JNI 扩展。native 实现与当前类原生系统的对应能力仍待验证。

## 证据文件

- verification/hyperos-navbar/miui-framework-source/com.android.internal.policy.NavigationBarImmersiveController.java
- verification/hyperos-navbar/miui-framework-source/com.android.internal.policy.MiuiNBIManagerImpl.java
- verification/hyperos-navbar/framework-source/com.android.internal.policy.DecorView.java
- verification/hyperos-navbar/framework-source/com.android.internal.policy.DecorView.smali
- verification/hyperos-navbar/systemui-source/com.android.systemui.navigationbar.views.NavigationBar.java
- verification/hyperos-navbar/systemui-source/com.android.wm.shell.shared.handles.RegionSamplingHelper.java

## test4 修正，2026-11-13

用户升级并重启 test3 后反馈没有生效。ADB 确认已安装 versionCode 102，总开关开启、系统框架连接状态已记录、SystemUI 的 NAVBAR_FRAME 接收器存在。保持淘宝首页亮屏时，系统为手势导航（navigation_mode=2）、Awake、Keyguard showing=false，运行日志仍在 tick 的 unavailable 前置分支退出。test4 改为优先读取 SystemUI 写入的 Settings.Secure.navigation_mode，资源值只用于设置缺失时兜底，以避免早期 system context 的资源快照影响判断。

另修正 system_server 直接发送非保护广播造成的重复日志，使用指定用户的 sendBroadcastAsUser，保留 shareIdentityEnabled，发送与接收两侧均使用 STATUS_BAR_SERVICE 权限并继续校验发送 UID。页面新增最近运行状态，仅状态变化时写入独立状态偏好，不触发设置通知循环。test4 为 versionCode 103，运行效果待用户覆盖升级并手动重启后验证。

## test5 补齐 Pixel Taskbar，2026-11-13

用户反馈 test4 淘宝主页仍是黑条。实机读取确认模板正常命中，runtime 为取色中，发送帧为 active=true / mode=1 / color=-1（白色）。截图仍显示黑色导航背景。窗口层级显示当前导航窗口名称为 Taskbar，所有者是 Pixel 桌面 com.google.android.apps.nexuslauncher，rootLayout 和 TaskbarDragLayer 高度均为 73px，小白条类型为 StashedHandleView。

从本机 NexusLauncherRelease.apk 提取并核对了 TaskbarBackgroundRenderer.draw(Canvas)、TaskbarDragLayer.mBackgroundRenderer、paint、strokePaint 及 StashedHandleView.updateHandleColor(boolean,boolean)。test5 在原有 Pixel 桌面作用域接入背景与小白条配色，发送到 SystemUI 与 Pixel 桌面两个宿主。仅 TaskbarDragLayer 所属渲染器参与，保留 Scrim 独立绘制，绘制后恢复原画笔状态，并按系统上报的真实导航高度补齐背景，避免原生背景 alpha 为 0 时仍露出应用黑底。普通应用依然免勾选。

test5 为 versionCode 104，首次升级后仍需用户手动重启加载系统框架和桌面宿主。新绘制路径的实机效果待加载后验证。

## test6 截图连续性与独立设置页，2026-11-13

用户确认 test5 正常生效，截图与刷新时仍会露出黑条。实机读取记录显示 ScreenshotUI 于 17:39:43.313 获得焦点，导航栏在 17:39:43.546 发出 active=false，Pixel 桌面于 17:39:44.050 收到；17:39:49.567 才重新发出悬浮帧。回到淘宝首页时另捕捉到颜色采样暂不可用。当前证据确认截图焦点导致撤销适配，刷新具体触发条件仍需升级后复测。

test6 为 versionCode 105。ScreenshotUI 或短暂无焦点期间，仅在上次窗口所属 Activity 仍为 mFocusedApp 且窗口可见时继续处理该窗口，悬浮 Insets 使用同一判定。截图浮层显示期间保持同一窗口、模式和导航高度的最近颜色，暂停重新采样。颜色采样返回空或抛出异常时，仅同一窗口、相同模式与导航高度可保留最近成功颜色，最长 1500ms。其他系统面板、键盘、锁屏和窗口模式仍按既有条件恢复。

监听系统焦点、目标窗口 finishDrawing、布局策略完成事件，合并后 64ms 请求更新。活跃轮询改为 160ms，待确认候选颜色为 64ms。帧广播使用前台接收优先级，未变化帧最多每 750ms 发送心跳，宿主超时为 3000ms。小米专有像素采样回调未移植，更新时序及性能仍需真机检查。

按用户要求将导航栏加入首页，主页面采用与图标适配一致的带图标应用列表和原生 Activity 子页。应用默认模式为单选行，Activity 独立规则通过底部单选面板编辑，支持应用与 Activity 搜索。清除独立规则后按既有优先级继承，已有别名和已保存但当前清单缺失的 Activity 仍可编辑。宿主连接与最近运行状态移至开发者工具中的导航栏诊断。

验证结果为 52 项相关单元与页面测试通过，38 个 API37 精确方法检查、既有主机检查与归档检查通过。已查看本地渲染的导航栏主页面和应用子页面，最终 APK 已通过版本、原签名、三个固定作用域与 CRC 校验。APK SHA-256 为 8ea737f0a981557dc2c614ae7c634a059be2b375edb56e81c00d061d8b8554eb。当前手机仍运行 test5，test6 的截图连续性、刷新响应及功耗尚待用户覆盖升级并手动重启后验证。

## 待调整的搜索布局，2026-11-13

用户要求先记录导航栏页面的搜索布局偏好。应用列表与 Activity 列表的内容区均不放置常驻搜索框，可取消搜索，或将搜索入口放到 TopBar。具体方案在后续界面调整时落实，本次仅记录。

## 待替换的主页入口图标，2026-11-13

用户指定模块主页“导航栏适配”入口使用以下手机底部导航条图标。本次仅记录，后续替换当前入口图标。原始尺寸为 24×24，fill 为白色。用户消息中的转义符和 xmlns Markdown 链接在下方还原为有效 SVG。

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" height="24px" width="24px" fill="#FFFFFF"><path d="M7,23q-0.82,0-1.41-0.59T5,21V3Q5,2.17 5.59,1.59T7,1H17q0.82,0 1.41,0.59T19,3V6.1q0.45,0.18 0.73,0.55T20,7.5v2q0,0.47-0.27,0.85T19,10.9V21q0,0.82-0.59,1.41T17,23H7Zm0-2H17V3H7V21Zm0,0V3V21Zm3-1h4q0.43,0 0.71-0.29Q15,19.43 15,19t-0.29-0.71T14,18H10q-0.42,0-0.71,0.29T9,19t0.29,0.71Q9.58,20 10,20Z"/></svg>
```

## 本轮需求清单，2026-11-13

用户已发出开始指令，本轮处理以下 7 项，实施与验证结果见后文。

1. 导航栏应用列表和 Activity 列表取消内容区常驻搜索框，可取消搜索或将搜索入口放到 TopBar。
2. 模块主页“导航栏适配”入口替换为用户指定的 SVG 图标，路径数据见上节。
3. 排查并移除横屏导航条背后的暗色阴影。当前横屏会退出导航栏适配，已采集 B 站横屏窗口与视图数据，具体绘制来源继续核对。
4. 校准图标适配后的状态栏通知图标尺寸。当前通知素材将最长可见边归一到画布的 82%，还需对照反馈设备的原生效果确认缩放与留白影响。
5. 恢复“长侧滑打开小窗”独立开关及配置同步、触发判断。早上改动位于 build/wt-review-0920，当前 main 打包的 test6 未合入。手机保留 longBackWindow=false，当前代码未读取该键。整合时保留已有取值与原迁移规则。
6. 优化小窗遮罩期间的重复计算。预先准备起点和目标布局，在完全不透明遮罩覆盖期间减少真实窗口的位置、缩放、裁剪、圆角等重复更新，继续驱动可见遮罩动画，并保留首帧就绪、淡出交接、中途取消、旋转与键盘变化处理。现有实现已避免逐帧 setBounds 和重复绘制遮罩图标，完整优化尚未实现。
7. 排查其他用户设备上小窗转换时遮罩偶发消失。反馈截图显示桌面上出现大 QQ 图标，具体前置操作未知。项目用户已在本机尝试复测，暂未复现。需要核对模块遮罩背景和图标的图层异常、原生桌面图标动画，以及转换中断导致的遮罩退出，补齐反馈设备的复现或日志证据后再修复。

### 第 7 项的初步证据

TaskCover 正常绘制时在同一 SurfaceControl.Transaction 中设置背景与图标的透明度并显示，正常 close 时也在同一事务内移除两者。因此截图中的大图标还需要确认归属，单凭截图尚不足以认定仅背景图层被移除。

项目用户本机 test6 的 LSPosed 日志记录 QQ task=158 在 18:38:27.416 开始全屏遮罩转换，18:38:27.520 收到 source=application-back 关闭请求，18:38:27.521 进入关闭流程，随后 18:38:27.773 退出动画结束。WindowEngine.backToClose 当前仅排除 restoring 和 closing，未排除 fullTransition 或 transitioning，回调排队执行时也未重新核对转换状态。这段日志反映本机一次转换与返回关闭的时序，可用于后续核对转换取消处理。反馈截图来自其他用户设备，项目用户本机复测未复现遮罩消失，该时序与反馈故障的关联尚待验证。故障原因仍待反馈设备的复现或日志确认。本机日志时间与截图中的 08:50 不对应，本机当前日志未报告 COVER_SKIPPED 或 COVER_RELEASED 异常。

## 0.9.21-test1 实施结果，2026-11-13

版本代码为 106。应用与 Activity 列表采用取消搜索的方案，主页导航栏入口使用指定 SVG 的 Android VectorDrawable，并沿用页面动态主题着色。

导航栏增加默认开启的横屏透明开关，由现有系统后端向 SystemUI 和 Pixel 桌面发送透明帧，支持应用请求隐藏导航条后的临时唤出。横屏保留应用原生 Insets，键盘、锁屏、多窗口等既有暂停条件继续生效。独立开关可恢复横屏原生背景，普通应用仍通过三个既有系统作用域适配。

通知图标按原生 getIcon 返回图标的实际像素尺寸校准密度与画布，通知素材可见轮廓的最长边从画布 82% 调整为 94%。状态栏后续着色与布局继续由系统执行，反馈设备上的视觉一致性待复测。

已从早上的工作目录恢复 longBackWindow 的设置页、迁移、Provider 同步、提示、触发准入与排队后的再次检查。已保存的关闭值直接读取，已有安装缺键时保留开启，新安装默认关闭；在手势已准备但尚未派发期间关闭开关，会撤销准备状态。

遮罩完全不透明且仍持有真实任务时，复用已准备的逻辑画布和密度，跳过真实窗口重复的变换、裁剪、圆角、透明度及已就绪装饰的结构查询。遮罩自身和可见装饰继续插值动画。淡出前通过回调强制刷新真实窗口的最终呈现，再解除隐藏；尺寸变化、透明阶段和原生动画接管沿用常规处理。真实窗口的配置读取合并为一次。

排查确认 beginCover 原先会复用已经进入淡出或释放阶段的 TaskCover，新转换可能继承旧的淡出时钟。现已仅复用同模式且仍持有遮挡的遮罩，其余情况创建新遮罩并转交已有原生动画图层。增加 COVER_REPLACED、COVER_END 和异常时的遮罩状态日志。这项代码问题已有回归覆盖，其他用户截图对应的故障根因仍待反馈设备复现或日志确认。

195 项现有单元与页面测试全部通过，已查看新导航栏主页面和应用页预览。原始运行基线、主机检查及归档接口检查通过。新版本的横屏视觉、反馈设备的通知图标大小和实际性能收益待真机验证。

最终交付 deliverables/NativeComplements-0.9.21-test1.apk，SHA-256 为 67aca76467678e88f8010dd372a724999e6fc8f03889bc0bc075d8559994a57c。已核对 versionCode 106、原签名和三个固定系统作用域。用户覆盖安装后手动重启一次加载系统 Hook，日常设置调整继续通过配置同步生效。

## 0.9.21-test2 完整补回上午评审分支

用户要求优先恢复上午分支的全部功能与优化。已在 main 工作目录带回 fix/review-0920-touch-guard 的已提交及未提交实现，包括 R8、后台触摸保护、全屏跳转判断、反射与 Hook 热路径优化、熄屏心跳暂停、广播去重、迷你窗前台排除、宿主启动失败恢复、图标文件清理、弹幕历史保存结果和配置读取失败的常亮释放。本轮导航栏与兼容的遮罩更新同时接入。

216 项测试通过，R8 发布构建及最终 APK 反射名称、恢复方法、签名和作用域检查通过。版本代码 107，交付 deliverables/NativeComplements-0.9.21-test2.apk，SHA-256 为 47a354b650af8b17c2eb8ff14becc8a9db8d2bf4141f3c1a0c663d3009b0f72e。详细逐文件核对记录见 branch-integration-audit-2026-11-13.md。用户安装后手动重启加载新版宿主，真机效果待复测。

## 待办，小窗运动流畅度，2026-11-13

已在 test4 实施帧调度修复与补充统计，改动和验证记录见文末。

用户反馈低帧感主要发生在小窗运动中途。源码确认真实任务使用 Choreographer，遮罩 pollCover 使用固定 16ms 延迟循环，存在帧驱动节奏差异。现有真实任务呈现复用仅覆盖完全不透明且逻辑画布尺寸稳定的遮罩阶段，实际命中比例待补充采样。

- 将可见遮罩接入现有 Choreographer，让遮罩、真实窗口和装饰共享帧时间，梳理重复更新及事务提交。
- 补齐遮罩提交间隔、完整回调耗时及呈现复用命中统计。现有 maxWorkMs 仅覆盖插值与 renderFrame，独立遮罩循环和后续收尾也需要纳入评估。
- 验证连续切换、取消、旋转、锁屏、首帧就绪和遮罩淡出交接，检查闪黑与位置跳变。
- 使用相同设备和操作流程复测运动帧间隔与长尾耗时，核对固定 16ms 调度与用户低帧感的关联。

最近性能采样与源码排查保存在 verification/performance-20261113/results.md。

## 待办，莫奈图标返回桌面时闪切，2026-11-13

用户反馈开启莫奈图标后，返回桌面的归位动画先显示默认图标，结束时瞬间切换为莫奈图标。已在 test4 实施主题图层保留，实际视觉待真机复测。

已核对模块 IconLayerModule.installAnimationProbe 与归档目标 Pixel 桌面的实际实现。桌面 FloatingIconView.IconLoadResult 保存 isThemed，加载器依据桌面图标的 themed 状态调用 IconLoader.getFullDrawable，后者经 themeController.createThemedAdaptiveIcon 生成主题化动画图标。模块当前在 FloatingIconView.setIcon 前仍调用通用 IconAssets.desktop 替换，对于自定义彩色图标存在把已主题化图标重新覆盖成彩色版本的路径，原生 setIcon 随后直接使用替换图标的前景和背景。这条样式覆盖路径与反馈现象吻合，实际视觉问题待修复后验证。

- 按每个图标的原生主题状态保留已生成的莫奈动画图层，避免后置彩色替换覆盖；继续使用桌面原生动态颜色与主题控制器。
- 保留普通彩色图标的既有分层适配，以及用户、快捷方式、徽标等原生判断。
- 验证开启与关闭主题图标、打开与返回、预测性返回取消、缓存刷新和动态颜色切换。

静态证据位于 verification/icon-monet-return，包含目标桌面的 FloatingIconView 加载回调与 IconLoader 实现。

## 待办，Pixel Android 17 QPR2 兼容性，2026-11-13

当时共三项实施任务，分别为小窗运动流畅度、莫奈图标归位闪切与 Pixel QPR2 适配。用户随后撤回了时间字号的疑问，时间尺寸检查取消；之后追加弹幕并行展示，汇总为四项。

已分析 Pixel 10 Pro / CP4A.261105.040.A1 / SDK 37.2 的 system-dump-20261113-1722.zip，以及 LSPosed_20261113_164749.zip、native-complements-diagnostics.zip。原始材料、按宿主解析的 DEX 定义、反编译结果、离线核对脚本和分析结论保存于 verification/pixel-qpr2-20261113。

日志版本为 0.9.19-signal-test1 / 96。已将 NoSuchMethodException 与材料中的实际定义对应。

- handleStartResult 从 7 参数变为 8 参数，新增 CallerInfo。当前预检在此失败并卸载已登记的小窗 Hook，是小窗核心停用的直接路径。
- startActivityUnchecked 新增 TaskDisplayArea 与 CallerInfo，14 参数变为 16 参数。关联跳转需要明确的新旧签名及参数索引映射。
- Task.isResizeable(boolean) 改为无参，此处是可选的受控可调整性适配失败。
- 手势 Lambda20 已成为 Runnable，实际输入字段指向 Lambda24 的 invoke。通知点击对应的 runner/sender 也由旧 Lambda7/10 变为 Lambda6/12。适配时以目标字段类型及调用结构验证入口。
- 双栏旧 getter、面板资源更新与 Compose emitter 路径发生变化。目标 SceneContainerFlag 的 legacy 断言直接抛出异常，需要适配实际新面板路径。
- SystemUI Kotlin.Pair.component2 被编译移除，保留 second 字段，信号显示预检需要兼容该差异。

图标、通知图标、状态栏偏移的入口在原日志中已有加载成功记录。另有配置读取失败及 Motion Unknown authority，需要在安装状态下核对 Provider 注册和完整异常栈。用户确认材料导出前已经卸载模块，卸载后的包查询不作为此前接口故障或 Provider 故障的根因。

离线检查确认 Launcher 自身的 IconProvider 三参数方法及导航栏 38 项预检方法仍存在。SystemUI 与 Launcher 内置的同名 IconProvider 不同，已按宿主区分。现有文件使用 DEX 041 容器，核对时已遍历全部逻辑 DEX。上述结论属于离线分析，实施后还需反馈设备复测。电话服务内部接口还需相应 Telecom 材料或完整运行证据补充。

## 0.9.21-test3 弹幕并行展示，2026-11-13

用户选择允许两三条同时滚动，本版采用三条独立轨道。每条保留收到时的内容快照，同应用复用通知 key 的后续消息和其他应用的新消息均可并行显示。三条占满后按到达顺序排队，队列最多 32 条，队列满时保留系统原通知。

优先读取 MessagingStyle 最新消息及 InboxStyle 最后一条文本，再回退到普通通知正文。普通正文开头的“（两条消息）”等汇总计数会清理，结构化消息原文及正文中间的同类文字保留。重复投递不重启动画；历史保存失败时保留原通知，并允许相同通知再次到达时重试保存。

通知移除回调携带原始 key 与 postTime，模块主动取消的旧通知回调与后续同 key 消息分开处理。历史写入完成后再次核对当前通知版本，旧消息保存完成时保留尚未显示的新版本通知。截图期间继续沿用当前弹幕；其他面板暂停与恢复全部轨道，前台应用切换或熄屏清空展示与队列。

223 项单元与页面测试全部通过，新增覆盖同 key 更新、跨应用并行、排队、旧回调、异步历史保存、暂停恢复及消息提取。原始运行基线、归档目标签名与导航栏接口检查通过。版本代码为 108，版本名 0.9.21-test3，保留 R8 与原签名。实际 QQ 连续消息效果待用户覆盖安装并手动重启后验证。

前三项任务继续保留待实施状态，本次安装包包含弹幕展示修复。

交付文件为 deliverables/NativeComplements-0.9.21-test3.apk，SHA-256 为 6b70cda388777acd3e7f8debfd5193dc9e3394131427ded04aa09f6985e12585。发布构建及最终 DEX 的 R8 反射名称、恢复方法、签名与三个固定系统作用域检查通过。

## 0.9.21-test4 实施记录，2026-11-13

版本代码 109。用户要求合并弹幕三档、小窗运动流畅度、莫奈归位闪切、Pixel QPR2 兼容与 HyperOS 边缘抑制研究。

### 弹幕条数

设置页提供三条、五条、无限，旧安装默认三条，配置立即同步。增加档位会开始展示排队消息，调低档位保留正在滚动的消息。无限档放开并发数量，可见轨道用满后循环复用，密集消息可能重叠。有限档沿用最多 32 条队列及原通知保留策略。

### 小窗运动

可见遮罩改用现有 SF Choreographer，传入按同一垂直同步时间换算的单调时钟，移除独立的固定 16ms 递归。遮罩背景、图标与装饰在同次 paint 事务中更新，窗口原有帧驱动继续使用同一 Choreographer。原生窗口绘制和配置匹配合并为每帧一次。完全不透明阶段继续复用真实呈现，揭开前仍强制刷新。增加遮罩帧间隔、工作耗时、呈现复用计数，以及包含最终原生收尾的完整动画慢回调记录。实际设备帧率与性能收益待相同场景复测。

### 莫奈归位动画

FloatingIconView 使用原生 IconLoadResult.isThemed 判定，主题动画直接保留桌面已经生成的图层。彩色图标的分层适配继续走既有替换路径。当前与 QPR2 桌面的主题字段和 setIcon 签名已通过实际 DEX 核对，实际返回动画待真机复测。

### Pixel QPR2

接入 handleStartResult 的 CallerInfo 参数、无参 Task.isResizeable，以及 startActivityUnchecked 的 16 参数调用与索引映射，新增参数原样转交。手势回调从 DisplayBackGestureHandlerImpl.onInputEvent 的具体字段类型识别，旧系统的 Function1 字段保留经过验证的 Lambda20 入口。通知点击验证两套 runner/sender 字段布局。信号读取 Pair.second，兼容已移除 component2 的构建。横屏双栏增加 Hydrated 仓库的原生资源读取作用域及配置回调，沿用仓库自身的 MutableState 更新和原生 ShadeMode 计算。

新增 tests/verify_test4_target.py，遍历 DEX 041 的全部逻辑头，按 SystemUI 与 Launcher 宿主分别解析，核对当前系统和反馈 QPR2 的接口、字段类型与启动参数索引。目标摘要和选定成员保存在 tests/target/test4-members.json。旧配置 Provider 解析失败的具体根因仍待反馈设备确认，图标与状态栏偏移加入读取重试和完整异常日志；现有 Motion 重试保持运行。

### 边缘防误触

依据 verification/hyperos-edge-suppression/README.md 的输入分发层方案实施。SystemUI 按屏幕旋转生成左右区域、横屏上下边与握持侧角落，默认角落范围为 24dp，用户可调到 0。保留系统安全区、键盘/面板暂停、熄屏处理和小窗控件排除。区域通过 SystemUI UID 校验的广播和三秒租约发送给 system_server。

输入层沿用 InputManagerService.filterInputEvent 边界。每根手指仅在 DOWN 决定是否接纳，移动出入区域保持原决定；多指混合事件按 pointer id 拆分。原有无障碍过滤器继续接收被接纳的子集；没有原生过滤器时使用原生 FILTERED 标志异步投递拆分后的事件，并消费本次原事件。关闭或租约失效后完成已抑制手指的收尾，宿主退出或输入后端失败可释放区域并回退窗口方案。驱动私有 mode 15 与握持传感器规则仍属于原研究的硬件边界。

过滤边界返回值和变换后事件的投递语义对照 AOSP InputDispatcher 的 notifyMotion 与 setInputFilterEnabled，并核对两套系统的 Java 目标定义。[AOSP InputDispatcher](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/inputflinger/dispatcher/InputDispatcher.cpp)

### test4 验证结果

233 项单元与页面测试全部通过，包含三档持久化与即时切换、无限档并发、通知版本隔离、多指接纳与拆分、旋转角落几何、无障碍过滤器保留、租约超时和后端故障恢复，以及遮罩 8ms 步进和熄屏六秒后的恢复。已检查弹幕设置页生成的界面预览。原始运行基线、原有归档目标、导航栏目标和 test4 两套宿主目标检查通过。反馈设备的 QPR2 Provider 问题、莫奈动画视觉、小窗实际帧率与新输入路径的设备效果仍待覆盖安装后复测。

交付 deliverables/NativeComplements-0.9.21-test4.apk，versionCode 109。发布构建、R8 最终 DEX 的反射名称及旧优化/新输入后端成员、原签名、三个固定系统作用域与 APK 完整性检查通过。SHA-256 为 d219cf2b04329dffe58afe5da98029096a8569075bf0a20abbe396ff379951b0。覆盖安装后需用户手动重启加载新版系统 Hook。

## test4 后续反馈，待统一排查，2026-11-13

- 默认小窗切换为迷你小窗时仍有卡顿、掉帧感。用户要求先记录，待汇总后一起查明原因。具体应用、触发操作与逐帧数据待后续核对。
- 横屏透明导航条功能不生效。用户要求先记录，待汇总后排查。具体应用、页面、导航条唤出状态和配置快照待后续核对。

上述两项当前均为用户反馈，根因尚未定位，等待用户安排统一排查。
