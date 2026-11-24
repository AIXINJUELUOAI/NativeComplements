# 图标接入边界

## 素材与规则

[IconRenderPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconRenderPolicy.java>)沿用已有 mode 编号，按彩色／莫奈／通知选择、匹配库及生成前后景完整性输出素材来源和 mask 计划。[IconPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconPolicy.java>)继续承载 JSON、偏好迁移、批量保存和剪枝，不是纯规则类。

[IconAssets](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java>)在原 PackageManager 查询后解析计划，UI 预览与宿主 worker共用入口。手动双层、匹配库、已生成双层互斥；缺文件／层保原图，不静默换算法。mask跟随真实解析结果，不把平面图伪造成独立 themed 槽。

“外观与控制”提供抽屉与遮罩莫奈独立开关，均受原生系统主题状态门控；遮罩覆盖系统启动图标与受管小窗转场图标，素材不足保留彩色／原图，缓存只在 worker 构建。图标适配页逐应用“莫奈图标”及“状态栏通知小图标”模式不受影响。小窗菜单／扇区莫奈仍退役，[ShadeStore](<../../app/src/main/java/dev/local/sunwindowdemo/features/shade/ShadeStore.java>)恒关并忽略旧值，不能因共用素材方法或旧字段仍存在而宣传为当前功能。批量设置从图标页菜单选择应用，再独立选择各路模式，未选路保持原值，一次发布。

## 宿主与 ABI

[IconLayerModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java>)仅匹配已知 Launcher 主进程，保留 IconProvider／LauncherActivityInfo、FloatingIconView及模型缓存刷新链。同用户校验先于替换，原生 themed 动画不被彩色素材覆盖。[LauncherIconAccess](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherIconAccess.java>)只解析 Kotlin 字段／getter、缓存移除参数次序等真实差异，不改变缓存或渲染策略。

[DrawerMonet](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/DrawerMonet.java>)限定 AllApps 网格／预测，区分 search／workspace。查询补 CacheLookupFlag 的 bit 8，缺 themed 位的已绑定 AppInfo在后台升级一次；仅绑定 ThreadLocal 范围请求原生主题绘制，finally 恢复。不全局开主题、不写共享 BitmapInfo；关闭重绑原生。系统主题由[LauncherThemeReporter](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java>)观察 ThemeManager上报，不自动切换 Minimal 或修改用户桌面偏好。

系统配色资源确认变化后，实际 HOME 在桌面主题图标开启时调用 Launcher 原生模型重绑，并刷新已绑定的抽屉图标。单色素材和持久化图标缓存继续沿用。管理界面也在配色资源更新或返回前台时重新读取系统配色。

[SystemIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java>)只在 SystemUI处理同用户非会话通知标识图标及合格 splash。标准通知卡片的应用标识复用已适配的彩色图标；状态栏／shelf／AOD 小图标保持原有独立通知策略，不随导入自动启用。不处理会话头像、通知附图或裸自定义 RemoteViews。卡片绘制仅针对已确认身份的 header 图标，精确接受 CachingIconView 及新版 NotificationRowIconView；字段声明为父类不代表实例必为父类。预检已知继承关系及两层无 onDraw 覆盖，未知子类保原生。临时素材必须 finally 恢复，不改原 Notification、原生 app icon/provider 缓存。splash 临时属性也须 finally 恢复；素材缺失保原生，不在 build／draw 热路径查包、解码或 Binder。模型缓存更新在原 worker、可见 View 刷新在 main；热恢复失败不保证无需重启桌面。

[SettingsIconModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SettingsIconModule.java>)仅接入 `com.android.settings` 主进程，在 ApplicationPackageManager 原始应用取图层复用同一彩色快照，后续阴影与用户徽标仍由原生生成。只处理本用户、PM Context 与显式 factory user 一致的 ApplicationInfo，用户头像与其他用户保原生。素材 worker 的原图读取有 ThreadLocal 旁路，避免上次替换反馈入解析。新增作用域旧安装须自行补勾。原生缓存按本用户失效，不强制重启／重绑页面，导入或关闭后离开并重新进入详情查看；无桌面入口的包不在当前素材目标集合内。详情取图链参考 AOSP Android 16 的 [EntityHeaderController](https://android.googlesource.com/platform/packages/apps/Settings/+/android-16.0.0_r1/src/com/android/settings/widget/EntityHeaderController.java) 与 [SettingsLib.Utils](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/packages/SettingsLib/src/com/android/settingslib/Utils.java)，不能代替目标 Settings APK 的页面及可选缓存 ABI 验证。

## 授权、缓存与失败

- [IconLayerProvider](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerProvider.java>)按自身、实际 SystemUI、system、已知系统实际 HOME／本用户 UID，以及同用户真实系统 Settings UID 分权；Settings 仅增加读取，不扩大 `report`／`started` 权限；素材读取只给已授权消费者，`report`限 Launcher、`started`限 system。它的授权不能和 ShadeProvider的“只给 Launcher一个抽屉布尔”混为一谈。
- 配置刷新失败保已发布 bitmap快照，新 epoch取消旧 worker发布。素材 generation／ack、AtomicFile及剪枝留平台侧，不把未完成导入当 ready。
- 系统主题读取失败返回 false；报告只有 IPC成功才去重，上报失败重试但不主动抹去 Provider旧快照，不承诺全故障实时 fail-close。
- 绘制只读宿主快照；桌面素材快照 96MiB、私有 URI字节缓存 64MiB。旧表面转换缓存保留 128px／32MiB边界，不表示退役入口重新可用。

规则、缓存、原生绑定及 ABI定向检查只覆盖各自层次，不证明任意 Launcher或 ROM设备效果；预检／安装失败按局部链回原生，不通过放松 HOME／UID检查绕过接入问题。
