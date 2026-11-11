# NativeComplements

- 默认简体中文。原生 Android 设置风格，Compose MD3/M3E、系统动态颜色与深色模式。
- 首页按功能独立跳转；功能做到可用后再加入入口。当前功能为小窗、弹幕通知、来电拦截、边缘防误触、快捷建议、加速度传感器控制与图标适配，不创建其他功能的占位页面或接口。
- 管理页面共用 SettingsPage 标题栏和系统安全区，页面内部不重复创建标题与 Scaffold。开发者工具仅从“关于”连续点击版本号七次后显示，日常页面不展示后端连接及实验信息。
- 单 app Gradle 模块。管理界面位于 home、navigation、core/ui 和 features/floatingwindow/ui。
- 第一版行为基线是 Demo8.2。基线原有26个Java文件，当前变更清单记录于 tests/runtime-changes.json，代码位于 features/floatingwindow/legacy，Java 源码包路径保持 dev.local.sunwindowdemo。修改小窗时记录原因，不改基线摘要来隐藏差异。
- applicationId 自 0.6.0 起为 dev.nativecomplements.module（模块名“类原生补全计划”）；它与三个系统作用域、签名权限和 corner 偏好键是覆盖升级兼容点。使用 SystemUI 宿主与协议16，不依赖无障碍，不增加普通应用作用域或包名特判。
- .local/demo.keystore 是现有覆盖升级签名，仅本地保存。不要提交签名、设备数据或本机配置。
- JDK17+、SDK android-37.0、Gradle8.14.4。本机可用 Android Studio JBR，跨目录构建通过环境变量配置路径。
- 构建：gradlew.bat --no-daemon --console=plain :app:assembleRelease。按变更运行必要的针对性检查，不把构建成功表述为真机效果保证。
- 基线及主机检查：python tests/verify_runtime.py；归档目标签名检查：python tests/verify_target.py。
- 最终打包：python tests/package_release.py，仅交付 APK，不创建源码ZIP、逐轮备份或额外报告。生成物放 deliverables/、verification/ 或 build/，不提交 Git。
- 不自动安装、重启或更改设备的 LSPosed 配置。
- main 用于集成，交付版本打 tag。并行任务使用各自的分支与 worktree；公共 Hook 入口、首页导航、共享配置和版本号由集成任务统一修改。
- 保留用户未提交工作，不执行 reset 或强制推送。测试签名和原始基线不清理。
- 小窗已移除180秒期限，配置由UID检查的Provider同步到SystemUI，另提供仅可写快捷项列表的管理接口；启动健康计数和全套动态Hook卸载尚未实现。

- 电话拦截位于 features/callblocking；使用独立 CallBlockingModule 和 UID 检查的 CallBlockingProvider，设备保护存储。只使用既有 system 作用域，按实际来电 PhoneAccount 识别卡槽，不轮询铃响猜 SIM，不接管默认电话角色。

- 快捷控制位于小窗悬浮菜单的“快捷功能”，不再提供独立游戏助手与侧边提示条。弹幕通知独立位于 features/barrage，使用 UID 检查的只读 Provider 和设备保护存储，不依赖小窗总开关，不拦截原生通知。
