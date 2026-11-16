# Demo8.2 不可变回归基线

原始源码是当前小窗实现的来源基线，不是待清理的实验 Demo。

- [源码归档](<sunwindow-demo8.2-source.zip>)保持原样；其中包含原始代码、许可与来源声明。
- [运行文件摘要](<runtime-sha256.json>)用于校验归档内容；当前差异及原因单独登记在[变更清单](<../tests/runtime-changes.json>)。
- [目标签名](<target-signatures.json>)与 [UI 签名](<ui-signatures.json>)是固定证据，不代表当前所有宿主兼容。
- [回归入口](<../tests/verify_runtime.py>)校验原始摘要、差异登记并运行 Android 无关主机检查。不通过改基线摘要或删除检查隐藏变化。

旧基线 APK 不再随源码分发。原 APK SHA-256 为 `0968cffe5049f10882aee396054221a5a0b6a9ac747a695c7aa9af272c4c05e9`，版本 `0.8.2-test1`／code12／协议14；这些是历史身份，不是当前产品版本。签名证书 SHA-256 为 `28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104`，不含私钥。

主机检查只能提供回归证据，不代替实际设备的输入、动画、锁屏和恢复验证。
