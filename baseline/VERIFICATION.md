# Demo8.2 验证记录

APK SHA256：`0968cffe5049f10882aee396054221a5a0b6a9ac747a695c7aa9af272c4c05e9`。versionCode12，协议14。
证书SHA256：`28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104`，沿用原签名。

## 实际执行，最终结果exit0

- `python work/sunwindow-demo82-src/tests/run_host.py --out work/sunwindow-demo82-host`：7组PASS。几何检查增加了50/90/100百分比边界、1–6个图标全部可选、动画/适配半径、过早选中拒绝、向外和过冲取消及无效输入检查。
- `python work/sunwindow-demo82-src/build.py --api work/demo-deps/libxposed-api.jar --out work/sunwindow-demo82-final --keystore work/sunwindow-final-build/demo.keystore`：Java/D8/aapt2/zipalign和v3签名通过，使用获准的本机SDK/JDK读取权限。
- `python work/sunwindow-demo82-src/tests/check_apk.py work/sunwindow-demo82-final/sunwindow-0.8.2-test1.apk`：CRC、193项DEX定义、三个作用域及入口、许可证和NOTICE、两个新设置键通过。无框架/API jar或私钥。
- aapt2核对0.8.2-test1、versionCode12、minSdk34、targetSdk37和Demo8.2标签；apksigner verify --verbose --print-certs确认签名和证书。
- 相对Demo8.1，除6个设置/菜单相关Java文件外，另22个Java文件在归一化版本标签后完全一致。没有新增Android或Hook接口，沿用8.1成员声明报告，没有重新执行设备Hook验证。
- 原Demo8.1 APK摘要仍为`2caf12dd093276bc0704ed011b8c578796ada08318d76335cb9230c6a5a9bba4`；源码和APK保持原样。
- 交付后重新读取APK、源码ZIP及源码文件，核对CRC、NOTICE和SHA256。

提示开关只控制可见提示窗口。阈值反馈在提示显示判断之前处理，通过已有附着视图保留震动；偏好变化会移除已显示的提示。选中判定和图标绘制共用drawnRadius，默认由旧50%提高为90%。

本轮未安装、重启或操作手机；主机检查不代替手指操作和震动验收。CHANGES.diff以Demo8.1为基线，SOURCE_SHA256.json记录交付摘要。
