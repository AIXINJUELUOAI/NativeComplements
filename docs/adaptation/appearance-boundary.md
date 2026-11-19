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

## 桌面双击息屏

[DesktopDoubleTap](<../../app/src/main/java/dev/local/sunwindowdemo/features/desktop/DesktopDoubleTap.java>)旁路观察 Workspace 空白网格触摸，不消费事件或替换原生监听器。开关默认关闭且独立于小窗；仅正常、无浮层、无拖动／页面切换的默认显示器桌面，同页单指两次短按且第二次松手后才请求。图标、小组件、底栏、长按、多指和滑动不参与。

[DesktopSleepHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/desktop/DesktopSleepHost.java>)在 SystemUI 异步读取配置并向已验证系统 HOME 发布窄 Binder。接收及执行时核实际 HOME UID、事件时效、显示器、前台组件、亮屏与未锁定状态；普通 `PowerManager.goToSleep` 电源键路径，不使用设备管理器强制锁定或改变强认证策略。读取／反射／权限失败保持原生；不增加作用域或 Provider 读者。

## 游戏角落防误触

[GameModeStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/gamemode/GameModeStore.java>)独立默认关闭；仅游戏模式已启用、选中应用实际前台全屏且亮屏解锁时生效，不依赖游戏助手面板开关。[GameCornerGeometry](<../../app/src/main/java/dev/local/sunwindowdemo/features/gamemode/GameCornerGeometry.java>)定义屏幕左下、右下两个四分之一圆，半径为短边的 4～24%，默认 10%，连续浮点调节并兼容旧整数配置。[详情页](<../../app/src/main/java/dev/local/sunwindowdemo/features/gamemode/GameCornerScreen.kt>)不改变页面方向，只将示例按横向屏幕比例绘制，调节效果实时显示在示例上，预览和命中共用几何，仅在预览内模拟多指，不屏蔽设置控件。

[GameModeHost](<../../app/src/main/java/dev/local/sunwindowdemo/features/gamemode/GameModeHost.kt>)只以实时配置授权；读取失败立即撤销角落资格并重试。使用独立、只接受真实 SystemUI UID 的三秒租约广播，不覆盖原边缘矩形。[EdgeInputFilter](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeInputFilter.java>)复用唯一系统输入过滤链路与原生无障碍过滤；[GameCornerAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/GameCornerAccess.java>)在新触点落下时再核默认显示器实时尺寸、当前 WMS 焦点窗口、全屏 Task、键盘不可见、真实包 UID、同用户及亮屏解锁，系统面板获得焦点后不新增角落屏蔽，反射失败仅关闭游戏角落判定。

每个手指只在自身 DOWN 判定：内起点屏蔽至抬起，外起点进入角落仍放行；半径／资格／租约变化只影响新触点。混合输入用原生 `MotionEvent.split` 保留有效 pointer ID、action 与历史；首个有效手指晚于被拒手指时修正整段可见流的 downTime。系统浮层／键盘／截图／展开助手暂停新角落屏蔽，模块保护触区保持可用。不用外接显示器、鼠标或普通应用 Hook 扩大范围；主机检查不能代替设备游戏多指、系统面板及旋转验收。

## 授权与失败

[NavbarProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/navbar/NavbarProvider.java>)只向自身、实际 SystemUI及已知系统实际 HOME／本用户 UID 返回只读开关；无普通 app 或 server 导航 Hook。配置线程异步读取，main 发布／重排。读取失败关闭覆盖、重建原生并延迟重试。

[ShadeProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeProvider.java>)只给自身／SystemUI完整快照；已认证 Launcher仅获抽屉布尔投影，无远程写入口。双栏／偏移／信号可保最近完整 HostSnapshot并重试，不与导航 fail-close混称。双栏刷新部分失败关闭覆盖并尽力恢复 panel／Compose callback，不保证任意异常零残留。

信号目标必须预解析；安装失败回滚本组，替代 binder 后端也须局部回滚，不能留半组 Hook。弱 Drawable 与订阅观察各有同步，重绘 main执行；关闭恢复记录的原生状态。现有检查不替代真实订阅、当前 UI owner、IME／旋转和关闭恢复的设备验证。
