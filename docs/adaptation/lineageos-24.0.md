# LineageOS 24.0 接入

## 来源与集成

贡献者：[LFRon](https://github.com/LFRon)（提交作者 mozixun）。原始贡献为 [PR #4](https://github.com/AIXINJUELUOAI/NativeComplements/pull/4)，提交 `89b0c783518b833da5f39bfa5de0131a13de392b`。集成基于 code165，保留 PR 祖先及作者；当前维护范围以功能合同为准。

维护者记录仅用于首次一次提示、关于页署名与反馈路由，不作为权限或兼容性认证。维护者仓库未开启 Issues 时使用[主项目反馈](https://github.com/AIXINJUELUOAI/NativeComplements/issues)，不要求贡献者补测试模板或样例，不在每个功能页添加“未适配／未验证”提醒。

## 真实接入，而非 ROM 分支

- 静态作用域为 `system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`、`com.android.launcher3`，仅新增 Launcher3 宿主，不新增普通应用作用域。
- [LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)负责已知桌面包与实际 HOME／UID；不是任意 HOME 即获得模块能力，也不按 ROM 字符串决定 Hook 安装。
- 相同 Hook 共用。真正不同的 Hook 目标、参数布局和安装时机集中在功能自己的小接入／Targets，公共状态、规则、身份核验和恢复逻辑不复制。
- 必要 Hook 按宿主实际类、方法、字段类型预检，未知形状保持原生；尚无回报不等于不支持。安装成功不表示视觉、功耗和全部恢复已验收。

## PR 差异的集成取舍

| 范围 | 集成方式 |
|---|---|
| 小窗框架 | 复用既有 FrameworkAccess 与共用准入／恢复，集中 Task resizability、ActivityStarter 参数布局目标 |
| 返回输入 | 保 Pixel synthetic 路径；Kotlin Function1 从构造参数捕获真实 callback，严格归属和事件资格不放宽 |
| 通知小窗 | 保 main 已恢复的正文原 PendingIntent、派发前原生回退及派发后不重发；LOS 通过精确发送点接入 |
| 截图 | 字段／Kotlin getter 仅局部只读解析，不复制截图业务 |
| 桌面图标 | 接入 Kotlin getter 和缓存参数布局；相同渲染 Hook 共用 |
| 抽屉莫奈 | **不集成 PR 的自动 Minimal 切换**：该行为会改变系统桌面主题偏好，并非单纯 ABI 适配。保原生主题门控；用户自己在系统“壁纸与样式”中选择主题图标／Minimal |
| 导航栏 | 既有 SystemUI／Taskbar 宿主边界保留，相同 Hook 不再套 ROM 工厂 |
| 横屏双栏 | Legacy 与 Hydrated 的不同安装目标隔离，规则共用；不强开 Scene，不写只读 Flow |
| 信号 | 原有回调和 stacked targets 保留；不同 binder／TelephonyCallback 接入独立，避免重复 Hook 和半安装 |
| 来电／传感器／边缘 | 相同接口及共用规则保留，桌面排除范围加入 Launcher3 |

## 上游资料与检查边界

PR 附有[固定源码来源](<../../tests/lineageos24-provenance.json>)及[源码合同检查脚本](<../../tests/verify_lineageos24.py>)，固定官方 `lineage-24.0` 的 framework、SystemUI 库、Launcher3、Telecomm、ThemePicker 提交。脚本需要贡献者机器上仓库外源码树；没有该树时输出 **SKIP**，不记为通过，也不把贡献者文档中的旧主机或设备结论当作当前验收结果。

PR 原文提到 OnePlus Ace 3 / LOS24 的加载、Taskbar、旋转、边缘同步及主题切换记录，但原始设备材料未随仓库提供，不依赖这些声明作为验收；其中自动主题切换已明确不纳入。无需补交材料才允许使用，也不以缺材料为由给每个功能弹警告。

当前架构与检查入口见[接入索引](<README.md>)，发布历史见 [Git 提交记录](https://github.com/AIXINJUELUOAI/NativeComplements/commits/main)。不维护按功能的认证矩阵，不自动安装、重启、改 LSPosed 或设备设置。
