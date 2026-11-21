# 通话、传感器与边缘触控接入边界

通话、传感器和输入过滤各自保留原生锁、调用顺序及失败路径；值规则不接收 Telecom／WM／输入对象，不以 ROM 名替代预检或权限。

## 通话拦截

- [CallBlockingRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingRules.java>)保持号码匹配、SIM 规则优先级、陌生人豁免；`Repeats.observe(subscriptionId, number, elapsed)`只持有订阅／规范号码／时间，三分钟、512 键有界内存历史，不接收 Telecom 对象。
- [CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>)在 `CallsManager.onCallFilteringComplete(Call, CallFilteringResult, boolean)`读取原生结果；只在原生允许时评估，沿原调用顺序排除非来电、自管、紧急、ECBM、相关属性、终态、其他用户。实际 `PhoneAccount` 必须具有 SIM 能力，经 registrar 的 subscription 再查槽位，未知／权限失败放行；不轮询猜 SIM、不担任默认电话角色。
- 同一 Call 重复回调缓存在 Module 的 `WeakHashMap`，保留原 `equals/hashCode` 弱键语义与锁内“查缓存 → 更新纯历史 → 存结果”原子性，不擅自改成引用比较。规则拒绝历史记录仍在原生执行成功返回后去重、异步写入。
- Telecom 反射签名全部先解析，安装失败卸载已装 Hook；原生拒绝／ignore 结果不改。Builder、Binder identity、PhoneAccount、联系人权限／65 秒缓存、后台 Provider I/O 留适配侧；`finally` 恢复 Binder identity。未知联系人状态仅使陌生规则放行，显式规则仍优先。
- [CallBlockingProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingProvider.java>)保持 system／管理器 UID、联系人权限校验；[Store](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingStore.java>)保留设备保护偏好、200 条本地记录与有界字段。未迁移磁盘格式，不记录异常消息中的号码。

## 短信拦截

- [SmsBlockingRules](<../../app/src/main/java/dev/local/sunwindowdemo/features/smsblocking/SmsBlockingRules.java>)只接收卡槽、发送方、正文与规则值：号码沿用电话规则的 +86 归一化，字母服务号按大写通配，关键词大小写不敏感、去控制字符，验证码豁免需同时命中标记词与 4–8 位数字。规则不持有 `SmsMessage`／tracker 对象。
- [SmsBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/smsblocking/SmsBlockingModule.java>)仅在 `com.android.phone` 主进程装配：`processMessagePart` 取事实并写调用线程 `ThreadLocal`，`BlockChecker.getBlockStatus` 读回并返回原生屏蔽状态。执行与清理全部交给原生 `processMessagePart`（永久删 raw 行、不投递广播），模块不写 Telephony 数据库、不接管默认短信应用。拒绝决定只存在于本次调用线程，不按发送方／reference number 跨短信缓存。多段或解码未知时不使用片段正文命中关键词；启用验证码豁免时，正文未知的陌生规则保守放行。记录须关联确切 handler／tracker 的原生永久删除，且实际影响行数大于零；未确认的异步过滤不记为已拦截。
- 判定路径只做内存规则与一次订阅→槽位查询；Provider、联系人和记录写入都在 worker。Provider 只接受自身、system 与本用户实际系统电话包 UID，电话包解析失败不回退常量 UID，联系人快照仍由持 `READ_CONTACTS` 的模块进程生成（≤4000），缺失／过期只放开陌生规则。`Binder.clearCallingIdentity` 均 `finally` 恢复，异常只记类型与位置，不含号码或正文。
- 作用域因此新增 `com.android.phone`：这是承载该功能所必需的宿主，不是给普通应用放权；未补勾时仅本功能不生效。

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

## 后台管理

- `full` 从既有 system_server 入口安装，总开关初始关闭。未保存档位的应用初始使用严格，已保存规则继续生效。生效范围为主用户自行安装且 UID 独占的第三方应用。正在播放媒体或使用 GPS 时自动临时保护。
- 应用列表直达进程详情。进程列表由清单组件、宿主已观察的普通应用进程和已配置名称组成。顶部菜单的批量动作覆盖应用全部进程，包括之后出现的进程。单项可以设例外，恢复默认会清除批量和单项覆盖，回到当前档位。
- 默认档位在原生冻结资格成立后等待 5 秒，同时保留系统临时解冻期限。严格档位从进程退后台起等待 5 秒，保护解除后可以发起冻结，沿用系统 Binder 检查与解冻流程。墓碑统一使用 V2，仅写入确认由该进程独占的 cgroup v2 节点，V2 操作失败时交回系统处理。保留旧测试版 STOP 信号的恢复逻辑。
- 后台应用仍被系统标为播放时，工作线程通过系统允许的 Unix 套接字读取限量混音器快照，按 UID、PID 和会话匹配实时音轨。只有两次复核皆不活跃且帧计数一致才解除媒体保护。混音器响应或格式不确定时保留保护。无待复核应用时停止采样。
- 抖音的测试规则在严格档默认关闭后台播放保护，可在抖音详情中开启。迷你和贴边小窗在仍占用原槽位时继续受墓碑保护，包括临时隐藏画面的阶段。关闭或释放至后台后重新计时。
- 每个可管理应用可开启「不进缓存」。主进程默认放行，显式进程和批量动作优先。仅对放行且进入缓存或上次活动状态的进程调整后台状态，内存紧张时仍可由系统回收。
- 「低内存时裁切」由用户逐个子进程开启，平时按所选档位冻结。可用内存持续低于 1 GiB 达 3 秒才启动回收，每次结束一个已冻结的候选，等待约 1 秒后复测。到达 3 GiB、没有候选、可用内存未增加或本轮已回收 4 个时停止。应用每次退后台期间，同名进程最多裁切一次。主进程、放行进程、可见小窗和正在进行的媒体播放或 GPS 不参与此项回收，系统内存回收照常执行。
- 后台唤醒锁限制默认覆盖符合条件的第三方应用，开发者工具可关闭总开关或为单个应用调整额度和设置豁免。来自同一应用的部分唤醒锁共用时长额度，重复获取不重置额度。前台、迷你和贴边小窗、通话、媒体播放及 GPS 活跃时暂停限制。额度用尽后沿用 Android 的停用和恢复流程。
- `tests/verify_background_targets.py` 核对两套 Android 17 归档的接口及 Binder／进程调用次序。冻结时序、锁屏消息、蓝牙播放、静音导航及贴边小窗恢复需要设备对比。

## 核验边界

复用现有通话、MotionPolicy和 EdgeInputPolicy／Filter定向检查。Telecom私有成员、SensorService命令、输入 split／注入均依赖真实平台；编译及假宿主检查不代表来电、传感器恢复、多指或已有无障碍链已在目标设备验收。传感器命令用户固定 0，不宣称多用户或逐个加速度事件过滤；未知／不完整配置时释放限制，命令超时不等于肯定未生效，须保留已提交跟踪与重试。
