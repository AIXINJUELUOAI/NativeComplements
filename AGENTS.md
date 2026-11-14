# NativeComplements

- 默认简体中文。原生 Android 设置风格，Compose MD3/M3E、系统动态颜色与深色模式。
- 首页按功能独立跳转；功能做到可用后再加入入口。当前功能为小窗、弹幕通知、来电拦截、边缘防误触、快捷建议、加速度传感器控制、图标适配、导航栏适配与通知／控制中心横屏双栏，不创建其他功能的占位页面或接口。
- 管理页面共用 SettingsPage 标题栏和系统安全区，页面内部不重复创建标题与 Scaffold。开发者工具仅从“关于”连续点击版本号七次后显示，日常页面不展示后端连接及实验信息。
- 单 app Gradle 模块。管理界面位于 home、navigation、core/ui 和 features/floatingwindow/ui。
- 第一版行为基线是 Demo8.2。基线原有26个Java文件，当前变更清单记录于 tests/runtime-changes.json，代码位于 features/floatingwindow/legacy，Java 源码包路径保持 dev.local.sunwindowdemo。修改小窗时记录原因，不改基线摘要来隐藏差异。
- applicationId 自 0.6.0 起为 dev.nativecomplements.module（模块名“类原生补全计划”）；它与三个系统作用域、签名权限和 corner 偏好键是覆盖升级兼容点。使用 SystemUI 宿主与协议16，不依赖无障碍，导航栏取色由 system、SystemUI 与 Pixel 桌面宿主执行，透明绘制由用户授权作用域中的应用侧 Hook 配合。推荐作用域从已核实 ColorOS 配置外层包名生成，安装后须在 LSPosed 确认作用域，推荐不等于授权；本模块不自动修改作用域。Activity 模板由 UID 校验 Provider 同步，普通应用仅能读取自己包的规则。
- .local/demo.keystore 是现有覆盖升级签名，仅本地保存。不要提交签名、设备数据或本机配置。
- JDK17+、SDK android-37.0、Gradle8.14.4。本机可用 Android Studio JBR，跨目录构建通过环境变量配置路径。
- 构建：gradlew.bat --no-daemon --console=plain :app:assembleRelease。按变更运行必要的针对性检查，不把构建成功表述为真机效果保证。
- 基线及主机检查：python tests/verify_runtime.py；归档目标签名检查：python tests/verify_target.py。
- 最终打包：python tests/package_release.py，仅交付 APK，不创建源码ZIP、逐轮备份或额外报告。生成物放 deliverables/、verification/ 或 build/，不提交 Git。
- 不自动安装、重启或更改设备的 LSPosed 配置。
- 一律在 main 上开发，不新建长期分支；同一时间只允许一个会话修改本仓库。交付版本打 tag。
- 开工前工作区必须干净：有未提交改动先 `git add -A && git commit -m "wip: 开工前快照 <主题>"`；收工或交付前再提交一次。
- 遗留的并行 worktree 和分支只读保留、不再新增；其中有效改动必须合回 main 才算交付。公共 Hook 入口、首页导航、共享配置和版本号仍由集成任务统一修改。
- 保留用户未提交工作，不执行 reset 或强制推送。测试签名和原始基线不清理。
- 小窗已移除180秒期限，配置由UID检查的Provider同步到SystemUI，另提供仅可写快捷项列表的管理接口；启动健康计数和全套动态Hook卸载尚未实现。

- 电话拦截位于 features/callblocking；使用独立 CallBlockingModule 和 UID 检查的 CallBlockingProvider，设备保护存储。只使用既有 system 作用域，按实际来电 PhoneAccount 识别卡槽，不轮询铃响猜 SIM，不接管默认电话角色。

- 快捷控制位于小窗悬浮菜单的“快捷功能”，不再提供独立游戏助手与侧边提示条。弹幕通知独立位于 features/barrage，使用 UID 检查的配置读取／受限历史写入 Provider 和设备保护存储，不依赖小窗总开关；成功显示并记录历史后取消对应原通知，不做投递前拦截。截图不隐藏或重建当前弹幕，键盘、其他系统面板和锁屏规则保留。
- 横屏双栏位于 features/shade，独立设备保护配置与 UID 校验只读 Provider，默认关闭，适配已核实的 legacy Split Shade 及 QPR2 Hydrated 配置仓库；不强开 Scene、不改 DPI、不依赖小窗开关；Compose QS 通过其原生配置资源流同步分栏模式，不直接改 readonly StateFlow、MutableState 或硬编码磁贴列数。
- 状态栏图标垂直位置与“通知与控制中心”共用同一设定快照，范围 -32~+32 dp，仅平移可见内容，不改高度、刘海或 DPI。系统悬浮旋转按钮在“快捷建议 → 屏幕旋转”开启时由本模块替换，关闭即恢复原生；仅点击时写入目标方向，不改自动旋转总开关。
- 导航取色保留现有应用图层窄条算法；小窗获焦时以原生 DisplayPolicy 底部背景候选选择全屏归属，补色为 WindowState 容器下的无输入子层，宿主仅透明背景并同步手势条明暗，接口失败回退宿主补色。失焦不立即销层以保留任务截图机会；窗口删除、父 Surface／几何／规则／安全状态变化和功能暂停时清理。桌面／最近任务在仍有适配任务时仅清理宿主导航背景，不铺前应用颜色；真机快照与转场结果需独立验证。
- 导航栏位于 features/navbar，首页独立入口沿用逐应用列表和原生 Activity 子页，列表支持应用名、包名、Activity 与完整组件名搜索并直达页面模式项，应用详情支持 Activity 过滤；不增加逐应用启用开关，主页使用用户指定的手机导航条矢量图标。透明策略保留原始 policy_flag/base_only：4 仅透明，6 另处理导航 Insets，不将资格黑名单改成悬浮；应用侧暂限正常全屏主 Activity 窗口，键盘、分屏、安全窗口与其他显示器旁路原生。竖屏规则优先级为用户 Activity、用户应用、内置模板、通用默认，内置模板随总开关参与且不再提供独立开关，京东内置策略为系统原有；横屏提供独立的默认开启透明处理，仅清理宿主背景，包含临时唤出的手势导航条。诊断信息仅在开发者工具显示。ScreenshotUI 期间仅延续同一可见 Activity 的窗口和 Insets 处理，短暂采样失败最多保留最近颜色 1500ms。
- 长侧滑打开小窗由 corner.longBackWindow 独立控制，沿用已保存值，旧安装缺键时保留原先开启行为，新安装默认关闭。Provider 首次导出前完成迁移，宿主快照只读。完全不透明的 TaskCover 遮挡期间可复用真实窗口呈现状态，揭开前强制刷新；进入淡出、退出或释放阶段的旧遮罩应替换，交接时延续已持有的原生动画图层。
- TaskCover 由现有 SF Choreographer 驱动，与窗口共享帧节奏，日志覆盖遮罩帧间隔、呈现复用次数及完整动画回调的长耗时。
- 弹幕并发档位通过滑块选择三条、五条、无限，旧配置默认三条，调低档位保留当前消息；无限档用满可见轨道后复用轨道。有限档满额后排队，成功显示并保存历史后取消对应版本的原通知。
- 边缘防误触使用 system_server 输入过滤边界，按每根手指的 DOWN 决定整条流，保留已有无障碍输入过滤器。区域由 SystemUI UID 校验广播同步，3 秒租约失效释放；宿主连接失败回退透明窗口。角落按四个旋转选择握持侧两个角，默认 24dp 可调，系统安全区与小窗控件继续保留。

- 小窗触控区域合并到单个受信任窗口，通过 InternalInsetsInfo 的可触区域并集保留内容穿透、过渡阻断、入场手势、键盘安全区与系统优先级；隐藏接口预检失败时回退原六窗口。横屏导航背景同时处理 Launcher 的 TaskbarBackgroundRenderer 和 TaskbarTransitions 专属 BarBackgroundDrawable，保持原生动画状态更新与到期恢复。
- 图标批量设置仅从图标适配页顶部菜单进入，先选择应用，再独立选择桌面、莫奈、通知模式，每路默认保持现有。素材与生成缓存各应用独立保留，一次提交整个批次并刷新。

- TaskCover 的全屏端点使用所属显示器的 ScreenDecorationsUtils.getWindowCornerRadius，按系统统一窗口半径规则处理分辨率缩放并与小窗圆角插值；新建、恢复衔接及目标边界变化时更新，逐帧复用。
- 经模块接管的应用按包名与用户维护五分钟清理宽限。实际可见页面与存续的侧边隐藏小窗保留保护，停放的关联任务按不可见处理。仅重放已延期的内部强停请求，到期复核可见性、请求身份及 UID。设置、ADB 等外部 Binder 强停和模块明确的关闭结束操作即时执行；第三方工具转为内部异步执行的手动强停也适用宽限。
- 连续跳转共用 LaunchCoverFlow 和可转交的 TaskCover 表面，绘制与窗口状态稳定一秒后揭开，五秒总上限，熄屏暂停计时。跨任务转交失效时沿用新建遮罩回退，超时沿用现有窗口恢复。
