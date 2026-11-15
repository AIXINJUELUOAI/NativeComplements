# NativeComplements

- 默认简体中文。原生 Android 设置风格，Compose MD3/M3E、系统动态颜色与深色模式。
- 首页按功能独立跳转；功能做到可用后再加入入口。当前功能为小窗、弹幕通知、来电拦截、边缘防误触、快捷建议、加速度传感器控制、图标适配与外观和控制（含小白条沉浸、通知／控制中心横屏双栏），不创建其他功能的占位页面或接口。
- 管理页面共用 SettingsPage 标题栏和系统安全区，页面内部不重复创建标题与 Scaffold。开发者工具仅从“关于”连续点击版本号七次后显示，日常页面不展示后端连接及实验信息。
- 正式功能集中在 app Gradle 模块。管理界面位于 home、navigation、core/ui 和 features/floatingwindow/ui。用户另授权 navbar-demo 独立观察 APK（dev.nativecomplements.navdemo，独立 system 作用域），仅采样/人工标注，不改导航、页面或 Insets；实验前关闭“小白条沉浸”并由用户重启。Demo 0.1.6（layout-v6）每次捕获一张导航上方最多320dp应用图连同窗口内导航区域行；应用层导航区域非纯色即判悬浮（应用已自行绘制），3–6个窄、等距、对称且上方2dp平静、不与上方列重复的底部导航项判延伸（底栏内淡纹理>8%视为半透明模糊判悬浮），否则底部≥32dp空白或有边界/等距排列且不与上方重复的底栏判延伸，内容被边界截断、与上方重复或无法确认判悬浮；12–24dp主色占比仅记录。离线原型与样本集在 verification/navbar-demo-live/layout-v5（proto.py 与 DemoLayout.java 保持同步）。每次肯定/否定/待定都保存该分析区域（PNG优先，≤800KiB，最多400张）及样本JSON，忙碌或过期时仍记录标注但不截图；日志与截图仅本地、可ZIP导出/清空，不上传；构建签名检查用 python tests/package_navbar_demo.py，额外归档接口核验用 python tests/verify_navbar_demo_target.py。
- 第一版行为基线是 Demo8.2。基线原有26个Java文件，当前变更清单记录于 tests/runtime-changes.json，代码位于 features/floatingwindow/legacy，Java 源码包路径保持 dev.local.sunwindowdemo。修改小窗时记录原因，不改基线摘要来隐藏差异。
- applicationId 自 0.6.0 起为 dev.nativecomplements.module（模块名“类原生补全计划”）；它与三个系统作用域、签名权限和 corner 偏好键是覆盖升级兼容点。使用 SystemUI 宿主与协议16，不依赖无障碍。静态作用域仅保留 system、SystemUI 与 Pixel 桌面三个系统作用域（staticScope=true）；小白条沉浸使用 SystemUI 原生导航栏与已核实 Pixel 桌面 Taskbar 两条系统宿主路径，不依赖普通应用授权，无普通应用侧或system_server导航Hook。模块不直接改设备LSPosed数据库，静态范围的选择限制/旧授权清理由框架实现，不跨分支保证。
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
- 抽屉莫奈图标开关位于“外观与控制”（原“通知与控制中心”），默认关闭且独立于图标适配总开关。仅抽屉网格限定原生渲染链，复用原生monochrome/主题色/角标；缺素材回原生，不全局启用主题，不写共享BitmapInfo，不改桌面、搜索与通知图标。Launcher UID仅能读取该布尔投影；宿主私有素材有界缓存，配置/素材发布/包及用户可用性变化后失效，关闭重绑恢复。抽屉 AppInfo 查询补齐主题位（CacheLookupFlag 8），缺主题位的已绑定图标在后台按原 flag+8 重载一次。
- “莫奈图标”分组含抽屉、遮罩（应用／小窗启动与变换遮罩，TaskCover）、小窗（悬浮菜单与扇区，AppCatalog.windowIcon）三个独立开关；三者均受系统“壁纸与样式 → 主题图标”门控，Launcher 由 ThemeManager.verifyIconState 上报 system_themed，未开启时开关置灰且实际关闭。遮罩与小窗配色同 AOSP MonoIconThemeController，素材优先模块 mono，其次原生 monochrome；两者缺失时在后台转换真实前景或完整图标，共用128px/32MiB有界缓存，照片保留明暗，缺可见像素才回原图。抽屉原政策不变，关闭各自开关恢复原图。
- 状态栏图标垂直位置与“外观与控制”共用同一设定快照，范围 -32~+32 dp，仅平移可见内容，不改高度、刘海或 DPI。系统悬浮旋转按钮在“快捷建议 → 屏幕旋转”开启时由本模块替换，关闭即恢复原生；仅点击时写入目标方向，不改自动旋转总开关。
- 0.9.45-immersive-test1 将正式导航栏适配收敛为“外观与控制 → 小白条沉浸”单开关，默认关闭，使用独立 navbar_immersive 配置，不继承旧总开关。删除独立板块、逐应用/Activity/页面规则、取色、模板、容器检测、学习、标注浮窗及应用/server/Launcher导航Hook；历史磁盘偏好不主动删除但不再读取。navbar-demo 独立观察APK不改动。
- 小白条沉浸只在默认显示器手势导航生效：SystemUI 或已核实 Pixel 桌面 Taskbar 创建原生导航参数时仅修改 navigationBars Insets provider，保留原生横条、手势触摸区域、其他Insets源和窗口几何；仅对该导航栏所属的背景Drawable临时空裁剪，仍运行原生绘制/动画状态，关闭invalidate恢复，不接管横条颜色/明暗。配置由UID校验Provider只向管理器/SystemUI/Pixel桌面实际UID暴露enabled；更改监听异步读配置、主线程调用原生导航重新布局，关闭重新生成原生参数，不用已被改过的尺寸作恢复值。不读取应用内容、不截图、不取色、不修改安全窗口和逐应用Insets。实际键盘、横屏、转场和关闭恢复效果需要真机独立验证，不宣称构建通过即全面兼容。
- 0.9.46-immersive-test2 恢复三个静态系统作用域，仅补沉浸入口/反射/逐个deopt/Hook/配置/重布局步骤的框架日志（不逐帧），保留test1执行路径与deopt失败保护。test1设备pref为true、安装APK与交付hash一致且SystemUI与current归档相同，但启动logcat已滚动、旧代码未写模块日志，尚不能判定失效发生哪一步；test2为定位版本，不宣称沉浸根因已修。
- 0.9.47-fixes-test1 补 live hash 已核实的 Pixel Taskbar 导航源，配置边沿原生重算主参数与四旋转后仅向该已附着窗口显式提交，避免原生 shallow Insets 缓存吞更新；只支持已核实 current Launcher ABI，不宣称 QPR2 Launcher 兼容。原生应用 splash 接入遮罩莫奈，后台缓存活动原生 mono，主题/壁纸/包变化刷新；缺 mono 保留原图。小窗遵从原生已授权可见安全窗口集合，外部确认时临时隐藏自有展示/输入，同 task 权限保留真实窗口与焦点，不改安全窗口层级。默认窗内容与拖柄从迷你透明控制层可触区域扣除，保留已捕获触摸流；旧六窗回退仅保留最大未遮挡矩形，全盖禁触。初次 addWindow 仅映射 owned Binder 响应 Insets，与 relayout/resize 保持局部视口一致；起点真实透明 buffer 顶部126px仍需安装后重建窗口复测，不按状态栏高度猜裁剪或按包名补丁。
- 0.9.48-fixes-test2 按用户实测只补两个遗留项：owned task 自有低层白色 ColorLayer 填透明 buffer 区，继承 task 裁剪/圆角/缩放/显隐，不猜顶部126px、不覆盖实际内容；独立于handle/dim/cover pose，安全暂停与退出回收。注意整个任务透到外部的透明区域都会变白，深色模式仍白。小窗/遮罩缺 mono 时后台强转真实前景或完整图标，照片保明暗，不做语义抠图；缓存128px/32MiB，module/direct native素材不降分辨率。原沉浸/安全UI/重叠触控用户已反馈正常，不修改。OpenAlive仅讨论与源码研究，未实现。
- 长侧滑打开小窗由 corner.longBackWindow 独立控制，沿用已保存值，旧安装缺键时保留原先开启行为，新安装默认关闭。Provider 首次导出前完成迁移，宿主快照只读。完全不透明的 TaskCover 遮挡期间可复用真实窗口呈现状态，揭开前强制刷新；进入淡出、退出或释放阶段的旧遮罩应替换，交接时延续已持有的原生动画图层。
- TaskCover 由现有 SF Choreographer 驱动，与窗口共享帧节奏，日志覆盖遮罩帧间隔、呈现复用次数及完整动画回调的长耗时。
- 弹幕并发档位通过滑块选择三条、五条、无限，旧配置默认三条，调低档位保留当前消息；无限档用满可见轨道后复用轨道。有限档满额后排队，成功显示并保存历史后取消对应版本的原通知。
- 边缘防误触使用 system_server 输入过滤边界，按每根手指的 DOWN 决定整条流，保留已有无障碍输入过滤器。区域由 SystemUI UID 校验广播同步，3 秒租约失效释放；宿主连接失败回退透明窗口。角落按四个旋转选择握持侧两个角，默认 24dp 可调，系统安全区与小窗控件继续保留。

- 小窗触控区域合并到单个受信任窗口，通过 InternalInsetsInfo 的可触区域并集保留内容穿透、过渡阻断、入场手势、键盘安全区与系统优先级；隐藏接口预检失败时回退原六窗口。
- 图标批量设置仅从图标适配页顶部菜单进入，先选择应用，再独立选择桌面、莫奈、通知模式，每路默认保持现有。素材与生成缓存各应用独立保留，一次提交整个批次并刷新。

- TaskCover 的全屏端点使用所属显示器的 ScreenDecorationsUtils.getWindowCornerRadius，按系统统一窗口半径规则处理分辨率缩放并与小窗圆角插值；新建、恢复衔接及目标边界变化时更新，逐帧复用。
- 经模块接管的应用按包名与用户维护五分钟清理宽限。实际可见页面与存续的侧边隐藏小窗保留保护，停放的关联任务按不可见处理。仅重放已延期的内部强停请求，到期复核可见性、请求身份及 UID。设置、ADB 等外部 Binder 强停和模块明确的关闭结束操作即时执行；第三方工具转为内部异步执行的手动强停也适用宽限。
- 连续跳转共用 LaunchCoverFlow 和可转交的 TaskCover 表面，绘制与窗口状态稳定一秒后揭开，五秒总上限，熄屏暂停计时。跨任务转交失效时沿用新建遮罩回退，超时沿用现有窗口恢复。
