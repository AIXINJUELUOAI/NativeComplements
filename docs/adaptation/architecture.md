# 架构与维护边界

## 产品和进程

[构建配置](<../../app/build.gradle.kts>)仅创建 `full` 产品，安装身份为 `dev.nativecomplements.module`，源码包保留 `dev.local.sunwindowdemo`。升级签名、权限、旧配置键与协议 16 是兼容点，不因重组文件而改变。

| 环境 | 职责与入口 |
|---|---|
| 管理器 | [ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)提供配置、权限、诊断和用户请求；[WindowController](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/ui/WindowController.kt>)不直接持有系统 Task |
| system_server | [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>)安装任务、视口、输入及恢复接入；[CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>)独立处理 Telecom |
| SystemUI 主进程 | [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)连接手势和共享宿主；系统图标、导航及各功能保留局部安装边界 |
| 已知 Launcher 主进程 | 手势冲突保护、历史任务、图标和原生导航源；宿主匹配不替代桥接 UID 认证 |
| 本应用壁纸进程 | Alive 在普通应用 UID、自有 Surface 中执行 JNI；不把渲染库注入系统进程，详[Alive 平台](<alive-platform.md>) |

准确入口为[七项注册表](<../../app/src/full/resources/META-INF/xposed/java_init.list>)；[四项静态作用域](<../../app/src/main/resources/META-INF/xposed/scope.list>)为 system、SystemUI、Pixel Launcher、Launcher3。[LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)只允许白名单内系统／更新系统 HOME，授权时重新解析实际 HOME 与本用户真实 UID，不向已安装但非 HOME 的桌面回退。ROM 维护记录只用于署名和反馈，不授予 Hook 或 IPC 权限。

## 事件、规则与执行

```text
原生回调／已认证请求
  → 采样事实：用户、显示器、任务／流身份、几何、安全及转场状态
  → 共用规则／状态决定
  → 平台在原线程／锁下重验并操作 Task、Surface、输入或窗口
  → 完成／拒绝／中断结果推进状态；失败恢复原生对象并释放自有资源
```

这是审查模型，不是新增事件总线或万能 DTO。[WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>)、[WindowNavigation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowNavigation.java>)、[WindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowPresentation.java>)、[WindowLifecycle](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowLifecycle.java>)等承载值规则；Android 对象、票据、时钟、Binder、AtomicFile／Parcel、WM 锁和事务留在对应平台侧。

[WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)仍负责原生协调，不是纯平台无关核心；[FrameworkAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/FrameworkAccess.java>)是反射预检／缓存，不是完整可替换平台接口。真正不同的签名或安装时机由功能侧 Access／Targets 解析，相同 Hook 和普通规则不复制，也不按 ROM 名分叉安全条件。

## 配置与通信

- 管理器写本包配置，经各功能 UID 校验 Provider 向宿主提供最小数据；不同 Provider 的授权范围不能统一放宽为“系统应用可读”。
- [ConfigProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/ConfigProvider.java>)只允许自身／实际 SystemUI UID，除读取外仅提供快捷目标列表的窄写入。迁移和完整复制完成后才发布 ready。
- [Wire](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/Wire.java>)使用显式目标和共享发送身份。广播接收核验实际发送 UID；Binder 另核 descriptor、操作、协议、数据边界、token、受控 session 和租约。不能凭 action 或自报包名授权。
- 帧通道仅接受 system UID，验证协议后合并到 SystemUI main；管理器结果接收同时核 action、system UID 和接收权限。预测返回含同步结果／事务所有权，不能改成普通异步控制。
- [HostSnapshot](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>)可保留最近完整配置，不是统一故障策略。导航失联关闭覆盖；其他功能可能保旧快照并重试，详各功能文档。
- 小窗启用页的 Freeform／宿主检查是提醒，可由用户明确继续保存开关；不会写系统设置、伪装能力通过或绕过后端接管预检。关闭开关也不是全量动态卸载。

## 线程、锁和所有权

- server Hook 可由 Binder、WM traversal、动画或显示线程进入。Task／Session 重验、布局与事务遵守 `mGlobalLock`；模块 display-priority worker 上的 SF Choreographer 不能成为并发共享事务的借口。
- Provider、配置、包查询、解码与磁盘工作留允许的 worker；不能在持 WM／Power 锁的帧路径增加阻塞 IPC。原生要求同步追加的事务不随意 post。
- SystemUI／Launcher View 和 WindowManager 操作在 main；宿主设置读取后整体发布快照。ThreadLocal、临时字段和 Canvas 修改必须 `finally` 恢复。
- 原生 Task Surface 是借用对象；自有 cover、背板、装饰与输入窗口须在替换、退出及 detach 路径释放。alpha 为零不等于禁触。
- 安装异常只撤销该安装器拥有的 handles；`PROTECTIVE` 不保证字段、窗口、journal 自动回滚。宿主死亡、锁屏、安全确认、旋转与关闭有不同恢复语义，不能合成一个粗暴 close。

## 兼容与检查

旧独立产品已停止构建，但[身份、资格与 first-claim 墓碑](<standalone-window.md>)仍保护旧包共存，不强抢或跨包迁移恢复记录。主模块其他功能不随小窗 owner 冲突整组关闭。

小窗运行改动按[差异原因清单](<../../tests/runtime-changes.json>)记录，不改不可变基线掩盖差异。R8、签名、入口／作用域及必要定向检查不能证明设备动画、功耗、输入或其他 ROM 兼容。接入应针对真实 ABI 差异补最小检查，不要求贡献者另交模板或报告。各功能入口见[索引](<README.md>)。
