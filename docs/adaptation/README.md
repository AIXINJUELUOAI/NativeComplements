# 宿主接入与维护索引

正式产品仅为主模块 `full`。小窗、系统功能与 Alive 共用“平台事件输入 → 规则／状态 → 平台执行与恢复”的边界；相同 Hook 共用，真实 ABI 差异放在功能侧局部 Access／Targets，不建立 ROM 插件框架。

静态作用域为 `system`、SystemUI、Pixel Launcher、Launcher3、电话（`com.android.phone`，仅短信拦截）。桌面桥接只信任已知系统实际 HOME 及其本用户 UID；包存在、ROM 名或同一 Android API 均不等于授权或兼容证明。当前产品配置以[构建配置](<../../app/build.gradle.kts>)为准，社区接入说明见[轻量协作](<../community-adaptation-plan.md>)。

## 阅读入口

| 范围 | 文档 |
|---|---|
| 进程、配置、身份、线程与恢复 | [架构边界](<architecture.md>) |
| 小窗输入、启动、视口、任务与 Surface | [小窗接入](<floating-window.md>) |
| 旧独立小窗的身份与迁回兼容 | [退役产品兼容](<standalone-window.md>) |
| 八个模块入口及共享宿主关系 | [系统功能总览](<system-features.md>) |
| LOS24 接入来源与取舍 | [LineageOS 24.0](<lineageos-24.0.md>) |
| 导航、双栏、状态栏及信号 | [外观边界](<appearance-boundary.md>) |
| 图标素材、抽屉与系统图标 | [图标边界](<icon-boundary.md>) |
| 快捷建议、旋转与弹幕 | [通知与建议边界](<notification-boundary.md>) |
| 通话、传感器与边缘触控 | [系统策略边界](<system-policy-boundary.md>) |
| 配置快照与诊断 | [共享边界](<shared-boundary.md>) |
| Alive 资源、JNI、生命周期及系统交接 | [资源](<alive-assets.md>)、[渲染](<alive-renderer.md>)、[平台](<alive-platform.md>)、[绑定](<alive-module.md>)、[SystemUI](<alive-systemui.md>) |

## 维护原则

- Hook 目标按实际成员、类型、唯一性及调用语义预检；失败保持原生或按该功能的局部恢复路径退出。未知不能伪装成失败，也不额外显示逐功能兼容提醒。
- 保留 UID、用户、显示器、认证、任务所有权、nonce、租约与恢复记录；规则输出不是原生执行成功。
- 复用必要的现有定向检查。主机规则、目标接口检查、构建／签名核验与设备效果分开判断，缺少仓库外资料的检查应为 SKIP，不记为通过。
- 贡献说明围绕实际改动即可，不要求额外测试模板、报告或社区认证。安装、完整重启和设备配置由用户操作；可选场景见[检查清单](<../../DEVICE_ACCEPTANCE.md>)，仓库约定见[AGENTS](<../../AGENTS.md>)。

本目录只维护当前合同。讨论过程、逐轮检查和发布回执不在此复制；历史可由 Git 查询。
