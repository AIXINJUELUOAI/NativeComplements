# 旧独立小窗兼容

## 当前产品边界

正式构建仅保留[主模块 full](<../../app/build.gradle.kts>)，不再发布独立小窗 APK。小窗使用原生角落 Hook 与扇区，通知／跨应用打开是主模块内的独立设置；长侧滑、无障碍按钮、桌面 shortcut 及小窗菜单莫奈入口已退役；遮罩莫奈按用户要求保留独立开关。停止产品不等于删除设备旧包，也不撤回[规则与平台执行分层](<architecture.md>)。

## 不能删除的兼容面

| 部分 | 当前合同 |
|---|---|
| [ModuleIdentity](<../../app/src/main/java/dev/local/sunwindowdemo/core/ModuleIdentity.java>) | 主包 `dev.nativecomplements.module` 与旧包 `dev.nativecomplements.window` 的 action、authority、权限、宿主偏好／缓存和系统记录命名仍分离；主包历史恢复文件名保持不变 |
| [WindowBackendOwnership](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendOwnership.java>) | system_server 的共享 Properties key `dev.nativecomplements.window.backend.owner.v1` 跨模块 ClassLoader first-claim；不能换 key、覆盖 owner 或同时创建两个 Engine |
| [WindowBackendEligibility](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendEligibility.java>) | 本包 `corner.enabled` 投影自身非导出 PM 组件，只影响下次 server 启动；0 不 claim，1 可 claim，DEFAULT／旧包缺 marker 的 -1 保旧兼容，不据未知断言关闭 |
| [WindowBackendConflict](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowBackendConflict.java>) | 非 owner 不创建小窗 Engine；没有“主版必胜”的加载顺序承诺 |
| [WindowBackendSelection](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendSelection.java>) | 旧选择 marker 只作兼容墓碑，不重新提供后端选择器，不强写旧组件状态 |

资格保存先写并复核 PM，再提交偏好，失败尽力恢复；这不是跨存储原子事务。升级、已解锁 boot、Provider／UI 补迁移；先完成 CE 旧配置迁移，只处理本包、不读取对方私有偏好。首次迁移尚未运行时，该次启动仍可能按旧 first-claim；迁移后的下一次完整重启才采用新资格。

运行中关闭不热卸载全部 Hook、不转交 owner、不跨包迁 journal。主模块 [ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>)仍独立安装；允许消费同 SystemUI 内有界环境快照不等于可借旧独立 Binder 执行主版任务控制、OTP 或后台唤醒。

## 用户迁回顺序

1. 在旧独立版中恢复所有受管理窗口。
2. 手动停用或卸载旧独立模块。
3. 启用主模块小窗并完整重启。

模块不自动卸载、清数据、修改 LSPosed、迁移恢复记录或抢占已有后端。旧 journal 未完成恢复不能为了显示成功而清空。未知修改版不承诺安全并用。

## 与平台能力的区别

包身份隔离和单后端保护不等于任意 OEM 内置 Freeform 共存协议。接管仍要求真实 task、主用户／默认显示器、合法层级与原生能力，详[小窗接入](<floating-window.md>)。不全局关闭系统小窗，也不因 Freeform 可用便忽略 Shell、输入、焦点、Surface 与恢复差异；无需为其他 ROM 再复制独立引擎或产品分支。
