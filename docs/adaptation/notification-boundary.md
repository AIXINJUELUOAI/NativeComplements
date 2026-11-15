# 快捷建议与弹幕接入边界

## 输入 → 规则状态 → 执行

- [SuggestionHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/SuggestionHost.java>)接收剪贴板、受 UID 校验的配置/短信/亮屏广播、相机/蓝牙回调和通知；[SuggestionRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionRules.java>)负责有界本地识别、整体短信匹配、有效期调整、旋转准入和异步操作代际。打开与验证码输入共用 `Attempt`，旧回复/超时不能完成新请求；真实 Intent、ResultReceiver、主线程、相机及 WakeLock 仍由宿主执行。
- 亮屏建议由 [full ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>)输入触摸/灯光标志、默认组、唤醒/变暗事实；原生事件先执行成功，再在原生交互/锁屏检查后发布。`undimEvent/undimReady` 只收值，不持有 PowerGroup。屏幕关闭、锁屏和功能关闭保持原释放行为。
- [BarrageHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageHost.kt>)从原生通知提取结构化正文；[BarrageState](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageState.kt>)持有有界投递去重/精确版本移除账本，决定消息准入、排队、空闲轨道、轨道复用及保存后取消资格。`Notice/source/View/expire` 的真实身份与队列仍留宿主；不把原生对象转成纯层身份。
- 弹幕最大待播 32、最近版本 128，档位 3/5/无限。满额只排队，降低档位不移除在播消息；无限档只复用可见轨道的行位置。截图标题 `ScreenshotUI` 不暂停或重建在播 View，键盘、其他系统面板仍暂停原 View，前台切换/息屏/关闭按原路径移除。

## 安全与失败恢复

- [BarrageProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/barrage/BarrageProvider.java>)与 [SuggestionProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/SuggestionProvider.java>)保留实际调用 UID 校验；历史仅 SystemUI 写入。存储、Bundle、通知监听与设备保护偏好不进纯层。
- 弹幕 `addView` 成功登记在播 → 历史 `commit` 返回成功 → 主线程确认仍在播且为同 key/postTime/投递内容 → 调原生取消。显示/保存/取消失败不吞原通知；旧版本保存或移除不能影响新版本，保存失败可由同投递重试。配置或宿主失败保留原有快照退避/原生清理路径。
- 旋转由 [SystemUI 适配](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/RotationSuggestion.java>)与 [Launcher 适配](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/LauncherRotationSuggestion.java>)持有原生提议/按钮/Binder；只在用户点击且当前提议仍匹配时调用原生动作，不改自动旋转总开关。关闭恢复原生；Hook、窗口或主机确认失败放回原生展示，不吞原生按钮。
- [SuggestionInput](<../../app/src/main/java/dev/local/sunwindowdemo/features/suggestions/runtime/SuggestionInput.java>)复用纯数字/延迟校验；token、应用窗口类型与 UID、原生焦点同对象、IME 可见性、锁屏、WM 锁及定向 DOWN/UP 注入留原生，保持逐步短路重验；每个已提交 DOWN 始终配对 UP。

这里只记录代码边界；既有针对性检查不代替真机效果或跨系统兼容验证。
