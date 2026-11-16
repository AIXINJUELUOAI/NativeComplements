# 外观与控制接入边界

## 规则与配置

[ShadePolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadePolicy.java>)接收开关、横屏／宽度、资源 ID、偏移、槽位与已采样尺寸，输出双栏、资源覆盖及视口内倍率；不持原生对象。[SignalDisplayPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplayPolicy.java>)只处理 NR 展示阈值和原生编码，不改变网络质量、订阅或 modem。

[ShadeStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeStore.java>)使用独立设备保护配置。双栏默认关闭；状态栏偏移 -32～+32dp，图标比例 80～120%、默认 100%。抽屉莫奈是独立设置，窗口／遮罩莫奈恒关并忽略旧值。

## 原生执行与恢复

| 链路 | 维护合同 |
|---|---|
| 导航栏／Taskbar | [NavbarHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarHost.java>)与[NavbarTaskbarHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarTaskbarHost.java>)只对默认显示器、手势导航、存活的准确 owner 生效。原生先建参数，再清 navigationBars provider 尺寸；不改 IME overrides、手势 source、窗口几何、触区或 pill 明暗。仅所属背景 draw 临时空 clip，原 draw／动画照常，finally 恢复 Canvas |
| 导航关闭 | main 调原生方法重建当前及四旋转参数，不用已修改尺寸恢复；Taskbar 仅向已附着的同 owner WM／root 提交重算参数，不写原生缓存。字段／方法及必要 caller deopt 预检失败撤销本后端；不能由共享 Launcher 包名推定任意 Taskbar ABI 已支持 |
| 横屏双栏 | [LandscapeShade](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/LandscapeShade.java>)按真实 legacy／Hydrated 目标安装，只在开启、横屏且至少 600dp 覆盖。不强开 Scene、不改 DPI；native panel 更新顺序保留 |
| Compose QS | [ComposeShadeLayout](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ComposeShadeLayout.java>)按 repository 的实际字段选择构造／emit，解析宿主两项 bool 资源，仅 ThreadLocal 范围覆盖并 finally 恢复；刷新原配置 callback，不写 readonly StateFlow／MutableState或固定磁贴列数 |
| 状态栏位置／比例 | [StatusBarOffset](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StatusBarOffset.java>)仅平移可见内容，不改高度／刘海。[StatusIconScale](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StatusIconScale.java>)采样 bounds／matrix／padding／native scale，只在 draw 临时缩放并 finally 恢复；网络、电池、时钟不进系统槽位白名单 |
| 信号 | [SignalDisplay](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/SignalDisplay.java>)保真实订阅、ServiceState／NR／NTN／primary及 Drawable 身份。synthetic emitter 路径或[MobileSignalBinder](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/MobileSignalBinder.java>)局部接入共用展示规则；[StackedSignalTargets](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalTargets.java>)校验不同 Compose 签名，[StackedSignalDisplay](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/StackedSignalDisplay.java>)只传展示副本，不改共享原生 model／Flow。模块 revision state仅触发重组 |

## 授权与失败

[NavbarProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarProvider.java>)只向自身、实际 SystemUI及已知系统实际 HOME／本用户 UID 返回只读开关；无普通 app 或 server 导航 Hook。配置线程异步读取，main 发布／重排。读取失败关闭覆盖、重建原生并延迟重试。

[ShadeProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeProvider.java>)只给自身／SystemUI完整快照；已认证 Launcher仅获抽屉布尔投影，无远程写入口。双栏／偏移／信号可保最近完整 HostSnapshot并重试，不与导航 fail-close混称。双栏刷新部分失败关闭覆盖并尽力恢复 panel／Compose callback，不保证任意异常零残留。

信号目标必须预解析；安装失败回滚本组，替代 binder 后端也须局部回滚，不能留半组 Hook。弱 Drawable 与订阅观察各有同步，重绘 main执行；关闭恢复记录的原生状态。现有检查不替代真实订阅、当前 UI owner、IME／旋转和关闭恢复的设备验证。
