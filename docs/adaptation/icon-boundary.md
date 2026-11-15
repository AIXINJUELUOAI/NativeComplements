# 图标：规则输入与 Android 执行边界

## 真实入口

- [IconRenderPolicy](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconRenderPolicy.java) 是无 Android 依赖的值规则：沿用原 mode 编号，接收彩色／莫奈／通知选择、库是否匹配、生成前后景是否齐全，输出素材来源、两路 mask 选择和是否需要解析。`themed(userEnabled, systemThemed)` 共用于抽屉、遮罩和小窗；三者仍独立开关。
- [IconPolicy](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconPolicy.java) **不是纯规则层**：它继续承接既有 JSON、偏好迁移／批量保存与素材剪枝，公开常量沿用值规则编号。未增加另一套配置或持久化协议。
- [IconAssets](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconAssets.java) 的 `resolve` 在原 PackageManager 原图查询后使用 plan；UI 预览与宿主 worker 共用此入口。手动双层、匹配库、已生成双层互斥；缺文件或缺层保持原图，不自动改走算法。mask 的跟随决策仍以真实素材解析后的 `nativeColor` 为准，原平面图不伪造独立 themed 槽。

## 宿主 ABI 与资源

[IconLayerModule](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/IconLayerModule.java) 保留 Launcher `IconProvider`、`LauncherActivityInfo`、`FloatingIconView`、模型缓存刷新 ABI；同用户检查先于替换，原生 themed 动画不被彩色替换。[DrawerMonet](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/DrawerMonet.java) 保留 drawer／prediction 与 search 区分、原 `BitmapInfo`、CacheLookupFlag 的 8 位及绑定 ThreadLocal 的 finally 恢复；关闭重绑，不全局开启主题、不写共享 BitmapInfo。

[SystemIconModule](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/SystemIconModule.java) 保留同用户非会话通知、原生画布缩放和 splash 类型／用户判断；临时替换 splash 属性后无论成功失败均 finally 恢复。Drawable、Bitmap、Surface 之外的 Hook 对象身份、URI、Provider UID 权限与所有反射成员名仍归 Android 适配侧，本次不改 Provider。

## 失败、恢复与上限

- [LauncherThemeReporter](../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java) 独立观察 ThemeManager，读取失败回 false；IPC 成功才去重。连接／上报失败不会主动抹去 Provider 之前的 systemThemed 快照，不能称全故障实时 fail-closed。
- 配置刷新失败保留已发布 bitmap 快照；旧 worker 被新 epoch 取消后不发布。素材私有缓存及原 AtomicFile、库 generation 发布／ack 剪枝继续留平台侧。
- 遮罩／小窗素材顺序保持模块 mono → 原生 monochrome → worker 转换缓存 → 原图。仅转换缓存使用 128px／32MiB；桌面素材快照 96MiB、私有 URI 字节缓存 64MiB 边界不变。绘制路径不执行 Binder、磁盘或解码。

## 最小证据

复用 [IconAssetsTest](../../app/src/test/java/dev/local/sunwindowdemo/features/iconlayers/IconAssetsTest.java) 的失败快照、过期取消、素材缓存检查，仅补一组原模式路由等价与主题 gate 断言；像素、抽屉 finally、Reporter 和 ABI 检查沿用已有验证。本轮变更等待集成方必要编译／检查，不沿用旧版本通过结果，不声明其他 Launcher／ROM ABI 或真机效果兼容。
