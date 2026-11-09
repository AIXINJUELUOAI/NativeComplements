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
