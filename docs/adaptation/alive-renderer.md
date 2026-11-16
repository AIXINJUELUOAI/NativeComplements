# Alive 照片渲染内核

## 当前增量：0.11.5-alive-test2 / code150

[预览卡片](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)在TextureView上叠ImageView，仅以[成功真实帧内存缓存](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreviewCache.kt>)盖住重新进入/START重建：总缓存≤4MiB/12项、单图长边512；完整Snapshot/phase/DE实际resources_revision/实际尺寸匹配，LRU淘汰，不回收仍被cover引用的bitmap，由GC管理。没有磁盘/DE副本、Activity引用或后台常驻GL。cold进程/未命中仍要首次渲染。输入变化先关闭旧renderer再新建，代际+renderer/texture identity+单调buffer timestamp截止及presented成功swap/TextureUpdated共同确认后才撤cover/捕一张缩略图，失败不伪造缓存；停止仍按原completion释放精确SurfaceTexture。页内草稿先读再异步准备资源，资源未ready仅展示匹配缓存、不建GL。照片入口使用系统ImageOnly选择器，APK导入仍文件选择器。实际壁纸service、motion和睡眠协议均未改，不能用缓存宣称修复熄屏闪锁屏。

## 保留增量：0.11.5-alive-test1 / code149

AMBIENT位置与单页编辑见[平台契约](<alive-platform.md>)：lens及五photo默认anchorY=.05、中心45%屏高；LOCK/HOME端点不动，不改native/曲线/时长，不称动态时钟避让。顶部两/三卡各持有同一renderer类型的独立TextureView、GL worker/EGL context/native handle，按当前阶段静态端点初始化，无点击循环或3秒idle演示。按阶段聚合错误、移除时清理，健康卡不能清空失败卡；生命周期及精确Surface关闭回执不变。主机定向测试不等于native真机观感/性能通过。

## 保留范围：0.11.3-alive-test1 / code146

本节覆盖code145当前性表述；固定native与shader未改。用户145只读证据与新系统交接分别见 [平台事实](<alive-platform.md>)、[SystemUI边界](<alive-systemui.md>)。code146源码修正不是新包真机视觉PASS。

接入基于 [OpenAlive 固定提交 2d54c72](<https://github.com/Maga-King/OpenAlive/tree/2d54c72a573b2f66e20b558283bef58cdb0ada60>) 的 MIT Java 动画/矩阵/遮罩解码及照片渲染分支，不是 Flyme 官方服务移植，不声称原版系统时序、性能或其他 ROM 兼容。复制源码保留 [完整 MIT 许可](<../../app/src/full/java/org/aliveclean/LICENSE>)。

用户通过 [AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>) 本地导入固定 OpenAlive 发布 APK，验证 SHA/ABI/白名单后取得只读 JNI、装饰图、玻璃纹理及五套 RGB 遮罩；不执行外来 DEX，不随模块重新分发 native/素材，不生成占位渐变冒充就绪。JNI 内嵌着色器不在本适配重新编译。

| 阶段 | 当前选择 |
| --- | --- |
| AOD | 0启航（独立 lens 路径），1留光、2星月、3轻启、4山脉、5银河（照片遮罩路径） |
| 锁屏 | 0无、1长虹、2波浪、3雾花 |
| 桌面 | 6无、7长虹、8波浪、9雾花 |

锁屏/桌面独立配置，默认0/6。不自创“流沙”或加入其他厂商引擎。启航不创建 FrameMotion/MaskFrames，使用 SceneMotion lens 与真实 decorator；雾花按需加载 ground gray/mask，绑定 native slots1/2，照片遮罩占slot0。

## 单页编辑、缩略图与应用

[AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>) 用不可变 aod/lock/home/photo Snapshot 分离 draft/applied；导入照片使用 UUID 文件，不覆盖现用照片，保留旧style/照片兼容。实际 Engine 读 applied，系统预览读 draft，页内预览接收显式 Snapshot，预览错误不污染实际服务状态。

[编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)顶部两/三张真实阶段卡片，点卡片打开仅当前样式的底部Sheet，无阶段切换/完成按钮；点选保存草稿后更新卡片。照片入口在外层，资源入口收至更多。设置成功返回HOME并仅移除本模块task；取消/失败保留草稿，系统选择器与同组件直接发布边界见[平台文档](<alive-platform.md>)。

code145 的卡片使用固定上游 **10张 PNG**：六张 AOD、四张锁屏纹理图；桌面复用四种纹理示意。它们不是当前用户照片实时缩略渲染。importer 对固定APK整体 SHA 校验后，分别校验每张白名单PNG的独立SHA与≤160000字节上限，存入私有可选 `previews-v1`，不 bundled。旧导入缺图时可重新选择同一固定 APK 补导；缺图不改变 native ready，不阻止真实渲染，也不拿假图冒充。缩略资源独立于不可变 native generation，**补图不替换已发布/已加载原生库**，不切换同进程 JNI 世代。

[AlivePreview](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)每张阶段卡片使用独立TextureView与[同一renderer类型](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)，显式Snapshot输入，按对应静态端点显示；不是录像或HTML示意。原TextureView不接管点击/无障碍焦点，外层卡片处理样式编辑入口；无背景循环切阶段或idle演示。更换配置reload，真实壁纸的唤醒/收拢动画轨不变。

预览跟随 STARTED 生命周期，停止/离页时异步close；EGL断开native window的 completion 返回后，才释放准确对应的 Surface/SurfaceTexture。关闭中不抢建renderer，TextureView销毁返回false后由对应回调释放，避免BufferQueue race。错误检查仅事件后250/500/1000ms三次。页内卡片不模拟物理DOZE亮度、系统遮挡/认证或时钟布局。

## worker、ABI 与资源生命周期

- GLES3/EGL、动画与 native handle 仅由 `AliveGL` HandlerThread 持有；遮罩另用 `AliveMask`，720px最多4、1080px最多2个复用RGB buffer；照片worker解码，长边最多2048。
- [SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>) / [FrameMotion](<../../app/src/full/java/org/aliveclean/FrameMotion.java>) / [TextureMotion](<../../app/src/full/java/org/aliveclean/TextureMotion.java>) 输出60/36/20 **float**（240/144/80字节），native-order direct ByteBuffer，不是同数字节数。
- [FrameSample](<../../app/src/full/java/org/aliveclean/FrameSample.java>)冻结形态/纹理/遮罩序号，精确解码后提交，不追逐新采样或忙轮询；等待样本提交后再取端点，稳定端点停vsync，启航idle也有限时。
- [NativeScene](<../../app/src/full/java/org/aliveclean/NativeScene.java>)仅加载 importer canonical 私有只读绝对路径；R8保留JNI/反射属性入口，所有照片分支上传真实decorator满足native ABI，不用0/占位图。
- 宿主拥有Surface；renderer只释放自身纹理/native/EGL，不擅自release宿主Surface，共享默认EGLDisplay不由单引擎eglTerminate。

## 睡眠与静态端点交接

等待native visible最多6秒，隐藏不初始化/绘制；无scene只用最近真实LOCK/HOME种子，没有种子静态AOD，不假HOME。完整起始帧成功swap ack后起1800ms收拢；隐藏pause三轨、停帧，恢复再续，从首次sleep信号起绝对上限仍7800ms。

[Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)保留code145的同episode `holding`：只留已存在scene，结束三轨、清待呈现样本与lens期限，按需提交静态AOD端点；无scene不为交接重建，隐藏不绘制、不延长7800ms绝限。wake/ambient进入0/OFF/UNKNOWN/SUSPEND取消，DOZE按正常事实门控衔接。code145新日志已表明首swap和端点都发生在DOZE之前且visible=true，用户仍看到原生熄屏/黑场，故不能把holding称为完整系统视觉交接。

## code146：球体混合退出与禁止补播

- [SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>)按**实际AOD混合占比**而不只逻辑mode选择启航awake轨：快速0→LOCK→HOME期间只要 `values[0]>0.000001`，仍走对应AOD展开轨，避免误走普通LOCK→HOME的tr4，把尚存decorator按默认1000ms拖尾。正常AOD展开decorator在33ms退0，沿当前采样衔接，不删原装饰图/改shader掩盖色块。实际纯HOME占比不借该条件重新采球。
- [Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)在非睡眠的隐藏/不可绘或visibility回调只提交当前端点；[AliveRenderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)无scene时不再保留AOD解锁种子。普通隐藏清待播wake/sleep回调，重建按当前mode初始化；解锁到软件后再回桌面不应补播旧解锁。只有同一有界sleep episode允许暂留现有scene并暂停三轨。
- `mode(同阶段,false)`结束三轨并清旧样本。进入ambient duration0/DOZE_SUSPEND强端点，真实可见awake场景仍可展开，不等待/修改认证。新SystemUI交接的first-swap回执来自一次真正 `eglSwapBuffers` 成功；回执本身不创建隐藏scene，也不是物理屏幕已呈现的证明。renderer失败通知Engine取消handoff，确认后停绘也取消；surface重新attach后再安排确认，不能拿已被release清掉的回调当成成功。

用户将异常描述为球体色块叠到壁纸，比“光晕”更准确；上面是代码可证的错误轨道与生命周期修正，尚无code146真机证据能宣称残留已消失。

原生AOD target黑底合成局部照片；样式1自身blur0，2为22、3..5为100是继承内容参数，不是字面输出像素半径；系统额外doze blur是另一边界。照片burn-in位移未做，时钟避让延期，只短时测试。

## 失败与验证限制

初始化/解码/native packet/EGL swap失败释放停试，不无限重建；遮罩最多三次尝试。实际服务通过error及applied backend_error报告、成功swap清除，预览独立；仅配置/资源显式变化重试，不让错误键造成reload回环。

按用户要求只保留必要生命周期/失败保护与构建、R8、签名、身份及产品边界核验，由Lead收口；本文不预报执行中的PASS/hash，不跑full suite或全功能回归。code145视觉反馈不能作为code146新交接、混合轨修正及禁止补播的真机PASS；六AOD/纹理、指纹、GPU和物理DOZE仍交用户验证，不自动安装/重启/改设置，不称全面连续、无闪或其他ROM兼容。
