# 快捷建议与弹幕接入边界

## 输入 → 规则状态 → 执行

- [SuggestionHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/SuggestionHost.java>)接收剪贴板、受 UID 校验的配置/短信/亮屏广播、相机/蓝牙回调和通知；[SuggestionRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionRules.java>)负责有界本地识别、整体短信匹配、有效期调整、旋转准入和异步操作代际。打开与验证码输入共用 `Attempt`，旧回复/超时不能完成新请求；真实 Intent、ResultReceiver、主线程、相机及 WakeLock 仍由宿主执行。
- 亮屏建议由 [full ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>)输入触摸/灯光标志、默认组、唤醒/变暗事实；原生事件先执行成功，再在原生交互/锁屏检查后发布。`undimEvent/undimReady` 只收值，不持有 PowerGroup。屏幕关闭、锁屏和功能关闭保持原释放行为。
- [BarrageHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageHost.kt>)从原生通知提取结构化正文；[BarrageState](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageState.kt>)持有有界投递去重账本，决定消息准入、排队、空闲轨道和轨道复用。`Notice/View/expire` 的真实身份与队列仍留宿主；不把原生对象转成纯层身份。
- 弹幕最大待播 32、最近版本 128，档位 3/5/无限。满额只排队，降低档位不移除在播消息；无限档只复用可见轨道的行位置。截图标题 `ScreenshotUI` 不暂停或重建在播 View，键盘、其他系统面板仍暂停原 View，前台切换/息屏/关闭按原路径移除。

## 安全与失败恢复

- [BarrageProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageProvider.java>)与 [SuggestionProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionProvider.java>)只向管理器／实际 SystemUI UID 提供对应数据；弹幕历史仅 SystemUI 写入，建议状态上报只接受固定短字段，不提供任意远端偏好写入。存储、Bundle、通知监听与设备保护偏好不进纯层。
- 弹幕不取消、隐藏或标记已读原通知；历史保存成功与否均保留通知栏原条目、原始点击／回复／分组。原生移除仍只清理对应 key/postTime 的弹幕；保存失败可由同投递重试。配置或宿主失败保留原有快照退避／原生清理路径。
- [BarrageHeadsUp](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageHeadsUp.java>)在原生已允许的悬浮横幅判断后，仅当本用户已选全屏游戏环境安全、消息通过共用准入且已显示或入队时，以新建拒绝结果替代本次横幅；不修改可能共享的原生结果，不改声音／振动／勿扰。不在主线程、同步窗口失败或队列溢出时保留原生；已入队后若稍后显示失败，不补发横幅，原通知仍在通知栏。两个已核实的现代 logged／unlogged 入口覆盖新通知、更新及排序重评估；来电／闹钟／持续通知及任何非空 fullScreenIntent 均保持原生，避免影响全屏提醒。未知成员或安装失败只停用横幅抑制。
- 弹幕覆盖层沿完整显示区域布局，不额外扣除挖孔／系统栏安全边距，设置原生 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 与不拟合 Insets；固定轨道间距、非触摸／非焦点和安全环境暂停规则保持不变。物理挖孔本身不可显示像素，不承诺该处文字可见。
- 旋转由 [SystemUI 适配](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/RotationSuggestion.java>)与 [Launcher 适配](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/LauncherRotationSuggestion.java>)持有原生提议/按钮/Binder；只在用户点击且当前提议仍匹配时调用原生动作，不改自动旋转总开关。关闭恢复原生；Hook、窗口或主机确认失败放回原生展示，不吞原生按钮。
- [SuggestionEnvironment](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/SuggestionEnvironment.java>)在 WMS 就绪后独立读取默认显示器、本用户的原生焦点、IME 与安全遮挡，以受信系统 UID、协议及有界新鲜度向共享宿主提供事实；沿用 STATUS 心跳和真实焦点／遮挡变化，无小窗资格也不创建或争抢任务引擎。缺失、过期或读取失败仍隐藏建议，不把后端关闭伪装成系统遮挡。
- [SuggestionInput](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/SuggestionInput.java>)由独立建议环境安装，不依赖小窗总开关。复用纯数字/延迟校验；请求真实 UID、本用户／默认显示器、token、应用窗口类型与 UID、原生焦点同对象、IME 可见性、锁屏、WM 锁及定向 DOWN/UP 注入留原生，保持逐步短路重验；每个已提交 DOWN 始终配对 UP。

原生通知观察由共享 BackBridge 在已投递／移除回调之后转发，不拦截普通应用发送或 NMS 投递前路径。短信需用户授予 RECEIVE_SMS，标准接收器仅向 SystemUI 定向发送命中 OTP 的有界内容并共享发送身份，不记录正文。两类功能独立于小窗总开关，但仍依赖 SystemUiHost。快捷建议的「以小窗打开」是建议侧独立开关（`suggestions` 的 `window` 键，默认关闭）：只有该开关与小窗总开关都开启、实际小窗后端就绪且用户点击建议按钮时，才用 `SuggestionRules.Attempt` 做单次代际与 15 秒超时，把原始 Intent 经 `Wire.openIntent` 交给既有 OPEN 路由；未回复或失败提示后保留原生展示，不改通知投递路径。

Launcher 旋转桥接使用实际 SystemUI UID、Binder 租约及逐提议 ACK；超时／死亡恢复原生，Launcher 目标须为已知系统实际 HOME／本用户 UID。原生 Hook 可能局部安装，不承诺统一动态卸载；既有针对性检查不代替设备或跨系统兼容验证。
