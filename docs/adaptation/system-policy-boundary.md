# 通话、传感器与边缘触控接入边界

通话、传感器和输入过滤各自保留原生锁、调用顺序及失败路径；值规则不接收 Telecom／WM／输入对象，不以 ROM 名替代预检或权限。

## 通话拦截

- [CallBlockingRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingRules.java>)保持号码匹配、SIM 规则优先级、陌生人豁免；`Repeats.observe(subscriptionId, number, elapsed)`只持有订阅／规范号码／时间，三分钟、512 键有界内存历史，不接收 Telecom 对象。
- [CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>)在 `CallsManager.onCallFilteringComplete(Call, CallFilteringResult, boolean)`读取原生结果；只在原生允许时评估，沿原调用顺序排除非来电、自管、紧急、ECBM、相关属性、终态、其他用户。实际 `PhoneAccount` 必须具有 SIM 能力，经 registrar 的 subscription 再查槽位，未知／权限失败放行；不轮询猜 SIM、不担任默认电话角色。
- 同一 Call 重复回调缓存在 Module 的 `WeakHashMap`，保留原 `equals/hashCode` 弱键语义与锁内“查缓存 → 更新纯历史 → 存结果”原子性，不擅自改成引用比较。规则拒绝历史记录仍在原生执行成功返回后去重、异步写入。
- Telecom 反射签名全部先解析，安装失败卸载已装 Hook；原生拒绝／ignore 结果不改。Builder、Binder identity、PhoneAccount、联系人权限／65 秒缓存、后台 Provider I/O 留适配侧；`finally` 恢复 Binder identity。未知联系人状态仅使陌生规则放行，显式规则仍优先。
- [CallBlockingProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingProvider.java>)保持 system／管理器 UID、联系人权限校验；[Store](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingStore.java>)保留设备保护偏好、200 条本地记录与有界字段。未迁移磁盘格式，不记录异常消息中的号码。

## 加速度传感器控制

- [MotionPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionPolicy.java>)承载 UID／PID 前台七秒窗口、到期及 ALLOW/FIRST_SCREEN/ALWAYS 状态；UID 排除／shared UID 最强规则位于 `targets(List<App>, fallback, overrides)`。`App`只含 UID、包名、persistent 值，`Targets`为不可变规则和命令目标映射；排除低 UID、持久系统包、模块、SystemUI 与已知桌面包，保留枚举顺序下最后非 ALLOW 包作为命令目标。
- [MotionAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionAccess.java>)仍在 `ActivityRecord.setState(State, String)`原执行之后，确认 RESUMED 且 focused 才向 worker 投递 UID／PID／elapsed；PM 包枚举、Package UID 再验证、配置读取与 Hook ABI 属平台。禁用／配置错误释放、已下发命令跟踪及重试、五秒失败重试、到期调度不变。
- [SensorCommand](<../../app/src/main/java/dev/local/sunwindowdemo/features/motion/SensorCommand.java>)保持 SensorService Binder shellCommand、1500ms 完成等待、结果校验、权限 identity 恢复；不是生成 shell 进程。Provider 权限、设备保护偏好、原 schema 与状态上报不改。

## 边缘防误触

- [EdgeInputPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputPolicy.java>)不再导入 MotionEvent/InputDevice，只接收 stream 键、Action、全部指针 mask、变更指针 bit 和按下时 finger／坐标事实。每指 DOWN 决定整条流；POINTER_UP 先计算本事件 accepted mask 再清抬起位，UP/CANCEL 释放流。区域关闭不截断已经抑制的指针。
- [EdgeInputFilter](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputFilter.java>)保留原生事实采样及 source／display 短路：非默认显示器或非 touchscreen 直接返回全部指针，不触碰纯状态；仅 DOWN/POINTER_DOWN 读取 tool，只有 finger 才取坐标。设备与显示器组成原 stream 键，不模拟事件。
- 精确 ABI 仍为 InputManagerService `start`／`setInputFilter(IInputFilter)`／`filterInputEvent(InputEvent,int)`，原 native `setInputFilterEnabled`／`injectInputEvent` 与 MotionEvent.split。全收保留原 proceed；全拒回收原事件；部分通过沿现有 accessibility filter 或按 FILTERED flags 原生重注入，原锁、回收及失败永久旁路顺序不改。安装失败卸载已装 Hook，不抹掉已有无障碍过滤器。
- [EdgeTouchHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchHost.java>)保留 SystemUI UID 认证区域广播、三秒租约／透明窗口回退、锁屏／IME／系统面板保护与 `close` 清理；[EdgeTouchGeometry](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchGeometry.java>)本来就是纯坐标规则，未重复提取。Provider 与边界数组验证仍在原信任边界。

## 实验性暗处暂停息屏显示

- 开发者工具提供默认关闭的暗处暂停开关与 0–5 lux 暗阈值；[DarkAodPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/darkaod/DarkAodPolicy.java>)仅处理暗 10 秒／亮 2 秒防抖和亮阈值滞回，不持有 Android 对象。
- [配置桥接](<../../app/src/main/java/dev/local/sunwindowdemo/features/darkaod/DarkAodProvider.java>)仅允许本应用与同用户真实系统 SystemUI UID 读取设备保护快照；配置变更通过 ContentObserver 传递，不修改系统 AOD 开关。
- SystemUI 宿主仅介入普通默认显示器 AOD，会话结束撤销传感器与延迟回调；依赖唤醒型环境光与有效原生 proximity。黑暗与遮挡各自保留原因，恢复仅释放本功能拥有的暂停；未知 proximity 不冒充远离。通知 pulse、认证、终态、DOCKED／MINMODE 不套用普通暂停规则。实际真熄屏、休眠唤醒与功耗仍需设备验收。

## 核验边界

复用现有通话、MotionPolicy和 EdgeInputPolicy／Filter定向检查。Telecom私有成员、SensorService命令、输入 split／注入均依赖真实平台；编译及假宿主检查不代表来电、传感器恢复、多指或已有无障碍链已在目标设备验收。传感器命令用户固定 0，不宣称多用户或逐个加速度事件过滤；未知／不完整配置时释放限制，命令超时不等于肯定未生效，须保留已提交跟踪与重试。
