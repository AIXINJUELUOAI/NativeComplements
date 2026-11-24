# 共享宿主与诊断接入边界

本页描述当前真实代码，不是 ROM 名称兼容表。业务规则与平台执行分离不意味着必须把 Android 序列化、Provider 或 UI 包装成单实现接口。

## 诊断

- 纯值状态：[DiagnosticsBuffer](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/DiagnosticsBuffer.java>)控制事件/来源/异常白名单、整批校验、10 分钟采集、24 小时内存保留与 256KiB 上限。整批校验通过后才发布，非法批次不产生部分写入。
- 平台输入：[DiagnosticsProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/DiagnosticsProvider.java>)由真实 Binder UID 确定来源，拒绝隔离/其他用户/未知 UID，不相信来路提供的包名；宿主不能控制开始、导出或清空。
- 模块事件附录：[Diagnostics](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/Diagnostics.java>)仍使用固定 64 项非阻塞队列和后台批量 IPC；失败清队列，不在 Hook 线程递归诊断。Provider 的事件白名单、宿主授权与不可控制录制／导出的边界保持不变。
- 系统日志：用户打开管理器时异步申请 Root；未获授权时，在主动开始录制前再次申请，仍拒绝则不开始。获得权限不等于自动采集；录制需明确确认可能包含通知、账号、网址、令牌等敏感信息。Root 管理器永久拒绝后需用户在该管理器调整，不绕过授权。
- [DiagnosticsStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/DiagnosticsStore.java>)管理用户主动开始／停止的完整可访问 logcat 录制，前台服务维持切到其他应用后的短时采集；仅从开始时间读取所有可访问缓冲区，不按包名、PID、标签或等级过滤，不清空缓冲区、不调整系统日志大小，不扫描其他应用私有文件。10 分钟或原始日志 64 MiB 到达上限即停止，状态与导出说明标记原因、错误和截断，不能把权限或缓冲区缺失当成完整成功。
- 日志流式写入应用私有存储，停止 24 小时后过期，应用未运行时在下次运行清理；不自动上传，用户选择 SAF 位置导出 ZIP，其中包含日志文本和范围／结果说明。进程终止后不自动恢复录制，残留记录标为中断；停止仅结束本次自有 logcat 子进程，不使用全局进程名终止。
- 接入另一宿主时先在 Provider 加精确同用户 UID 授权，再向纯规则提供已认证来源与允许的事件值；不可扩大为通用 Root 命令、原始日志读取或文件导出通道。系统日志原文只由管理器的用户主动录制路径读取，不能经导出的 Provider 暴露。

## 配置快照与产品组成

- [HostSnapshot](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>)是共用 Android 存储/传输适配器，处理 Bundle、AtomicFile、Provider 和宿主存储目录；不是系统兼容业务接口。Provider 不可达可保留最近完整快照，必须保留每个功能的失败语义，不能一律强制关闭或继续启用。
- [ModuleIdentity](<../../app/src/main/java/dev/local/sunwindowdemo/core/ModuleIdentity.java>)与[WindowBackendOwnership](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>)保留主包／旧独立包身份隔离和跨 ClassLoader first-claim；当前仅构建 full，不重新发布 window。非小窗功能不应借用小窗接管权或总开关，旧包兼容见[退役合同](<standalone-window.md>)。
- [ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>)组合正式主模块宿主适配器，不把全部能力安装放进小窗 owner 分支。隐藏管理页不等于卸载 Hook，停止独立产物不等于可以删其身份／恢复墓碑。

## 核验边界

复用现有 Provider 授权和批次原子性检查；缺资料不记通过，构建和本地规则不证明设备启动、桌面或目标 ROM 全兼容。模块不自动安装、重启或修改 LSPosed 配置，也不把诊断内容及设备现场写入公开文档。
