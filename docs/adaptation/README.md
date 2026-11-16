# 跨系统接入与小窗拆分索引

## 当前状态：规则边界已拆分；Alive 原生 AOD 模块实验开发

### 当前增量：0.11.5-alive-test3 / code151（唤醒短隐藏保帧与两类小窗入口）

- 用户确认19:13:31第一轮唯一触发系统动画混入，后续连续反向未触发。只读样本：31.622 wake→31.631 visibility=false→31.632 force-endpoint0/release→31.648 ambient-exit hidden animate=false→31.711恢复visible→31.741静态LOCK重建；后续成功轮scene保留、mode0→1 animate=true。该轮sleep至wake约612ms，并非1800到期，不能把“约2s结束”举例固化为补丁。
- 本轮仅窄修[Service](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)在成功sleep首帧后、真实ON/锁定唤醒时的既有scene资源保留：固定首wake≤2000ms，不重复延长，不是延迟动画；hidden pause不mode/不绘制/不初始化，真实ON/interactive/非ambient恢复时沿当前值反向。visible先恢复不提前消耗窗口，ambient未退不强端点；到期可见只撤资源保留资格，hidden才释放。真正新sleep、非ON、解锁、surface/reload/destroy均取消（同episode重复ambient/PREPARE/SCREEN_OFF不清成功资格、不续sleep期限；资源失效仅epoch撤资格，不闭锁原sleep），普通app隐藏仍原端点恢复，不复活旧解锁种子。动画/native/安全门不改。
- 按用户追加授权，本包同时恢复应用间跳转与通知小窗：原`landscapeShareWindow`/`notificationWindow`两独立偏好及“窗口与多任务”开关，默认true但保已存false；复用原foreground/caller/租约与精确通知原PendingIntent路径，失败原生回退、已dispatch不重发。保规则/状态/执行分层，不恢复其他退役入口，详[小窗接入](<floating-window.md>)。
- 该修复针对已证实visibility中断，不宣称消除所有系统Keyguard内容闪帧或所有结束边界冲突；原生转场与Alive时长不同、duration0和跨episode旧cancel仍是另行诊断，不顺手混改。既有通知/跨app/导航/宿主规则与恢复开关UI22项定向通过，Alive2项值状态含最终重复信号保资格重跑中；只读复核指出两项实现内回归（资源失效闭锁sleep、同episode重复信号清资格），均已定向修正，不是设备已验证的结论。最终AlivePhaseTest修正后2项重跑通过；release/R8/原签名/151身份/7入口3scope/Direct Boot及退役产品边界打包核验通过，主APK SHA256 `c82116891278eec4f90f8fe847bae5ed104f47367258d4d03c39c26bf5ab90b4`。本轮共24项必要定向检查，未跑全套；新包未安装、真机视觉与小窗效果待用户验证。

### 保留增量：0.11.5-alive-test2 / code150（照片入口与卡片缓存）

- [照片入口](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)由OpenDocument改为系统PickVisualMedia/ImageOnly；APK资源仍文件选择器，不新增图库权限，选中照片继续复制到CE草稿，取消不应用。
- [真实卡片缓存](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreviewCache.kt>)为进程内LRU，≤4MiB/12项、单图长边≤512，完整snapshot/phase/真实DE资源revision/尺寸匹配，不持Context/View、不写磁盘或DE、不常驻GL。重进/START先盖匹配真实帧，同代成功swap及Texture更新确认后撤cover；每代最多一次缩略读回，旧renderer完全close再建，代际/identity/timestamp拒绝旧回调。cold进程、未缓存新样式/照片/尺寸或淘汰时仍需首次真实渲染，不承诺冷启动无黑。草稿先读后异步准备运行资源，不再等完整准备才建立卡片。
- 本轮6项定向检查通过：Navigation单页用例追加验证ACTION_PICK_IMAGES及image/*，缓存单用例覆盖隔离/淘汰/尺寸上限，原AmbientAccess四项；未跑全套。独立只读复核无可证P1/P2；仍需真机确认OEM buffer timestamp与单调时基及首帧撤cover表现，单测不覆盖native呈现链。最终release/R8/原签名、150身份、7entry/3scope、Direct Boot及退役产品边界核验通过。[主版APK](<../../deliverables/NativeComplements-0.11.5-alive-test2.apk>) SHA-256：`7bd98ef631b65f12bdc97c05675236fefddd8e06e005960df7c2541cedf2c553`；位置/native/实际壁纸睡眠动画未改。
- 用户另报应用/桌面熄屏先闪一帧锁屏，后确认含时钟/日期/通知。设备149手动复现只读日志：19:04:41.873 native keyguard shown→.933 engine visible→42.003 sleep-presented→42.010 safe FIRST_SWAP→42.016 VISUAL；桌面19:04:50.118 shown→.128 presented→.173 safe FIRST_SWAP→.174 VISUAL。两次admission投影生效，非Hook未装，也非旧~1.3s延迟；show到visual窗口分别约143/56ms，代码只clip LightReveal不控制Keyguard内容层。样本一直Display ON，不能把doze policy当真实DOZE；缺内容alpha/逐帧提交证据，不称完整根因确证或修复。新增用户边界线索：动画结束前后反向亮灭屏最容易与系统动画冲突，1.7~2.1s仅举例，尚无该窗口标注样本。保持147安全门，不猜固定延时、不恢复旧延迟或放宽认证，单独核查各episode收尾/反向衔接。

### 历史增量：0.11.5-alive-test1 / code149（AMBIENT位置与Alive单页，主机核验通过；待真机）

- 本轮仅两项：[AMBIENT固定下移与单页编辑契约](<alive-platform.md>)。启航与五照片样式的AMBIENT锚点统一`.05f`，中心约屏高45%；LOCK/HOME端点仍全屏居中，曲线/时长和147睡眠安全门不变。不是读取真实时钟边界的动态避让，不能保证所有时钟布局无重叠。
- [Alive页面](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)顶部状态卡片点击即弹对应样式Sheet，点选保存草稿并即时刷新；无独立全屏预览/重复编辑入口/完成按钮，下方只保留照片与设置壁纸，资源导入和许可移至更多。复用SettingsPage/动态颜色/系统安全区；按阶段聚合真实预览错误，不允许健康卡片清除失败卡片状态。
- [本用户系统AOD值](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSystemState.java>)只读`doze_always_on`，开启三卡、关闭两卡，未知明确提示且不误当关闭。保留aod样式，临时遮挡/省电不影响板块；已知系统值时使用AOD能力组件，实际暂停/点亮仍完全由系统决定，关闭后再开启无需再次应用。普通组件继续回退，不写系统设置、不改认证。
- 设置壁纸移除模块内重复确认层；同组件无混用可直接发布，首次/切换仍由系统选择器确认且成功核实后才发布，取消保留草稿。148 Direct Boot发布、147准入及原签名主包边界保留；不自动安装/重启/改设备配置。
- 最终8项定向检查通过：既有SceneMotionTest新增实际矩阵坐标/两个比例/五照片往返端点，保打断唤醒；AliveAmbientAccessTest覆盖本用户AOD开关/未知与原草稿DE隔离；AlivePhaseTest和NavigationTest单页卡片/选中即时保存/系统关闭隐藏与重开保样式。未跑全套。测试中修正AnimatorSet seek需扣selfDelay，x86预览页跳过仅arm64资源准备后保留可编辑草稿；独立审查的多卡错误覆盖P2已按phase修复并只读复核。
- 最终release/R8、原签名、149身份、7entry/3scope、角落/Alive运行时、两个service Direct Boot/receiver非导出及无障碍/独立variant退役边界均通过。[主版APK](<../../deliverables/NativeComplements-0.11.5-alive-test1.apk>) SHA-256：`b07506bc9867d2dfac0769df5c30df9780d4a1f4669ec3f3e6bc13b54422cd9f`。真机位置/三卡native观感与性能尚待用户验证；三种解锁现象、照片burn-in与通知/跨应用扩展不在本轮，未宣称解决。

### 历史增量：0.11.4-alive-test1 / code148（Direct Boot，主机核验通过；待真机）

- 仅full主模块，保code147角落小窗与Alive睡眠admission/严格视觉安全门，本轮不混入新的解锁或通知功能。[Direct Boot数据边界](<alive-platform.md#code148direct-boot-数据边界>)将已应用快照和当前照片发布到本用户应用私有DE；从未确认应用的纯编辑草稿照片及编辑缩略图仍为CE。DE会保留历史已应用照片及确认应用中断后的候选，本轮未加自动回收或总量上限，不能声称DE永远只有当前一张。**DE不是凭据解锁后才可读：已应用照片在首次解锁前可由本应用读取**，不外部共享、不跨用户读取，也不绕过系统认证。
- [已应用配置](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)以DE的AtomicFile JSON为权威，照片先完整发布再提交快照；偏好仅通知revision/error，不能因SharedPreferences提交失败而采用未发布的新配置。[核心资源](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)固定上游SHA/白名单不变，旧CE代完整hash校验后仅复制指定资源与manifest，经只读文件、暂存校验、fsync及rename发布DE，不覆盖已有代、不删CE源，不把未核额外文件带入运行时。
- [私有迁移接收器](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveBootReceiver.java>)不是Direct Boot入口，仅在已解锁的BOOT_COMPLETED/MY_PACKAGE_REPLACED后台准备；Engine初始化worker也先准备，真实运行使用DE、预览仍用CE。两个壁纸service标记directBootAware，但不强选、重绑或启动壁纸，不新增认证Hook。
- 新安装须导入并应用一次；旧版须在已解锁状态完成迁移，**之后的下一次开机**才有完整DE数据。未经迁移的首次开机仍安全回退，标记directBootAware不等于已有可用资源或首解锁前真机通过。
- 本轮主版编译、既有`AliveAmbientAccessTest`/`AlivePhaseTest`定向检查通过，覆盖DE锁前快照读取、CE草稿隔离、同名照片变更拒绝、快照写入失败保旧；没有跑全功能套件。最终R8、原签名、148身份/7entry/3scope、两个壁纸service Direct Boot标志、迁移receiver非导出及原产品边界打包核验通过。独立审查发现AtomicFile.finishWrite静默失败可能误报成功，已补照片完整hash与快照字节读回后才通知，且以最终源码重新核验。不自动安装、重启或改设置，首次解锁前显示与147动画观感均待用户验证。
- [主版APK](<../../deliverables/NativeComplements-0.11.4-alive-test1.apk>) SHA-256：`66cf6e329afde4363c0c28502cd8088c46d96bcd4c0380c8b53571937d07785e`。三种解锁场景仍在诊断，未宣称解决；新增五分钟只读样本仅AOD稳定态，无新的解锁对照。时钟下移/避让、照片burn-in及通知/跨应用扩展未实施。

### 历史增量：0.11.3-alive-test2 / code147

- 取消独立小窗产品维护，仅构建/交付主模块。保留 `full` 源集与主包身份、原签名、7入口/3scope，不搬迁Alive资源；独立源目录仅历史保留，不再产生window variant。旧独立包若仍激活，保留原跨ClassLoader owner key与资格门：不能双建引擎、强抢或迁移恢复记录，需用户先恢复旧窗再手动停用/卸载旧独立模块并完整重启。
- [角落适配](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/BackBridge.java>)恢复原生InputMonitor观察、达到斜向阈值后才pilfer；[宿主](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/SystemUiHost.java>)恢复左右下角/扇区与保护矩形发布、截图/安全UI/锁屏/配置/lease取消。保[纯手势规则](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/CornerGesture.java>)和准入/生命周期分层，不恢复长侧滑、通知/shortcut/分享/跨app自动小窗、莫奈；无障碍service/按钮/设置全部移除。其他ROM按[小窗接入](<floating-window.md>)核对ABI与执行恢复，合回产品不是取消适配边界或宣称通用兼容。
- code146本地私有只读新证据（不提交设备日志）：15:40:20.024原生GONE→AOD，20.093已有Alive帧，20.104原生明确shouldDelayKeyguardShow，21.323原生转场结束，21.375真实锁屏，21.418才局部透出。此前1530软件内壁纸延迟可见后初始化到首帧仅36ms；不是GL本身耗时1.46s。先前早透出样本是LOCKSCREEN→AOD，不能充当桌面/软件内已解决的证据。
- [系统睡眠交接](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSleepHandoff.java>)只在本次合格自有壁纸睡眠、默认屏、非交互、无遮挡/认证退出/输入认证且原生未动画的条件下，在原 `UnlockedScreenOffAnimationController.startAnimation` 同步动态范围内将其原true入场判断投影false，由原方法自身写FALSE决策走不播放分支；保原ScreenOff动画列表/Fold、原锁屏处理和唤醒清理。不是全局禁动画，不直接写决策字段/认证/电源/亮度，不缩原生状态转场1300ms。真实锁屏安全门、成功swap/UID/nonce/期限确认与同root局部绘制交接仍必须成立，ABI/deopt失败全撤回本组。
- [Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)仅本Engine结构有效未过期PREPARE、既有active/非interactive/defaultON时，允许原生ambient duration0保留已有有界等待；此时不要求已visible/locked/有效surface，但绘制、ACK与透出仍分别要求这些真实条件。不把命令schema当发送者认证。仅保已有sleep，不新建/续期；其他0时长/SUSPEND仍强端点。原生若早DOZE，只有既有sleep已成功start且未绝限才可确认其可见帧；不强保ON、不延7800、不保证完整1800ms。新code147仍待用户真机验证，不用旧日志宣称生效，照片burn-in/时钟避让未做。

验证收口：主版编译及既有 `AlivePhaseTest`、`AliveAmbientAccessTest`、`WindowBackendOwnershipTest`、`WindowBackendConflictTest` 定向检查通过，原角落纯规则点击/长按透传、阈值claim、多指取消/显示器隔离检查通过；不是全功能套件。独立安全复核发现duration0在可见/锁定前终止等待的风险，已改为只保已有有界等待，绘制与ACK门未放宽，并重新打最终release，不沿用中间hash。最终R8、原签名、主包147身份、7entry/3scope、角落/Alive/Admission保留、无障碍/shortcut和独立variant移除、普通/实验ambient元数据边界通过。未自动安装、重启或改设置，新code147观感与其他ROM仍未验证。

| code147 最终原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.3-alive-test2.apk>) | `8244b4ff14dfc3ed184432432c870b6de24a00699939cba82d7be09fb12fb376` |

### 历史增量：0.11.3-alive-test1 / code146

- 用户明确桌面与应用内熄屏均先走系统动画、稍后才出现Alive，不是仅桌面回调问题。code145只读记录显示14:38:52.987开始睡眠、53.029提交首帧、54.830收拢预算到期，直到57.066才DOZE，期间native visible/draw均为true；不能把“已经画在下层但不可见”误判成没有渲染。旧版减少额外淡出不足以解决完整交接。
- [共用系统睡眠交接](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSleepHandoff.java>)跟随原生KeyguardService睡眠事件，给当前壁纸发送一次episode命令；仅自有当前用户实验组件、AOD开启、默认显示器、非交互、真实锁屏且无遮挡/认证退出/输入认证时，临时保留原生壁纸显示flag。原系统黑层在准备阶段继续绘制，应用内容不转成球体。
- [自有Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)仅真实native visible、有效Surface及成功swap后返回UID/nonce/显示器/绝限校验回执；随后只对同一根窗口的LightReveal绘制做短时空clip，原onDraw、opaque、controller、认证、输入、电源和亮度仍执行。没有回执/ABI不符/异常回原生；不加wakelock、不逐帧IPC、不隐藏初始化。绝限7800ms不续期，隐藏、唤醒、渲染失败、AOD/用户/壁纸/配置变化及OFF/SUSPEND取消。只针对已核这一层，不能承诺所有其他原生遮挡消除或观感已通过。
- [球体轨道](<../../app/src/full/java/org/aliveclean/SceneMotion.java>)修正快速0→1→2时的实际视觉来源：球体权重仍非零就继续AOD展开，而不是按未达到的LOCK逻辑目标选长尾轨，避免decorator从应有33ms退出变成1000ms造成色块拖尾。保留比例连续插值，不改固定native/shader；真正HOME终态不采球，未把截图当作永久终态污染的证据。
- [渲染生命周期](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)不再为不可见/已销毁scene留下唤醒种子；重新可见与重新attach只显示当前端点，解锁回软件再返回桌面不应补播过去的解锁。真实可见的唤醒仍使用既有scene过渡。
- 两包小窗只保留系统无障碍按钮（已于147退役）呼出菜单，删除长侧滑、角落/扇区、通知点击、桌面shortcut、全屏跨应用自动进入和快捷建议小窗入口；既有窗口内部导航、菜单操作、系统后端与安全规则保留。小窗及遮罩莫奈真实路径恒关并忽略旧值，抽屉莫奈不变。独立版移除“关于”，单独保留开源许可；主版“关于”不动。静态scope仍3个，full入口7个/window入口3个。

源码收口状态：Handoff与全部运行时代码已冻结，以下为最终产物而非执行中预报；四篇专项文档可按此快照完成末次复核。

验证边界：既有 `AlivePhaseTest`、`AliveAmbientAccessTest` 及一个最小 `SceneMotionTest` 打断唤醒轨回归通过；不跑全功能套件。独立只读审查核对归档ABI，发现的渲染失败撤回、停绘撤回和Surface attach后回执三项已修，最终快照无确认未处理P0/P1/P2。最终双release/R8、原签名、7/3入口、3静态scope、无障碍最小能力、移除shortcut/手势执行路径及window不含Alive边界通过。首次收口发现选中令牌变量名编译错误已改；旧R8检查仍要求已删除的手势方法也已改为验证移除，随后最终重打包通过，不沿用中间产物。未自动安装、重启或写设备设置；新code146尚未真机验证。时钟避让和照片防烧屏位移未做，仅短时测试。

| code146 最终原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.3-alive-test1.apk>) | `e8cbd4e5f5d714076574fcc5124f04d8ffb64aa45544a8f2abcbc70975fe446b` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.3-alive-test1.apk>) | `3cf1eb23a450c28c687563dca0823ffa34c5c5e5fc04b72657de55839c91c335` |

### 历史增量：0.11.2-alive-test2 / code145

- [全屏Alive编辑器](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)进入即真实单场景背景，顶部返回／编辑／设置壁纸；编辑底部Sheet直接展示当前息屏、锁屏或桌面样式，选中更新草稿背景，成功应用后回HOME并移除当前模块任务；取消或失败留在页面。照片、资源和普通／实验组件选择收在Sheet，草稿隔离保留。
- [导入器](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)增加10张固定上游官方样式缩略PNG，各自SHA-256核验，只存私有独立可选目录，不把native已加载代替换。原来已经导入核心资源的用户，重新选择同一固定OpenAlive APK即可补齐缩略图；没有资源时不伪造图片。缩略为官方样式示意，背景才是本人的实时壁纸渲染。
- 用户本轮桌面手动熄屏的只读日志确认：13:59:57.722开始收拢，59.112被系统隐藏、59.420再次可见，59.832模块窗口到期释放，14:00:01.745原生才进入DOZE，造成约1.9秒空档。新[生命周期](<alive-platform.md>)把收拢结束与资源释放分开，已经呈现的端点静态保留到原生交接；隐藏不绘制、不新建scene、不延长整个episode原7800ms绝对上限。唤醒／零时长进入／OFF／UNKNOWN／移除／SUSPEND仍结束。
- [SystemUI局部衔接](<alive-systemui.md>)仅精确自有实验壁纸、当前用户、AOD、默认显示器非交互且真实锁屏安全条件下，一次性把原生LightReveal目标的额外333ms延迟＋733ms淡出改为立即提交**相同目标暗度**，不设透明0、不改原1300ms转场、controller、认证、电源。只是减少一层视觉叠加，不代表全部系统遮挡或先缩暗已被接管。
- 无障碍服务（已于147退役）是full/window可选的系统无障碍按钮入口，仅呼出现有小窗菜单；不读取页面／事件、不截图、不模拟手势或启用触摸探索。用户手动启用并加入系统按钮；请求仍受本包UID、协议16、enabled、后端及系统安全规则校验，不跨版控制。它不替换现有系统侧后端，仍需要Xposed生效，不能宣称“Freeform＋无障碍就免后端运行”。

本轮不跑整套功能回归，仅生命周期定向检查、必要失败保护审查和最终双release/R8、原签名、身份／scope／入口／无障碍最小能力及独立版边界核验。日志仅证明旧code144的断点，不证明code145观感已通过；用户负责真机验证。没有安装、重启或写设备设置；时钟避让、自创效果、其他厂商引擎及照片防烧屏位移仍未实现，仅短时测试。

最终核验：既有 `AlivePhaseTest` 定向生命周期检查通过（不跑整套）；基线变更清单核验、文档引用、双release/R8、原签名和上述包边界通过。只读安全复核另补renderer统一holding初始化门，覆盖Surface替换与reload入口；该最终修正已重新打包，不沿用中间包摘要。未自动安装或真机验证。

| code145 最终原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.2-alive-test2.apk>) | `11f4fd358e4fce4523f3126133220cfd1b778178447216b9aef52e853802f812` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.2-alive-test2.apk>) | `10bceeb0c01e9ff281153d4182bf00567b6edbaab0f8349476549ffc05b57542` |

### 历史增量：0.11.2-alive-test1 / code144

本轮按用户确认的范围接入启航与真实连续预览，修复小窗开关资格、熄屏交接和额外模糊；时钟避让、照片防烧屏位移、OpenAlive 以外自创效果与其他厂商引擎不在本轮。

- 小窗取消额外后端选择器。[每包资格投影](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendEligibility.java>)把本包 `corner.enabled` 在后台同步到自有 PM receiver 状态，WMS 启动只读本包资格；关闭不 claim，双开保留 first-claim，身份/恢复记录不跨版。资格变更需要下一次完整重启，不热转交。升级广播、解锁后的启动、Provider 与管理页补齐旧配置投影；未完成迁移的 DEFAULT 保持旧兼容，首次升级建议各打开一次确认已保存开关，而非再选后端。
- [草稿配置](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>)使用独立偏好与不可变 UUID 照片；编辑与取消不改变已应用快照。已有同组件且无普通/实验混用时，显式确认更新配置；切换组件先由系统选择器确认，再核实实际目标提交，不能把取消当成功。
- [编辑器](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)与[单活预览](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)共用真实 renderer：点击同画面依次播放息屏0→锁屏1→桌面2→息屏0，不用录像或静态示意替代效果。不模拟真实时钟、认证、DOZE亮度；GL 关闭回执之后才释放 Surface/Texture，避免快速离开返回抢连。
- [渲染器](<alive-renderer.md>)接入真正启航 lens0 与原五种照片1..5，锁屏0..3/桌面6..9分别选择无、长虹、波浪、雾花；默认无附加纹理。雾花真实灰度/遮罩按 native 槽位绑定；五种照片仍必须提供 native ABI 要求的 decorator。启航旋转有限3秒，不常驻 AOD 帧循环。
- [生命周期](<alive-platform.md>)等待 native visible/可绘制最多6秒，资源及起始帧首次成功 swap 后开始1800ms收拢；隐藏暂停三轨、停止绘制，不隐藏初始化，整个睡眠交接绝对上限7800ms。缺场景只允许最近真实LOCK/HOME种子；重复信号不能重启。保留OFF/UNKNOWN/移除/SUSPEND保护，没有修改原生熄屏 controller、scrim 或电源行为，也不承诺所有系统遮挡均已消除。
- 新增用户反馈“息屏指纹直达桌面只有模糊渐清”找到确定代码路径：退出 ambient 的 `duration=0` 曾错误传入 `animate=false`，使 AOD→HOME/LOCK直接终态，后续同目标不会补播。本版仅 ambient 进入零时长强端点，退出按真实当前事实播放展开；不等待或修改认证，不强设HOME。真机事件顺序仍待复测。
- [SystemUI适配](<alive-systemui.md>)只对当前用户实际选中自有实验锁屏壁纸且AOD开启的两条 doze producer 投影其模糊贡献；保留认证动画、QS、启动及其他壁纸模糊。ABI/deopt失败独立卸载该新增链，原生路径保留。

检查边界：用户再次要求少测试。此前已经启动的一轮 full/window 单测通过；之后首帧、暂停与释放时序修正不再重复全套，最终只做代码审查及双release/R8、原签名/身份/静态scope/入口与独立版不含Alive的打包核验。不得把中间单测结果描述为所有最终代码已全测，更不代表动画或原生GPU已验证。未安装、重启、改设备设置；用户负责真机功能与观感验证，照片仍只适合短时息屏测试。

| code144 最终原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.2-alive-test1.apk>) | `7969423a909b572d1a47dd8cf695707e4aea51e432b3795ab2a78161a66789bc` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.2-alive-test1.apk>) | `959c8678a35777fdb902336b5ccb3f1e80cde563367456ace6c3c15360e75d44` |

### 历史增量：0.11.1-aod-test3 / code143

code142 真机只读日志已定位两项：主版小窗虽关闭仍先占后端，独立版开启却 `WINDOW_BACKEND_CONFLICT`；Alive AOD 照片及亮屏正常，缺少熄屏收拢。03:23:45.692 开始睡眠到 03:23:49.735 物理 DOZE 的约四秒内，原门控 `ON && !interactive` 提前停绘并释放，DOZE 直接重建静态0。不能再将该问题表述为完全未接通 AOD，亦不把随后用户改静态壁纸/关闭 AOD 反推作历史失败原因。

- 主版“小窗 → 小窗后端”可明确选主版或独立版。仅主用户后台同步持久化自身非导出、无 filter 的 PM 组件状态；不写 LSPosed 数据或对方设置，不随页面 enabled 热切换。两包须一起升级，在 LSPosed 保持所需系统作用域；重启前先恢复已有小窗。**选择仅下次完整重启生效，不自动安装或重启。** 未明确选择的旧配置保持 legacy first-claim；显式选择不靠加载顺序，不因选中端缺失自动抢回。
- [启动选择与仲裁](<../../app/src/main/java/dev/local/sunwindowdemo/core/WindowBackendSelection.java>)固定 user0 在已核 current 五参 WMS.main before 同步读取并冻结，deopt 真正 SystemServer caller；选中端才创建 Engine/恢复本身份 journal，其余只报告。PM/ABI/deopt 失败停止新增小窗路径，不影响早装 Alive/其他伙伴。无运行中 release/迁移。
- 主版委托独立小窗时，只有明确本启动可信委托状态才消费同 SystemUI 内有界环境快照，只恢复伙伴展示/边缘保护、不接受 foreign Binder/任务/控制；2500ms 过期、锁屏或异常保守暂停。**主版需要自有 Engine 执行的快捷建议小窗启动/后台唤起/OTP 等仍不可用；这些动作不会偷偷跨包执行。** 不宣称所有与小窗有关的主版伙伴动作都已互通。
- [自有 Engine](<alive-platform.md>)增加1800ms有限 sleep 窗口，复用已有曲线，隐藏停绘仅暂留已有scene；OFF/UNKNOWN/移除、零时长、SUSPEND 保护保留，期限/取消/重复信号均收敛。补 `ALIVE_ENGINE` 与 `ALIVE_RENDER` 边沿日志，不逐帧刷日志。不改原生 screen-off 控制器、scrim、认证、亮度或接近保护。
- 用户另报未选特效却锁屏/桌面全屏扭曲、进入任意应用再返回恢复原图。确定发现旧版三处硬编码 `lock=2`，固定 native 映射为曲面玻璃，会偏移照片 UV；本版统一锁屏0/桌面6原图，保留 AOD 样式。原图 HOME 终点本不应有玻璃，暂态恢复提示阶段/呈现刷新问题但截图不足实证；另补原生事件后最多四次有界真实 Keyguard 复查与端点 ratios 日志，不把 goingaway 当已认证，也不误称修改壁纸保存即已证根因。
- 样式仍只有留光/星月/轻启/山脉/银河五种照片效果；上游0号“启航”是另一渲染路径，尚未接入，不能补菜单名冒充支持。照片烧屏位移仍未实现，仅短时验证。

定向检查通过：双产品后端冻结仲裁、full委托环境TTL/超限/无控制能力、full PM marker保存不改corner偏好、双产品既有开启提示流程，以及full既有Alive阶段/窗口和精确绑定/原图纹理。没有再跑421项全套或新增大套件；曾遇公开SDK缺 `UserHandle.identifier` 编译错误，改为固定用户UID区间判断后重跑通过。代码独立只读复核P0/P1=0；Lead最终复核另修独立包R8可能改marker类名（改固定组件字符串，并加入双包DEX常量检查）。基线主机检查与15篇/414个本地文档引用通过。最终双release/R8、原证书、身份/scope/入口、普通/实验metadata、非导出禁用marker及独立版边界核验全部通过。

| code143 最终原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.1-aod-test3.apk>) | `e6960f7eadb3285c710939f6d71cf3c9b49644ad7261ac09e2148e8bbad56773` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.1-aod-test3.apk>) | `fb927595fdda3712354cc864d3002093102d08f15ed0af8afb49c55c5218c9be` |

未执行安装、重启或设置写入。code143 最终熄屏画面、解锁及进应用返回恢复仍待用户重启选择后复测，不宣称已替代全部原生熄屏动画或设备功耗/烧屏保护合格。中途旧hash打包未交付；只以上述最终双包为准。

### 前一增量：0.11.1-aod-test2 / code142

按用户截图反馈，主版/独立小窗的 Freeform 启用检查改为提醒而非硬拦：设置缺失、读取失败、能力未知等提示均有“继续开启/取消”；继续仅保存用户开关，不自动写系统设置、不伪装能力验证通过。原生任务安全检查和单后端防重复接管保留，不能靠点继续强抢其他模块。复用既有开启与宿主通知检查，未增加测试套件。曾因把 `--tests` 只写在双 task 命令末尾而误跑 full 421 项（不是有意新增全套回归）：其中一处断言仍匹配旧提醒文字，一处旧持久化测试反射字段未跟随之前状态提取，已修正测试调用/文字与字段路径，再用每个 task 各自的 `--tests` 定向验证。Alive 仍为下述实验能力，照片防烧屏位移未实现，不得长期常亮测试。

定向检查最终通过：full 6 项/window 5 项（四个原有开启流程、宿主通知，另 full 复核旧持久化路径）；双 release/R8、原签名、身份、scope、入口及独立版边界检查通过，独立只读复核未发现 P0/P1。未重新安装或操作设备。

| code142 原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.1-aod-test2.apk>) | `bc4fab66c45614b232ce98ed49a8ccc0ba6e8f9462fcdce286c4681ce7d50712` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.1-aod-test2.apk>) | `046809bf883a75ea8d51393b9922423f95b6f232c86859dc7299497b73e826b1` |

### 前一实验：0.11.1-aod-test1 / code141（未真机验证）

用户明确纠正：没有 AOD 不能称完整 Alive，先前 0.11.0 只交付普通照片壁纸。之后授权只对自有壁纸的必要系统绑定检查做局部例外；不是全局授予权限，更不改解锁认证。已连接设备只读核实系统三份归档哈希，随后用户断开设备继续离线开发。

- 普通服务保持 `supportsAmbientMode=false`，不因升级自动选择实验版；系统预览另提供 `AliveAmbientWallpaperService`、metadata=true，用户选择此组件本身即明确 opt-in。回退是在原生选择器切回普通版或其他壁纸，不把卸 Hook 当成已连接 Engine 立即回退。
- [绑定局部例外](<alive-module.md>)只覆盖同步绑定自有实验组件时的一次 ambient 支持检查；不修改 PM 授权状态、调用 UID 或其他权限门；预检/deopt/安装失败回原生拒绝，不影响普通版。
- [SystemUI 原生能力投影](<alive-systemui.md>)组合宿主 Flow 与原生壁纸/用户变化，仅当前完整选定用户、实际锁屏组件精确匹配且真正 AOD 设置开启时增加支持。独立静态锁屏不能误当桌面继承；不改 readonly StateFlow，不强透明 scrim，不改全局能力资源。原生 light-reveal、亮度、省电与遮挡暂停继续执行。
- [Engine 生命周期](<alive-platform.md>)按自身显示器状态、原生 visible 与真实 ambient 回调决定绘制：OFF/UNKNOWN/移除即停，DOZE_SUSPEND 即时端点后停帧；不加唤醒锁。GPU/JNI 始终普通应用 UID，不注入系统宿主。
- **照片 AOD 目前固定位置，未接入系统照片烧屏位移。系统时钟有位移不等于照片也有；保留原生保护不代表保证不烧屏。仅短时验证，不建议长期常亮测试。** 设备这次观察只捕获 NEAR 约 0.2 秒即 FAR，取消原生默认 10 秒暂停，未捕获持续遮挡到期；暗光调暗与接近暂停是不同链，不能据此判传感器或保护故障。

必要检查已完成：full 源集编译及两项最小 Alive 检查（阶段/显示器门控、精确绑定例外）通过，window 源集编译通过；独立只读安全审查未发现 P0/P1。双 release/R8、原覆盖升级签名、双独立身份、3 scopes/7 与 4 entries、自有普通/ambient XML 分别 false/true、独立版不含任何 Alive 运行时边界全部通过。首次新增 XML 断言因 aapt2 显示 `false/true` 而非十六进制常量失败，已修正断言并完整重跑打包通过，不是 APK 编译或逻辑失败。最终文档检查 15 篇/405 处链接通过。**没有安装、重启、修改设备设置或设备端实测。构建通过不是 AOD 已可见、过渡连贯、功耗合格或跨 ROM 兼容的证明。**

| 0.11.1-aod-test1/code141 原签名产物 | SHA-256 |
|---|---|
| [主版 APK](<../../deliverables/NativeComplements-0.11.1-aod-test1.apk>) | `d66341540a2581c226ea18cfc2fb5e45a90d2954084b33b984e87ce1e198853c` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.1-aod-test1.apk>)（无 Alive 改动，仅同版打包） | `7dc138c36e712edbcd2b615fe9fae811a4563b91746110880ee466a7112715a7` |

AOD 实验实现提交 `fcc83b2` 已推送，回执 `1451b48..fcc83b2 main -> main`；实验标签 `v0.11.1-aod-test1` 亦已推送。后续 code142 只修小窗启用提醒，不能沿用 code141 哈希。

### 前序全部功能规则边界拆分

用户追加授权顺序为：**现有全部功能规则/状态与系统执行分离、文档及 push 完成后，再开始 Alive 壁纸研究实现**。已确认三次推送：`268f09c..9b6b41b`（外观/图标）、`9b6b41b..537efc8`（通话/传感器/触控）、`537efc8..5ee8e95`（建议/弹幕/诊断及全部接入索引），本地与远端 main 同步后才开始 Alive。本轮非小窗规则边界已完成集成：相关既有功能检查与 full/window 双源集编译通过，既有通话增强（含 Android javac/安全不变量）和抽屉莫奈归档检查通过，10 篇接入文档 378 处本地链接/源码范围通过；独立安全审查 P0/P1 为 0。本阶段没有运行四百余项全套，也未操作设备，不能沿用下方 0.10.0 的历史测试结果声称本轮已全测。提交推送后继续 Alive，不在这里提前称 0.11 已交付。复用既有检查，不新增成批测试或反复全套；必要启动安全、权限、失败恢复与原签名/R8 核验保留。

### 非小窗功能接入入口

| 范围 | 规则/状态与原生执行边界 |
|---|---|
| 导航、双栏、状态栏外观、信号 | [系统外观接入](<appearance-boundary.md>) |
| 桌面、抽屉、通知与小窗图标 | [图标接入](<icon-boundary.md>) |
| 弹幕与快捷建议 | [通知与建议接入](<notification-boundary.md>) |
| 来电拦截、传感器、边缘防误触 | [系统策略接入](<system-policy-boundary.md>) |
| 诊断、配置快照与产品组成 | [共享宿主接入](<shared-boundary.md>) |

已有纯策略复用，native Hook、精确 ABI/资源、Binder 身份、线程、IO、View/Surface 与回滚留在各适配侧；不为单实现堆通用接口，不把页面代码迁移当作业务解耦。其他系统可复用规则层并实现真实平台事件/执行链，仍需核实自己的组件构建与设备行为。

### Alive：0.11.0 / code140 本地资源接入

主版新增真实原生 `WallpaperService`，裁剪接入 OpenAlive 固定版本的 MIT 自实现照片渲染链，提供留光、星月、轻启、山脉、银河五种照片效果；并非加载 Flyme 官方壁纸服务，也不声称原版一致。入口位于首页“Alive 壁纸”，步骤为：

1. 用户自行取得 [OpenAlive 0.4.41-preview 官方 APK](https://github.com/Maga-King/OpenAlive/releases/tag/v0.4.41-preview)，在模块中通过系统文件选择器导入。完整大小 `643140270` 字节，SHA-256 `99361d0275ef98f00206c9f53202efadf5686cc82011497a58c2d37a0e47bb71`；不安装、不加载其 DEX，也不自动下载。
2. 选择自己的照片与样式，在后台有界解码并私有保存；缺资源/照片不使用默认渐变冒充就绪。
3. 打开系统预览，用户亲自确认应用本模块的服务；不自动替换当前壁纸。切回其他壁纸由系统管理，不改其他壁纸或认证逻辑。

官方 shader（含 native 库内嵌副本）、mask 不属于上游 MIT 代码授权，本模块不打包再分发，仅允许用户显式本地导入固定校验资源。仍需用户遵守适用权利与许可。[资源导入安全](<alive-assets.md>)、[真实渲染与 JNI 边界](<alive-renderer.md>)、[平台事件/权限边界](<alive-platform.md>)分别说明输入→规则→执行及失败恢复。

**0.11.0 历史版本不支持 AOD；不等于页首新实验已经设备验证。** 当时系统对 ambient 壁纸及 keyguard 命令有壁纸包权限检查，不能靠 XML 声明或普通广播等价替代；metadata 保持 `supportsAmbientMode=false`，不绕过权限、不强透明 scrim/黑屏、不改指纹/PIN/输入。熄屏状态用于停帧和下次唤醒的真实照片展开，不宣称可见息屏或厂商级一镜到底。原生壁纸淡入保留，显式禁用自有 WM 额外 zoom 也不是已经证实修复设备重复动画。

独立小窗版不包含 Alive 服务、导入器、渲染代码或效果资源；双产品仍保留原三静态作用域，无新增系统 Hook。主版只在普通应用 UID 自有壁纸 Surface 中加载已校验的 native，不注入 system_server/SystemUI/Launcher。

本轮必要验证：两项现有 Gradle 入口下的 Alive 小检查（完整帧白名单、纯阶段事实）及 window 编译通过；固定原生库实际 ELF 依赖仅 `libandroid/libdl/libGLESv3/liblog/libm/libc`，三个 PT_LOAD 对齐 16384；243 个 RGB 遮罩解压尺寸全部匹配。独立代码审查修复真实 decorator 入口参数、等待遮罩后的终点补帧及多 Engine 共享 EGLDisplay 生命周期。最终 [双产品打包入口](<../../tests/package_release.py>)通过双 release/R8、原签名、身份、3 scopes、7/4 entries、主版 JNI/service 保留和独立版边界；官方库/shader/mask 均确认未打包。最小文档检查覆盖 13 篇与 395 个本地链接。没有批量增加测试，也没有安装、重启或修改设备配置。

0.11.0/code140 原签名产物：

| 产品 | SHA-256 |
| --- | --- |
| [主版 APK](<../../deliverables/NativeComplements-0.11.0.apk>) | `e809948b3b7f9b44cf7267b92343627789a8679dd7146629fce8b79a3c6b2512` |
| [独立小窗 APK](<../../deliverables/NativeComplements-Window-0.11.0.apk>) | `15478f52572dc36f8cd2949aadaf9a35be234ceba8e7de2c0a5a0ef62a87e51e` |

Alive 实现提交 `a5543ce` 已推送，远端回执 `5ee8e95..a5543ce main -> main`；与前序全部解耦提交的顺序可在 main 历史直接核对。发布标签为 `v0.11.0`（只包含已核验的源代码/说明，不打包官方资源）。

证书 SHA-256 仍为 `28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104`。**真机启动、桌面与 GPU/JNI、五效果、锁屏/PIN/生物识别、切回普通壁纸均尚未执行，主机检查不能据此称全部真机可用或保证不会出错；AOD 未支持，LOS 亦未经适配验证。** 快速隐藏会释放资源，重新可见时从唤醒 seed 重建，不承诺跨隐藏周期视觉连续。

## 已交付：0.10.0 / code139 第一轮代码闭环完成

第一轮授权的小窗规则/状态与原生执行分界、同源独立小窗产品隔离已完成，版本为 `0.10.0` / versionCode `139`，以[构建配置](<../../app/build.gradle.kts>)为准。**这是代码与主机验证交付，不是设备效果或 LOS 兼容声明，也不是任意系统可替换的完整平台接口。** 该次交付时 Alive 与其他功能重构尚未实施。

### 最新进度：恢复与生命周期闭环

- 恢复阶段 `912070f` 已 push，receipt：`cbd207b..912070f`。纯 [WindowRecoveryPolicy](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowRecoveryPolicy.java>)承载 boot/live 资格、100 次重试与就绪一次复查；[WindowHistory](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowHistory.java>)承载 120ms 稳定观察，native snapshot 仍先于历史删除。该阶段完整单测 full 405 / window 400，零失败。
- 生命周期阶段 `a0f3a7b` 已 push，receipt：`912070f..a0f3a7b`。纯 [WindowLifecycle](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowLifecycle.java>)承载 tick、priority/input、continuation 与 visible-resume 值规则；原生 lease 最后采样、隐藏分支无 lease、WM 锁内重验及执行顺序保持。
- 最终完整单测 **full 415 / window 410，均零失败、零错误**；基线 **4 unchanged / 22 documented changes / 37 additions**、全部 host checks、无 Android 编译依赖的标准 javac 纯类检查及归档接口检查通过。current/QPR2 入口归档签名检查通过，不能替代真机回归。
- 最终双 Release/R8 与双 APK 原签名、独立 IDs、三个静态 scope、full 七/window 四入口、快捷方式及禁止非小窗 Hook 的 DEX 边界核验通过。导航、恢复、生命周期独立审查 P0/P1 均为 0；纯规则均有实际生产调用。
- [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)保留 Android 协调、原生对象身份、WM/事务、AtomicFile/Parcel 与恢复副作用；不为单实现新增通用平台接口，不以文件大小衡量解耦。当前计划中的五个闭环已收口，设备回归按[设备验收](<../../DEVICE_ACCEPTANCE.md>)执行，安装/重启/LSPosed 配置由用户操作。

### 最终双 APK

版本均为 `0.10.0` / code `139`，使用原覆盖升级证书；只交付双 APK，不交付源码 ZIP 或额外报告。

| 产物 | SHA-256 |
|---|---|
| 主模块（本地交付产物，不入库） | `d422c90bcb2e5c73630a017ca77726fcefd38c9c298b0b5d7f9dc971702e62d2` |
| 独立小窗（本地交付产物，不入库） | `0db253d3dde78c781c33dc8c63b5ebdccb39c5c60a59598b963b243ab6a4ccbb` |

APK 在本地生成且不入 Git；以上核验对应 `a0f3a7b` 的运行源码。安装/重启/框架配置由用户操作，建议只启用一个小窗后端；主版覆盖升级，独立版使用隔离身份、配置和恢复记录，不自动迁移主版设置。

### 历史阶段：a8d6d60 已提交并 push

最新阶段 `a8d6d60` 已 commit 并 push main，receipt：`00b6db6..a8d6d60`。本节集中记录最新证据；后面的提交与数字均是对应阶段历史，不覆盖后续未提交改动。

- `WindowNavigation` 已为纯 value，native 票据由 `AndroidWindowLaunch` 持有；`WindowPolicy` 的 5 个 Object 签名改为 boolean facts，Host 各入口统一使用 `WindowHostPolicy` admission。
- `BackHandoffState` 提取 once、generation、idle 与 timeout 状态；**同时修复既有 late/duplicate callbacks 可能导致多次 commit 的漏洞**，不是只搬代码。原生取消、Shell 与主线程 IPC 顺序保留。
- 完整双单测 **full 396 / window 391，均 0 失败**；`verify_runtime` 的原基线 5 unchanged / 21 documented changes 与 35 additions、全部 host checks，以及无 Android 编译依赖的标准 javac 纯类检查通过。
- 双 release/R8、双 APK 打包的原签名、IDs、scope、entries、shortcuts 与 forbidden DEX 检查通过；独立 review 的 P0/P1 为 0。以上均是阶段静态/主机/构建证据，**不是设备或 LOS 兼容结论**。

该阶段当时剩余恢复、窗口生命周期两闭环；现均已在上方最新阶段完成。该阶段的 APK/hash 只作历史证据，不作为最终交付包。当时 Alive、其他功能重构暂缓；native AtomicFile/Parcel/WM/Binder 留在适配侧。

### 此前已提交并 push 的边界

- `3afdb01`：呈现规则提取为纯 [WindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowPresentation.java>)，原生对象、锁与事务保留在 [AndroidWindowPresentation](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowPresentation.java>) 执行侧；并非整个引擎平台无关。
- `58f39f9`：启用前使用真实 Freeform 能力与 correlated STATUS 请求/响应门控，不自动修改系统设置；以 single Properties owner 隔离重复接管，保留主包身份和恢复含义。这不是任意 OEM 原生小窗的通用共存协议。
- `bd3aafb`：[LauncherThemeReporter](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeReporter.java>) 独立，不再把系统主题状态上报绑定到抽屉莫奈安装。

### `a6da12a` 已提交并 push 的边界

- C1 接管规则已落 [WindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowAdmission.java>) 与 [AndroidWindowAdmission](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/AndroidWindowAdmission.java>)；共用同一 [WindowEngine](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowEngine.java>)，不是复制引擎。
- Gradle 增加 `full` / `window` productFlavors；[full ModuleApp](<../../app/src/full/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>) 从 main 迁出，[Page](<../../app/src/main/java/dev/local/sunwindowdemo/navigation/Page.kt>) 仍共享，[window ModuleApp](<../../app/src/window/java/dev/local/sunwindowdemo/navigation/ModuleApp.kt>) 是窄小窗入口。
- 注册表改为 [full java_init.list](<../../app/src/full/resources/META-INF/xposed/java_init.list>) 与 [window java_init.list](<../../app/src/window/resources/META-INF/xposed/java_init.list>)；window 为 4 entry（3 个小窗入口 + [LauncherThemeModule](<../../app/src/main/java/dev/local/sunwindowdemo/features/iconlayers/LauncherThemeModule.java>)），[scope.list](<../../app/src/main/resources/META-INF/xposed/scope.list>) 仍在 main、保留原三个系统作用域。
- [HostExtras](<../../app/src/main/java/dev/local/sunwindowdemo/HostExtras.java>) 契约配合 [full ProductFeatures](<../../app/src/full/java/dev/local/sunwindowdemo/ProductFeatures.java>) / [window ProductFeatures](<../../app/src/window/java/dev/local/sunwindowdemo/ProductFeatures.java>) 真实源集隔离非小窗宿主实现，不只是隐藏页面或加运行时布尔量。window 的 [WindowIconProvider](<../../app/src/window/java/dev/local/sunwindowdemo/features/iconlayers/WindowIconProvider.java>) 是 native mono / Monet 的窄桥接，不带抽屉、图标库或生成入口；不是 full 图标能力等价品。

推送 receipt 已确认 `bd3aafb..a6da12a` 到 main。该阶段完整单测 **full 353 / window 348，均 0 失败**，双 release/R8 及 APK 原签名、身份、三系统 scope、四/七入口、快捷方式与禁止非小窗 Hook 的 DEX 边界核验通过；345 pass 是更早阶段证据。未操作设备，生成包仍是阶段检查产物。

### `004aa43` 已提交并 push 的规则状态边界

- C2 [WindowControl](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/WindowControl.java>)：控制路由、形态/分组与几何规则，6 组测试。
- C3a [LongBackGesture](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/LongBackGesture.java>)：长返回识别状态，5 组测试；原生接纳/取消仍在适配侧。
- [OpenWaitState](<../../app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy/OpenWaitState.java>)：等待请求与取消 token 归属，4 组测试。

推送 receipt：`a6da12a..004aa43` 到 main。完整双全测 **full 368 / window 363，均零失败**；基线/全部主机规则与归档接口检查通过。此阶段未重新发布 APK；之后未提交改动不得沿用该结果。

`004aa43` 当时剩余导航/Policy、恢复、tick 调度、Host admission 与 Back handoff 五闭环；其中导航/Policy、Host admission、Back handoff 已在后续阶段推进，其余恢复与窗口生命周期也已在最新阶段收口，完整证据以[最新进度](<#最新进度恢复与生命周期闭环>)为准。编译、单测、R8、当前设备回归和目标系统验证分开记录。

未来 LOS 贡献顺序：**先由我们完成 refactor → 贡献者再 fork / adapt / test 并提交 PR → 我们 review 并做现设备回归 → 最后决定 merge**。不在当前未冻结边界上承诺适配完成，不凭 ROM 名或 Freeform 开关宣称兼容。

## 138 历史审计的范围与读法

以下审计、原 PR 路线与历史检查结论保留自 `main` 提交 `930a22c`（标签 `v0.9.48-fixes-test2`，versionCode 138）；它们不是当前实施状态。各专项页也在页首区分当前进展与历史正文。**源码链接已迁移到现路径，仅供定位参考，不是 138 snapshot 的精确函数内容或行号证据**；移除可能因迁移（含 full 导航行数减少 7 行）或后续提取失准的源码行锚，旧内容请按历史提交核对。

设计讨论来源：[Issue #3：针对小窗的一些个人建议](https://github.com/AIXINJUELUOAI/NativeComplements/issues/3)。采纳其“功能逻辑与具体 Hook/执行分离”的方向，不据此承诺 LineageOS、MIUI 或任意发行版已兼容，也不默认禁用系统内置小窗。

## 阅读顺序

| 你要做什么 | 先看哪里 |
|---|---|
| 理解进程、事件、规则、执行和配置的现有关系 | [架构与边界](<architecture.md>) |
| 为其他系统接入小窗或核对 Hook | [小窗系统接入清单](<floating-window.md>) |
| 适配导航栏、图标、横屏面板、来电等功能 | [其他系统功能清单](<system-features.md>) |
| 判断独立小窗版还需要什么、如何与主版隔离 | [独立小窗依赖与拆分路线](<standalone-window.md>) |
| 提交前核对开发约束与已有设备场景 | [开发约定](<../../AGENTS.md>)、[设备验收](<../../DEVICE_ACCEPTANCE.md>) |

## 接入原则

1. **共用行为，不复制引擎。** 先明确系统事件输入、规则状态决策、平台执行与恢复，再在实际出现差异的边界增加对接；不要先给每个类创建接口。
2. **按发行版组织证据，按实际能力决定安装。** 发行版名称只是检索信息；Android 版本、组件版本、精确 ABI、调用语义、线程及转场所有权都需核验。同 API 不代表私有实现相同。
3. **把 Hook 与普通反射依赖分开。** Hook 是事件或行为入口；被调用的隐藏 API、字段、资源和原生事务同样属于适配依赖。反射统一放一个类，不等于核心已解耦。
4. **失败边界与能力同样重要。** 核心链不满足时不接管；可选子能力保持原生或局部关闭。`PROTECTIVE` 不是完整回滚保证，具体清理必须核对安装代码。
5. **只操作模块明确拥有的任务和输入流。** 系统自带小窗、PiP、权限确认、锁屏、IME 等不是可随意屏蔽的对象。先核对所有权及冲突策略，不全局关闭 Freeform。
6. **打包边界与系统适配边界分开。** 独立小窗可复用同一套核心；单改 applicationId、删首页入口或只加构建常量，均不等于独立版已完成。

## 138 原建议：最小 PR 顺序

| 阶段 | 改动边界 | 验收条件 |
|---|---|---|
| 0：目标证据 | 补目标系统的版本、组件、差异及精确签名 | 不含设备隐私；区分源码推断、归档检查、真机结果 |
| 1：一个实际差异 | 选一个可选功能或小窗输入入口，局部隔离其目标解析/安装 | 当前路径不变；目标不存在时可原生回退；有最小可运行检查 |
| 2：小窗单条闭环 | 启动请求 → 任务归属 → 呈现/输入 → 全屏恢复 | 核对调用时机、锁、事务及清理，不只改方法名字 |
| 3：扩展平台边界 | 在第二个系统的真实差异上提炼共用输入/执行契约 | 核心决策不依赖 ROM 名或反射成员；两端验收记录齐全 |
| 4：独立产物 | 身份/路径隔离、入口裁剪、冲突策略、同源构建 | 不安装其他功能 Hook，不串配置/任务恢复，不双重接管；主包覆盖兼容 |

每个 PR 只做一类事情：行为保持的边界整理、目标适配、功能改动尽量分开。不要把整库搬家、类重命名、协议改动和新功能塞进同一 PR。现有 Pixel 风格系统与新增 LOS 各自需要维护者实机验证，任何一侧只有编译通过都不算兼容完成。

## 贡献者需要提供的证据

- ROM/Android 版本、构建标识及所涉及组件的版本或摘要；包名与运行进程。设备序列号、账户、通知内容及私有截图不提交。
- Hook 的声明类、完整参数/返回、字段类型、before/after/替换语义、上游调用者；是否存在内联而需 deopt，缺失如何退出。
- 正常输入和异常输入的结果、线程/锁、原生操作完成回调、任务和 Surface 的所有权及恢复路径。
- 最小测试、当前实现回归结果、目标真机结果；未验证的部分写明“未验证”。不把源码中存在某类当成运行调用证据。
- 对于小窗：至少核对打开/缩放/mini/返回全屏、旋转与 IME、锁屏/安全确认、任务结束与恢复；按改动扩充已有 [设备场景](<../../DEVICE_ACCEPTANCE.md>)。

## 检查及证据等级

```powershell
python -X utf8 tests/verify_adaptation_docs.py
python -X utf8 tests/verify_runtime.py
# 具备本地目标归档时再执行：
python -X utf8 tests/verify_target.py
python -X utf8 tests/verify_navbar_target.py
```

- 文档检查只保证本轮 Markdown 的相对文件链接、源码行号范围和关键入口可解析，不证明描述语义或 Hook 兼容。
- [基线检查](<../../tests/verify_runtime.py>) 维护原始 Demo8.2 摘要及已登记差异，不得改基线掩盖重构。
- [归档接口检查](<../../tests/verify_target.py>) 与 [导航宿主检查](<../../tests/verify_navbar_target.py>) 依赖本地归档，缺归档不是通过；精确签名通过也不代替线程、时序及真机验收。
- 138 历史运行基线（不代表当前结果）：全量单测 311 项，307 通过，4 项失败均在既有 [SurfaceFrameTest](<../../app/src/test/java/dev/local/sunwindowdemo/SurfaceFrameTest.java>)；本轮不修它们，不宣称全量绿。新背板/莫奈针对性测试与原签名 release 检查已通过。用户对 137 其余修复的正面反馈也不等于所有系统/场景验证。

### 138 文档审计当轮 Lead 复核结果（历史）

- 五份接入文档的本地链接与源码行号检查通过；人工核对进程入口、通信授权、运行逻辑未改及各子审计关键结论。
- Demo8.2 基线与主机规则检查通过；原基线摘要、差异登记未改。
- 本地目标归档接口检查通过；导航归档检查明确 current Launcher 路径可核验、QPR2 Launcher setter 不支持，不隐去此结果。
- 没有运行新的 Gradle 构建或设备测试，因此不产生新的功能验证结论；前述 138 单测失败仍为历史基线信息。

本轮只改文档及文档自检，不重打 APK、不安装、不重启、不更改设备设置或 LSPosed 范围。
