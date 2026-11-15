# 外观与控制：规则与宿主执行边界

本轮不增加入口、配置、宿主接口或兼容性承诺。外观功能只在主版安装原生 Hook；独立小窗版不安装这些可选 Hook。

## 值规则

- [ShadePolicy](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadePolicy.java) 接收开关、横屏事实、宽度 dp、资源 ID、状态栏偏移、图标槽位与已采样的图标/可用视口尺寸；输出是否强制双栏、资源覆盖值（`null` 保留原结果）、范围内设置及不越过原生视口的倍率。不接收 Android 对象或反射身份。
- [SignalDisplayPolicy](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplayPolicy.java) 已有 NR 显示阈值及原生编码规则，继续复用；不改变无线网络质量、订阅或共享信号模型。
- 导航栏只有一个独立开关。两个 owner 的默认显示器、手势导航和存活检查已紧贴原生输入适配，不为单个条件另造 policy 或工厂。

## 事件、原生执行与恢复

| 链路 | 原生适配边界 |
| --- | --- |
| SystemUI 导航栏与 Pixel Taskbar | [NavbarHost](../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarHost.java)、[NavbarTaskbarHost](../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarTaskbarHost.java) 保留准确字段/方法预检、caller deopt、弱身份映射及主线程限制。原生先创建参数，再仅清空 navigationBars provider 的尺寸；IME overrides、手势 source、窗口几何不改。背景仅在所属绘制调用临时空裁剪，原 draw 继续运行，`finally` 恢复 Canvas。关闭时调用原生方法重建当前及四旋转参数，不使用已经改过的参数作恢复值；Taskbar 仍按原顺序对已附着窗口提交重算参数。 |
| 横屏双栏 | [LandscapeShade](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/LandscapeShade.java) 保留 legacy/已核实 hydrated ABI 选择、原方法先执行与更新顺序；只在开关开启、横屏、宽度至少 600dp 时覆盖。 [ComposeShadeLayout](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ComposeShadeLayout.java) 从宿主资源表解析两个 bool ID，只在 repository 构造/emit 的 ThreadLocal 作用域内取规则覆盖值，`finally` 恢复作用域。刷新调用现有 repository callback，不改 readonly StateFlow、不伪造全局配置/旋转。 |
| 状态栏位置及图标比例 | [StatusBarOffset](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StatusBarOffset.java) 采样 density 并只平移内容；[StatusIconScale](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StatusIconScale.java) 采样 Drawable bounds、ImageMatrix、padding 和原生 scale，调用值规则后仅在 draw 期间临时替换 scale，`finally` 恢复。默认 100% 直接走原生；网络、电池、时钟不进系统图标白名单。 |
| 信号显示 | [SignalDisplay](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplay.java) 保留真实订阅、owner/Drawable 身份、ServiceState/NR/NTN/primary 采样与回调后重绘；标准 Drawable 仅 scoped binding 调整，叠层 Compose 由 [StackedSignalDisplay](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalDisplay.java) 构造展示副本；不修改共享 native Flow/model。 [StackedSignalTargets](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalTargets.java) 是 native 反射 ABI 选择，不是业务 policy。 |

## 配置与失败策略

[NavbarProvider](../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarProvider.java) 仅向自身和实际 SystemUI/Pixel 桌面 UID 暴露只读开关。导航栏 Provider 暂不可用时按原策略关闭覆盖、重建原生布局并延迟重试。安装失败撤销已安装 Hook。

[ShadeProvider](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeProvider.java) 向自身/SystemUI 提供外观快照，Launcher 只有抽屉开关投影；没有远端写入口。[ShadeStore](../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeStore.java) 保留设备保护存储、变更通知和实际 UID 校验。双栏/偏移/信号使用既有 HostSnapshot：Provider 暂不可用可保留上次完整配置并重试，不能与导航栏的失败关闭策略混为一谈。双栏 native 重配置部分失败时关闭覆盖并尽力恢复所有面板与 Compose callback；没有任意 IPC 失败立即清空全部主题/外观状态的承诺。

## 验证边界

复用现有导航栏、Taskbar、Shade、图标倍率及信号检查；本轮仅把原倍率/白名单断言改为直接验证纯规则，并在原 Shade 检查补少量边界与 native 原结果身份断言，不新建测试套件。实施冻结时未由本任务执行构建、测试或设备操作，检查结果由 Lead 集成记录。既有设备/归档 ABI 证据不等于本轮真机效果或其他发行版兼容；更换宿主需重新核对资源、签名、caller deopt 和关闭恢复。
