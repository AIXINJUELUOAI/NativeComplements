# 图标接入边界

## 素材与规则

[IconRenderPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconRenderPolicy.java>)沿用已有 mode 编号，按彩色／莫奈／通知选择、匹配库及生成前后景完整性输出素材来源和 mask 计划。[IconPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconPolicy.java>)继续承载 JSON、偏好迁移、批量保存和剪枝，不是纯规则类。

[IconAssets](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java>)在原 PackageManager 查询后解析计划，UI 预览与宿主 worker共用入口。手动双层、匹配库、已生成双层互斥；缺文件／层保原图，不静默换算法。mask跟随真实解析结果，不把平面图伪造成独立 themed 槽。

“外观与控制”中的莫奈开关仅保留抽屉，受原生系统主题状态门控；图标适配页逐应用“莫奈图标”及“状态栏通知小图标”模式不受退役影响。窗口／遮罩莫奈已退役，[ShadeStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeStore.java>)恒关并忽略旧值，不能因共用素材方法或旧字段仍存在而宣传为当前功能。批量设置从图标页菜单选择应用，再独立选择各路模式，未选路保持原值，一次发布。

## 宿主与 ABI

[IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>)仅匹配已知 Launcher 主进程，保留 IconProvider／LauncherActivityInfo、FloatingIconView及模型缓存刷新链。同用户校验先于替换，原生 themed 动画不被彩色素材覆盖。[LauncherIconAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherIconAccess.java>)只解析 Kotlin 字段／getter、缓存移除参数次序等真实差异，不改变缓存或渲染策略。

[DrawerMonet](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/DrawerMonet.java>)限定 AllApps 网格／预测，区分 search／workspace。查询补 CacheLookupFlag 的 bit 8，缺 themed 位的已绑定 AppInfo在后台升级一次；仅绑定 ThreadLocal 范围请求原生主题绘制，finally 恢复。不全局开主题、不写共享 BitmapInfo；关闭重绑原生。系统主题由[LauncherThemeReporter](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java>)观察 ThemeManager上报，不自动切换 Minimal 或修改用户桌面偏好。

[SystemIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java>)只在 SystemUI处理同用户非会话通知标识图标及合格 splash。splash 临时属性必须 finally恢复；素材缺失保原生，不在 build／draw热路径查包、解码或 Binder。模型缓存更新在原 worker、可见 View刷新在 main；热恢复失败不保证无需重启桌面。

## 授权、缓存与失败

- [IconLayerProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerProvider.java>)按自身、实际 SystemUI、system、已知系统实际 HOME／本用户 UID分权；素材读取只给已授权消费者，`report`限 Launcher、`started`限 system。它的授权不能和 ShadeProvider的“只给 Launcher一个抽屉布尔”混为一谈。
- 配置刷新失败保已发布 bitmap快照，新 epoch取消旧 worker发布。素材 generation／ack、AtomicFile及剪枝留平台侧，不把未完成导入当 ready。
- 系统主题读取失败返回 false；报告只有 IPC成功才去重，上报失败重试但不主动抹去 Provider旧快照，不承诺全故障实时 fail-close。
- 绘制只读宿主快照；桌面素材快照 96MiB、私有 URI字节缓存 64MiB。旧表面转换缓存保留 128px／32MiB边界，不表示退役入口重新可用。

规则、缓存、原生绑定及 ABI定向检查只覆盖各自层次，不证明任意 Launcher或 ROM设备效果；预检／安装失败按局部链回原生，不通过放松 HOME／UID检查绕过接入问题。
