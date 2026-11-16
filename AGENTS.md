# NativeComplements

## 产品与代码边界

- 默认简体中文。管理界面使用 Compose Material 3、系统动态颜色与深色模式；共用 SettingsPage 标题栏和安全区，不创建占位功能或重复 Scaffold。
- 仅维护 `app` 模块的 `full` 产品。主导航在 [ModuleApp](<app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>)；开发者工具从“关于”连续点击版本号七次后显示。
- 应用 ID `dev.nativecomplements.module`、源码包 `dev.local.sunwindowdemo`、升级签名、签名权限及已有配置键是兼容点，不随意修改。当前宿主协议为 16。
- 静态作用域为 `system`、`com.android.systemui`、`com.google.android.apps.nexuslauncher`、`com.android.launcher3`。桌面只信任已知系统实际 HOME 及本用户真实 UID；包存在或 ROM 名称不授予权限。
- 宿主接入遵循“平台事件输入 → 共用规则／状态 → 平台执行与恢复”。相同 Hook 共用，真正不同的 ABI／安装时机放入对应功能的局部 Access／Targets，不复制整套功能或创建通用 ROM 插件平台。
- 保留 UID、user、display、锁屏、认证、nonce、租约、任务所有权及失败恢复边界。Hook 预检／安装失败保持原生，未知不冒充不支持；安装成功不是设备效果验收。
- 社区署名仅用于首次一次维护者提示及关于页；无已确认维护者反馈地址时回主项目。不增加逐功能“未适配／未验证”提示，不要求贡献者补测试模板或报告。见[社区协作](<docs/community-adaptation-plan.md>)。
- 当前运行合同见[接入索引](<docs/adaptation/README.md>)。旧独立小窗产品已退役，但旧身份、防双接管和恢复迁回兼容不可随资料清理删除；不恢复长侧滑、无障碍按钮或已退役的窗口／遮罩莫奈入口。

## 构建与检查

- JDK 17+、Android SDK `android-37.0`、Gradle Wrapper 8.14.4。机器路径通过 `JAVA_HOME`、`ANDROID_HOME` 等环境变量配置，不提交本机配置。
- 必要定向：`gradlew.bat --no-daemon --console=plain :app:testFullDebugUnitTest --tests ...`。按风险选择现有检查，不因小改动反复全量回归或堆测试套件。
- Release：`gradlew.bat --no-daemon --console=plain :app:assembleFullRelease`；正式签名产物用 [package_release.py](<tests/package_release.py>) 校验身份、7 入口、4 作用域、R8、Direct Boot 及退役边界。本地测试签名不能声称可覆盖正式安装。
- [verify_runtime.py](<tests/verify_runtime.py>)读取不可变 Demo8.2 源码基线并运行主机检查；修改小窗运行文件需更新[差异原因](<tests/runtime-changes.json>)，不改[基线摘要](<baseline/runtime-sha256.json>)隐藏差异。
- [verify_target.py](<tests/verify_target.py>)核对接口快照；[LOS24 合同检查](<tests/verify_lineageos24.py>)等依赖仓库外资料的检查，缺资料应明确 SKIP／不可执行，不记为通过。
- [文档检查](<tests/verify_adaptation_docs.py>)应从 Git 公开文件验证本地链接，不依赖本机忽略的产物和现场材料。可选设备场景见[检查清单](<DEVICE_ACCEPTANCE.md>)；构建、主机检查不代替设备视觉、功耗或恢复验收。

## 协作与仓库卫生

- `main` 用于集成和发布，改动在短期任务分支，经必要检查和 Review 后合回。不维护长期 ROM 产品分支；遗留并行 worktree 不随意删除或新增。
- 团队写入按共享任务划分不重叠范围，集成者统一构建和 Git 操作。保留用户未提交工作，不用全库 `git add -A` 收进他人未完成工作，不 reset、不强推。推送必须核远端回执，正式发布才打版本标签。
- 公开仓库只保留当前源码、构建依赖、必要测试／基线、简明维护合同和授权声明。讨论过程、设备现场、发布草稿、一次性补丁／评审包、实验 Demo 不提交；历史可由 Git 查询，不在文档复制逐轮会话或交付回执。
- `.local/` 内签名和个人资料仅本地；生成物放忽略的 `deliverables/`、`verification/` 或 `build/`。不要提交密钥、设备数据、本机配置、额外源码 ZIP 或逐轮备份。必要基线及第三方依赖不能按通用二进制规则误删。
- 不自动安装、重启、修改设备设置或 LSPosed 数据库。诊断遵循内容白名单，不将通知、照片、号码或设备现场写入公开文档。
- 第三方资源与实现来源必须保留实际适用的许可证、NOTICE 和固定来源；用户本地导入的受限素材不能随模块分发。清理文档不能放宽授权或安全边界。
