# Alive ambient 绑定局部例外

## 组件与权限边界

[AliveAmbientAccess](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientAccess.java>)是用户明确授权的**签名权限检查局部例外**，不是普通应用获得 `AMBIENT_WALLPAPER`，不能描述为“未绕权限”。不修改 PackageManager 权限数据库、Binder 身份、其他应用或其他权限检查。

- [普通组件](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)保持 `supportsAmbientMode=false`，提供普通壁纸回退。
- [ambient 组件](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientWallpaperService.java>)完整名称为 `dev.nativecomplements.module/dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService`，真实元数据 `supportsAmbientMode=true`，复用同一 Engine 与渲染内核。
- 用户通过原生壁纸选择流程应用该组件；系统 AOD 设置仍决定 ambient 运行资格。撤回选择须切回普通 Alive 或其他壁纸。撤销 Hook 不会立即断开已连接 Engine，不能替代系统切换/重绑。
- LineageOS 的 WallpaperPicker2/ThemePicker 没有导出 `ACTION_CHANGE_LIVE_WALLPAPER` 预览 Activity；[AliveWallpaperAccess](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperAccess.java>)在 system_server 注册仅本模块可发送的广播桥，[AliveWallpaperPicker](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperPicker.java>)只在桥精确接受后发布草稿。组件、用户、UID 或 destination 任一不符即拒绝；桥不可用退回系统 `ACTION_SET_WALLPAPER`。

## 精确接入

仅在现有 system_server 模块入口安装两处 Hook：

1. `WallpaperManagerService.bindWallpaperDescriptionLocked(WallpaperDescription, boolean, boolean, WallpaperData, IRemoteCallback): boolean` 建立本线程同步作用域。组件取自原生 description，用户取自 `WallpaperData.userId`，不以清除后的 Binder 身份猜用户。每次绑定含其他组件/重入都新建作用域，`finally` 恢复外层或移除；不跨线程继承。
2. `WallpaperInfo.supportsAmbientMode(): boolean` 先运行原 getter。仅本次绑定与 info 均精确匹配 ambient 组件、用户非负、原结果 true、作用域未消费时，返回一次 false，跳过绑定内专用 ambient 权限检查的前置条件。其他 metadata 保存及后续 engine IPC 返回真实结果。

其他服务权限、解析、用户、绑定和分发检查仍由完整原方法执行；不 Hook 全局 PackageManager、权限服务或包信息。

## 安装与失败恢复

预检类、完整方法签名、返回类型及 userId 字段类型；初始未就绪，绑定调用方 deoptimize 成功后才激活，避免内联 getter 漏接。失败逆序撤销本组 Hook并保原拒绝，不抛到系统启动路径。运行时反射异常保原结果，不伪造允许；不新增 Provider、权限代理或全局开关。

该例外仅解决精确自有组件的绑定门槛；不解决 SystemUI 能力、壁纸可见性或显示器策略，这些边界见[系统交接](<alive-systemui.md>)。不改认证、UDFPS、输入、电源、亮度、接近传感器或原生暂停，不强制常亮。成员存在/编译通过不能证明所有宿主可用或烧屏安全。
