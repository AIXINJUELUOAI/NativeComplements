# Alive ambient 绑定局部例外

## 授权与两个原生组件

这是用户明确授权的**签名权限检查局部例外**，不是普通应用合法取得 `AMBIENT_WALLPAPER`，也不能描述为“未绕权限”。PackageManager 权限数据库、Binder 调用身份、其他应用或权限的检查均不修改。

- 普通 Alive 壁纸组件保持原 `supportsAmbientMode=false` 元数据，提供不需要此例外的完整普通壁纸回退。
- `dev.nativecomplements.module/dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService` 是独立 ambient 组件；用户在系统壁纸选择流程明确选用它才表示 opt-in。真实元数据为 `supportsAmbientMode=true`，复用同一个照片渲染内核，不创建第二套渲染器。
- 回退是用户通过原生壁纸选择流程重新选普通组件，不是隐藏开关或全局权限状态。撤销 Hook 不会让已连接的 ambient 引擎立即退出，不能以撤 Hook 代替系统重新绑定/切换壁纸。

## 平台输入 → 局部规则 → 原生执行

[AliveAmbientAccess](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAmbientAccess.java>)只在 system_server 现有模块入口安装两个精确 Hook：

1. `WallpaperManagerService.bindWallpaperDescriptionLocked(WallpaperDescription, boolean, boolean, WallpaperData, IRemoteCallback): boolean` 建立本线程同步作用域。从原生 description 读取精确组件、从 WallpaperData 读取真实 userId，禁止用已清除的 Binder 调用身份猜用户。每次绑定（包括其他组件和重入）建立独立作用域，`finally` 恢复外层或移除，异步/Binder 跨线程不继承。
2. `WallpaperInfo.supportsAmbientMode(): boolean` 先执行原生 getter，仅当当前绑定组件、info 组件均是上述完整组件、userId 非负、原生结果为 true，且本次作用域尚未消费例外时，返回一次 false，跳过绑定路径内的专用 ambient 检查。其他 getter 调用，包括正常元数据保存与后续 engine IPC，返回原生结果。

本地 current [services.jar](<../../verification/navbar-system/services.jar>) SHA-256 为 `3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9`：该 bind 方法只有一次直接 `supportsAmbientMode` 调用（DEX code-unit 404），随后通过 `mIPackageManager.checkPermission(String,String,int)` 检查 ambient（418），拒绝后除 watch 特例外原生返回 false/抛 SecurityException。这里改变的就是这一个拒绝分支的前置条件；不 Hook 全局 PackageManager、权限服务或包信息。其余服务权限、解析、用户、绑定与分发验证仍由完整原方法执行。

## 预检、回退与未验证范围

安装前精确校验类、方法签名、返回类型及 userId 字段类型；Hook 初始保持未就绪状态，必须 bind 调用方 `deoptimize` 成功才激活，避免 ART 内联 getter 导致局部例外未命中。失败逆序撤销已安装 Hook，保留原拒绝行为且不抛至启动路径。运行期反射异常返回原生结果，不伪造允许。没有额外 Provider、配置同步、PM Proxy 或单实现权限桥。

该例外只解决自有 ambient 组件在当前平台上的绑定门槛，不解决 SystemUI 壁纸可见性、light-reveal 能力流、屏幕黑屏策略、接近传感器或烧屏保护。不得为了效果绕过设备认证、关闭系统暂停/黑屏或强制常亮。新组件与 Hook 尚需真实设备验证：安装/启动失败回退、其他壁纸无影响、组件切换和用户隔离、DOZE/锁屏/解锁及原生暂停。框架成员存在和编译通过不能当作 AOD 已可用或烧屏安全证据。
