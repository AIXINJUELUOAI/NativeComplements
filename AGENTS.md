# NativeComplements

- 默认简体中文。原生 Android 设置风格，Compose MD3/M3E、系统动态颜色与深色模式。
- 首页按功能独立跳转；功能做到可用后再加入入口。当前只有小窗，不创建其他功能的占位页面或接口。
- 单 app Gradle 模块。新管理界面位于 home、navigation、core/ui 和 features/floatingwindow/ui。
- 第一版行为基线是 Demo8.2。26 个 Java 运行层文件目前原样保留于 features/floatingwindow/legacy，包名保持 dev.local.sunwindowdemo。未来修复小窗时明确记录修改原因，不把基线摘要同步改掉来隐藏差异。
- applicationId、三个入口/作用域、签名权限、协议14、corner 偏好键和无障碍组件身份是覆盖升级兼容点。
- .local/demo.keystore 是现有测试签名，仅本地保存。不要提交到 Git 或放入交付源码 ZIP。
- JDK17+，本机使用 Android Studio JBR；SDK android-37.0，Gradle8.14.4。
- 构建：gradlew.bat --no-daemon --console=plain :app:assembleRelease :app:testDebugUnitTest。
- 基线检查：python tests/verify_runtime.py。最终打包：python tests/package_release.py。
- 主机规则、离屏渲染和真实 LSPosed 设备验证分别记录；不得将构建成功表述为真机效果保证。
- 首版保留单活动小窗和180秒期限。统一远程配置、启动健康计数和新的动态Hook启停尚未实现。
