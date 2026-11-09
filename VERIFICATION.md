# 0.4.1 验证记录

版本 `0.4.1`，versionCode 41。公共修复覆盖双窗并存和独立恢复、入口强制重建移除与定尺寸淡入、受管任务的非分屏客户端配置、IME 坐标映射、mini 菜单缩放、建议启动回执及左下/右下位置设置。

- Release Java/Kotlin 编译通过。`LinkedWindowsTest` 的 8 项针对性检查通过，覆盖独立窗口记录、任务与 token 控制校验、键盘重复避让和失败回执。
- `python tests/verify_runtime.py` 和 `python tests/verify_target.py` 通过；新 IME 私有接口依据既有 API37 设备归档核对，记录在 `tests/target/viewport-members.json`。
- 现场日志已确认：游戏在入口额外 relaunch 后收到 SIGKILL，但发送者尚未确认；建议目标被 BAL 拦截；QQ 的物理键盘顶边与呈现底边已经相接，额外内留白指向逻辑 Insets 坐标不一致。
- 本轮没有安装、重启或更改 LSPosed。游戏存活、流媒体横屏、输入法视觉布局、双窗转场和建议跳转仍需用户安装后验证；主机检查不代表这些真机效果已经通过。

APK：`deliverables/NativeComplements-0.4.1.apk`

# 0.4.0-preview1 验证记录

当前版本：`0.4.0-preview1`，versionCode 40；原覆盖签名、包名、宿主协议16与三个系统作用域保持不变。

- 本轮完成菜单滚动结构、截图保留、主题三点按钮、手机齿轮图标与独立建议模块。新增本次常亮、一次性按钮行为和系统轻震；SIM 拦截逻辑未修改。
- `:app:testReleaseUnitTest --tests dev.local.sunwindowdemo.features.suggestions.SuggestionRulesTest` 通过：4 项检查覆盖前导零／全角数字、长号码排除、中文分享标点、主域与伪域区分、保留真实分享 URL／口令、宽松地址识别与输入长度边界。检查发现并修复了中文逗号后的分享文案被吞进 URL 的问题。
- `python tests/verify_runtime.py` 通过：不可变 Demo8.2 基线、几何／手势／状态／历史、旋转、mini 磁吸、边缘区域与遮罩检查。`python tests/verify_target.py` 通过原有归档接口核对。
- 新增输入、PowerGroup DIM/TOUCH 与通知回调依据现有设备归档核对，签名与归档 SHA256 记录在 `tests/target/suggestion-members.json`；归档 SystemUI 声明了后台剪贴板、相机与 WAKE_LOCK 权限。新增私有接口在运行时独立预检，缺失时只关闭相关能力。
- `python tests/package_release.py` 通过：Release 编译、lintVital、APK CRC、版本／包名／原证书／静态作用域与四个既有入口检查。未新增模块入口或普通应用作用域。
- 本轮没有安装 APK、重启设备或改动 LSPosed。交付时 ADB 无设备；短信接收权限、逐位输入、蓝牙／手电筒、常亮结束、链接深层跳转和菜单截图层级均仍需真机验收。构建和归档签名核对不代替真机效果。

APK：`deliverables/NativeComplements-0.4.0-preview1.apk`

SHA256：`03141bdd8a1412b780152401ea2d26eaa446d4f6d0d67c21919312394e7e942e`

# 0.3.0-preview1 验证记录

当前版本：`0.3.0-preview1`，versionCode 34，宿主协议16，沿用原覆盖升级签名与三个系统作用域。

- Release 构建与 lintVital 通过；打包脚本核对包名、版本、原签名、三个作用域和四个模块入口。
- 小窗不可变 Demo8.2 基线、既有主机检查、旋转位置映射、贴边隐藏边界及稳态 mini 转场条件检查通过。
- 电话纯规则检查通过：SIM 独立/跟随、通配符、国际前缀格式、空号全拦及未知卡槽放行。
- 电话设置页主机集成检查通过：独立入口、开关、SIM2 跟随开关保留独立设置、返回首页。
- 只读核对连接设备的 TelecomShim / Telecom 服务与 `service-telecom.jar`，确认运行于 system_server；本轮使用的来电、账户与原生过滤结果接口已依据该文件反编译核实。
- 新版未安装到设备，也未进行真实来电或小窗视觉验收。本轮编译、规则与主机界面结果不代替真机反馈。

# preview20 验证记录

当前版本：`0.2.0-preview20`，versionCode 33，宿主协议16。

- Release 构建及必需的 lintVital 已通过；包名、版本、三个系统作用域与原覆盖升级证书已核对。
- preview20 针对性 JVM 检查复现了原生非 focusable Activity 被暂停的路径，并检查实际 mini 生命周期资格方法及20项原生行为/排除条件。
- preview20 已交付用户，用户反馈“非常好”。该反馈不代替所有应用、系统版本与边界场景的完整验收。
- 本次 Git 整理保留小窗运行代码与原签名，清除旧生成物，更新文档与打包工具。
- Demo8.2 原始基线仍位于 baseline/；当前运行修改原因位于 tests/runtime-changes.json。

preview20 APK SHA256：`566d19a5f9591bab1ce8c5362bd9ad8a2a765fd732e9072102c0f14dfda23871`

覆盖升级证书 SHA256：`28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104`

主机检查、归档接口签名检查与设备效果是不同证据。后续功能需分别记录实际执行和用户反馈；不从历史版本的日志推断新版本已在设备通过。
