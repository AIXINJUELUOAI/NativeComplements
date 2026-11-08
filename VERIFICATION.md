# 第一版验证记录

交付版本：0.1.0-preview1 / versionCode 13 / 协议 14。

## 本轮完成

- `python tests/verify_runtime.py`：26 个运行层 Java 文件与原始 Demo8.2 源码 ZIP 逐字节一致；原有七组 JVM 规则检查全部通过。
- `gradlew.bat --no-daemon --console=plain :app:assembleRelease :app:testDebugUnitTest`：构建、release lint 和 5 项管理界面/配置/导航检查通过。
- `python tests/package_release.py`：APK CRC、DEX 定义、三个作用域、三个 Hook 入口、包名、版本号、minSdk34/targetSdk37、许可证、compileOnly API 未打包及无私钥检查通过。
- apksigner 校验通过，证书 SHA256 为 `28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104`，与 Demo8.2 一致。
- 保留原偏好文件、键、默认值与应用顺序；未引入全局配置迁移或改变通信协议。

## 界面检查

`verification/screenshots` 保存 Robolectric API35 离屏渲染，包括首页、小窗设置和深色手势页。该渲染用于检查排版与控件，不是目标 PixelOS 手机截图。真实 Activity 导航测试覆盖首页进入小窗、进入手势页、逐层返回，以及首页状态入口返回原页面。

## 保留的适配例外

Release lint 对旧 LauncherGuard 的 InputEvent.getDisplayId 反射报告 BlockedPrivateApi。app/lint.xml 仅对该原有文件豁免该项；运行代码、目标声明预检和其他 lint 检查保留。它在目标桌面 Hook 进程运行，不宣称普通应用或其他 ROM 也可直接使用。

## 验证边界

本轮没有安装、启用、重启或操作手机。源码一致和主机规则通过，证明迁移未修改原有窗口算法与 Hook 源码，不等于对 ART Hook 时序、真实触摸、应用兼容、动画帧率、系统恢复已完成新版本真机验收。单活动窗口、180 秒期限和旧版已知问题保留。

## 现场验收顺序

1. 旧版先恢复窗口，再覆盖安装并重启；检查三个作用域、原设置和无障碍连接。
2. 左右角落菜单、五个快捷应用排序、固定列表入口、侧边返回长滑。
3. 普通/迷你/全屏、白条缩放、拖动关闭和空白处关闭方式。
4. 横屏、截图工具栏、应用切换、历史任务隐藏及恢复入口。

原始 APK 与源码保存在 baseline；低版本 APK 不作为已验证可覆盖回退的安装包。停用与回退说明见 README。
