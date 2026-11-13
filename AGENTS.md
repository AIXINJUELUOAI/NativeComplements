# NativeComplements

- 默认简体中文。原生 Android 设置风格，Compose MD3/M3E、系统动态颜色与深色模式。
- 首页按功能独立跳转；功能做到可用后再加入入口。当前功能为小窗、弹幕通知、来电拦截、边缘防误触、快捷建议、加速度传感器控制、图标适配、导航栏适配与通知／控制中心横屏双栏，不创建其他功能的占位页面或接口。
- 管理页面共用 SettingsPage 标题栏和系统安全区，页面内部不重复创建标题与 Scaffold。开发者工具仅从“关于”连续点击版本号七次后显示，日常页面不展示后端连接及实验信息。
- 单 app Gradle 模块。管理界面位于 home、navigation、core/ui 和 features/floatingwindow/ui。
- 第一版行为基线是 Demo8.2。基线原有26个Java文件，当前变更清单记录于 tests/runtime-changes.json，代码位于 features/floatingwindow/legacy，Java 源码包路径保持 dev.local.sunwindowdemo。修改小窗时记录原因，不改基线摘要来隐藏差异。
- applicationId 自 0.6.0 起为 dev.nativecomplements.module（模块名“类原生补全计划”）；它与三个系统作用域、签名权限和 corner 偏好键是覆盖升级兼容点。使用 SystemUI 宿主与协议16，不依赖无障碍，导航栏适配由 system、SystemUI 与 Pixel 桌面宿主执行，普通应用无需勾选作用域，Activity 模板由 UID 校验 Provider 同步。
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
- 导航栏位于 features/navbar，首页独立入口沿用逐应用列表和原生 Activity 子页，内容区取消常驻搜索框，主页使用用户指定的手机导航条矢量图标。竖屏规则优先级为用户 Activity、用户应用、内置模板、通用默认，横屏提供独立的默认开启透明处理，仅清理宿主背景，包含临时唤出的手势导航条。诊断信息仅在开发者工具显示。ScreenshotUI 期间仅延续同一可见 Activity 的窗口和 Insets 处理，短暂采样失败最多保留最近颜色 1500ms。
- 长侧滑打开小窗由 corner.longBackWindow 独立控制，沿用已保存值，旧安装缺键时保留原先开启行为，新安装默认关闭。Provider 首次导出前完成迁移，宿主快照只读。完全不透明的 TaskCover 遮挡期间可复用真实窗口呈现状态，揭开前强制刷新；进入淡出、退出或释放阶段的旧遮罩应替换，交接时延续已持有的原生动画图层。
- TaskCover 由现有 SF Choreographer 驱动，与窗口共享帧节奏，日志覆盖遮罩帧间隔、呈现复用次数及完整动画回调的长耗时。
- 弹幕并发档位为三条、五条、无限，旧配置默认三条，调低档位保留当前消息；无限档用满可见轨道后复用轨道。有限档满额后排队，成功显示并保存历史后取消对应版本的原通知。
- 边缘防误触使用 system_server 输入过滤边界，按每根手指的 DOWN 决定整条流，保留已有无障碍输入过滤器。区域由 SystemUI UID 校验广播同步，3 秒租约失效释放；宿主连接失败回退透明窗口。角落按四个旋转选择握持侧两个角，默认 24dp 可调，系统安全区与小窗控件继续保留。
