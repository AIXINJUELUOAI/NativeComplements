# 系统功能入口与共享依赖

## 注册和宿主

正式入口以[八项注册表](<../../app/src/full/resources/META-INF/xposed/java_init.list>)为准；[静态作用域](<../../app/src/main/resources/META-INF/xposed/scope.list>)是 system、SystemUI、Pixel Launcher、Launcher3、电话（`com.android.phone`，仅短信拦截）。桌面只匹配主进程；IPC 权限另由[LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)验证已知系统实际 HOME 和本用户 UID，不因包存在或 ROM 名放行。

| 入口 | 宿主及委托 |
|---|---|
| [SunWindowModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SunWindowModule.java>) | system_server；独立安装亮屏建议、边缘过滤、传感器／Alive 等主版能力，再按资格与所有权安装小窗后端 |
| [BackBridge](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>) | SystemUI 主进程；Application 创建后连接共享宿主，安装原生角落 admission、通知观察、通知小窗、录屏／截图接入 |
| [LauncherGuard](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LauncherGuard.java>) | 已知 Launcher 主进程；旋转建议、受控角落流保护及可选历史任务过滤 |
| [CallBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingModule.java>) | system_server；独立 Telecom 过滤，不依赖小窗总开关 |
| [SmsBlockingModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/smsblocking/SmsBlockingModule.java>) | `com.android.phone` 主进程；在原生屏蔽号码路径上判定短信，不依赖小窗总开关 |
| [IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>) | 已知 Launcher 主进程；图标素材、系统主题上报、抽屉绑定与可选原生动画素材 |
| [SystemIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java>) | SystemUI 主进程；通知标识图标和原生 splash |
| [NavbarModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarModule.java>) | SystemUI／已知 Launcher 主进程；仅原生导航 owner 接入，无普通应用或 server 导航 Hook |

入口不是功能隔离单元，不能因名称带小窗便整段删除。正式产品仅 full；旧独立包兼容见[退役边界](<standalone-window.md>)。

## 功能合同导航

| 范围 | 当前合同 |
|---|---|
| 导航、横屏双栏、状态栏位置／比例、信号 | [外观边界](<appearance-boundary.md>) |
| 图标模式、素材、抽屉／通知／splash | [图标边界](<icon-boundary.md>) |
| 弹幕、OTP／剪贴板／亮屏与旋转建议 | [通知与建议](<notification-boundary.md>) |
| 通话、短信、UID 传感器政策、输入过滤 | [系统策略](<system-policy-boundary.md>) |
| 诊断、配置与身份 | [共享边界](<shared-boundary.md>) |
| 角落输入、通知／跨应用小窗及恢复 | [小窗接入](<floating-window.md>) |
| Alive 绑定、渲染和视觉交接 | [平台合同](<alive-platform.md>)、[SystemUI 合同](<alive-systemui.md>) |

## 共享宿主不等于共享开关

[SystemUiHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)创建建议、弹幕和边缘触控展示；它们有独立设置，不受小窗总开关控制，但仍依赖宿主初始化和共享环境观察。前台、IME、系统面板／截图及锁屏状态不是每个功能各自 Hook 一套。

BackBridge 在 main 连接宿主，失败清理并延迟重连。shade、状态栏、signal、rotation及导航另有自己的连接和局部安装路径；独立安装成功、宿主连接成功、配置可读和真实回调命中是不同状态。小窗 owner 冲突不应阻断全部主版能力。

[HostSnapshot](<../../app/src/main/java/dev/local/sunwindowdemo/core/HostSnapshot.java>)可供消费者保留最近完整配置；导航故意在读取失败时关闭覆盖，不能把其策略套给所有功能，也不能把保旧缓存称实时同步。

## 失败和验证边界

- Targets／Access 只收真实 ABI 差异，核字段、返回值、参数、唯一性、资源及必要 caller deopt。Hook 与普通反射调用依赖应分别核对。
- 安装器拥有局部 handles／恢复责任。`PROTECTIVE`不保证业务状态、临时字段、窗口或原生 Surface 自动回滚，也不代表已实现全量动态卸载。
- 配置与磁盘／解码留 worker；View／WindowManager 留 main；WM、Telecom、Power、输入过滤器各有原生锁与调用顺序，不能统称“都放模块线程”。
- 按变更风险复用现有定向、接口与打包检查；缺仓库外资料不能记通过，编译／假宿主／规则结果不代替目标设备效果。贡献只需说明实际改动，不强制附加模板或报告，也不自动执行安装、重启或设备设置变更。
