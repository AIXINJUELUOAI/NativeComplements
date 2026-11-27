# LineageOS 24.0 接入

## 来源与边界

贡献者：[LFRon](https://github.com/LFRon)（提交作者 mozixun）。来源为 [PR #4](https://github.com/AIXINJUELUOAI/NativeComplements/pull/4)（`89b0c783518b833da5f39bfa5de0131a13de392b`）及后续 [PR #10](https://github.com/AIXINJUELUOAI/NativeComplements/pull/10)；当前范围以源码与本目录合同为准，不沿用旧版本的验收结论。

维护者信息仅用于首次一次提示、关于页署名与反馈路由，不作为权限或兼容性认证。维护者仓库未开启 Issues 时使用[主项目反馈](https://github.com/AIXINJUELUOAI/NativeComplements/issues)，不要求贡献者补测试模板或报告，不在各功能页添加兼容警告。

仅维护 `full` 主模块。静态作用域为 system、SystemUI、Pixel Launcher、Launcher3、电话（仅短信拦截）和系统设置（仅设置内图标），不新增普通应用作用域。当前无额外 flavor。

[LauncherTargets](<../../app/src/main/java/dev/local/sunwindowdemo/core/LauncherTargets.java>)核已知系统实际 HOME 与本用户真实 UID；ROM 名称或包存在不授予权限。相同 Hook、规则与恢复共用，真正不同的签名和安装时机放在功能局部 Access／Targets，必要目标未知则保持原生。

## 当前接入合同

| 范围 | 接入与保留边界 |
|---|---|
| 小窗 | 共用 FrameworkAccess、任务准入和恢复；参数布局按宿主真实方法解析，不复制引擎 |
| 返回／通知／截图 | 保留原生角落准入、通知正文原 PendingIntent、派发前回退与派发后不重发；字段／getter 仅局部解析，不恢复长侧滑 |
| 桌面与图标 | getter、缓存参数及协变返回值局部解析，空白双击仍核真实桌面状态；不自动写 Minimal／ThemePreference |
| 导航 | 保既有 SystemUI／Taskbar owner、显示器及原生手势边界，不恢复旧逐应用模板 |
| 横屏双栏 | 保 Pixel 字段／emit；LOS getter 在真实 repository 的 Resources 身份及精确求值调用栈内逐次投影，配置刷新 callback 为必要条件；不把 suspend collect 当异步作用域，不写 Flow 或强开 Scene／Dual |
| 信号／旋转 | Kotlin 静态 binder 与接口 getter 局部接入；保原生订阅、输入、点击和超时，各组 deopt／安装失败局部回滚 |
| 后台管理 | freezeBinder 参数按真实方法解析，返回值不算调用参数；WakeLock 仅保已确证单布尔 setter，未知不猜测 |
| 暗处 AOD | mDozeHost 接口／实现按可赋值关系解析，保用户、系统 AOD、原生省电和接近保护；不丢已有接近事件回调 |
| 来电／短信／边缘 | 共用规则与原生执行；电话作用域仅短信使用，不扩大 Provider 或输入过滤授权 |

## 资料与验证

[固定来源清单](<../../tests/lineageos24-provenance.json>)及[源码合同检查](<../../tests/verify_lineageos24.py>)引用官方 `lineage-24.0` 的 framework、SystemUI、Launcher3、Telecomm、Telephony 与 ThemePicker。检查需要仓库外固定源码；缺少资料输出 **SKIP**，不记通过。

源码目标、主机回归、R8／签名检查与设备效果分别判断，不凭方法存在或构建成功宣称全部适配。未随仓库提供的设备材料不作验收依据，也不要求补材料才允许使用。当前入口见[接入索引](<README.md>)；历史由 [Git 提交](https://github.com/AIXINJUELUOAI/NativeComplements/commits/main) 保留，不在此复制合并过程或逐轮回执。不自动安装、重启、改 LSPosed 或设备设置。
