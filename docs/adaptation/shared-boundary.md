# 共享宿主与诊断接入边界

本页描述当前真实代码，不是 ROM 名称兼容表。业务规则与平台执行分离不意味着必须把 Android 序列化、Provider 或 UI 包装成单实现接口。

## 诊断

- 纯值状态：[DiagnosticsBuffer](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/DiagnosticsBuffer.java>)控制事件/来源/异常白名单、整批校验、10 分钟采集、24 小时内存保留与 256KiB 上限。整批校验通过后才发布，非法批次不产生部分写入。
- 平台输入：[DiagnosticsProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/DiagnosticsProvider.java>)由真实 Binder UID 确定来源，拒绝隔离/其他用户/未知 UID，不相信来路提供的包名；宿主不能控制开始、导出或清空。
- 执行：[Diagnostics](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/Diagnostics.java>)使用固定 64 项非阻塞队列和后台批量 IPC；失败清队列，不在 Hook 线程递归诊断。[DiagnosticsStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/diagnostics/DiagnosticsStore.java>)负责时钟、通知、Bundle、SAF 与 ZIP 输出，保留现有原生实现。进程退出即丢弃诊断会话，非磁盘日志归档。
- 接入另一宿主时先在 Provider 加精确同用户 UID 授权，再向纯规则提供已认证来源与允许的事件值；不可扩大为通用命令或传入原始异常、正文、电话号码。

## 配置快照与产品组成

- [HostSnapshot](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>)是共用 Android 存储/传输适配器，处理 Bundle、AtomicFile、Provider 和宿主存储目录；不是系统兼容业务接口。Provider 不可达可保留最近完整快照，必须保留每个功能的失败语义，不能一律强制关闭或继续启用。
- [ModuleIdentity](<../../app/src/main/java/dev/local/sunwindowdemo/core/ModuleIdentity.java>)与[WindowBackendOwnership](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>)分别保留 full/window 身份隔离与小窗进程内先到所有权。非小窗功能不应借用小窗的接管权或总开关。
- [full ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>)与[window ProductFeatures](<../../app/src/window/java/dev/local/sunwindowdemo/ProductFeatures.java>)是实际编译源集边界，负责组合各自宿主适配器；window 不安装其他功能 Hook，不能仅依赖管理页隐藏。新增功能需显式确定属于哪一产品，不能无意穿透此边界。

## 最低必要核验

复用现有诊断 Provider 授权和批次原子性检查，不为此次提取新增测试套件。构建和本地规则检查不能证明设备可开机、桌面可用或目标 ROM 全兼容；仍须用户安装回归，模块不自动重启或修改 LSPosed 配置。
