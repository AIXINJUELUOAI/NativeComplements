# Alive 照片渲染内核

## 当前范围：0.11.2-alive-test2 / code145

接入基于 [OpenAlive 固定提交 2d54c72](<https://github.com/Maga-King/OpenAlive/tree/2d54c72a573b2f66e20b558283bef58cdb0ada60>) 的 MIT Java 动画/矩阵/遮罩解码及照片渲染分支，不是 Flyme 官方服务移植，不声称原版系统时序、性能或其他 ROM 兼容。复制源码保留 [完整 MIT 许可](<../../app/src/full/java/org/aliveclean/LICENSE>)。

用户通过 [AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>) 本地导入固定 OpenAlive 发布 APK，验证 SHA/ABI/白名单后取得只读 JNI、装饰图、玻璃纹理及五套 RGB 遮罩；不执行外来 DEX，不随模块重新分发 native/素材，不生成占位渐变冒充就绪。JNI 内嵌着色器不在本适配重新编译。

| 阶段 | 当前选择 |
| --- | --- |
| AOD | 0启航（独立 lens 路径），1留光、2星月、3轻启、4山脉、5银河（照片遮罩路径） |
| 锁屏 | 0无、1长虹、2波浪、3雾花 |
| 桌面 | 6无、7长虹、8波浪、9雾花 |

锁屏/桌面独立配置，默认0/6。不自创“流沙”或加入其他厂商引擎。启航不创建 FrameMotion/MaskFrames，使用 SceneMotion lens 与真实 decorator；雾花按需加载 ground gray/mask，绑定 native slots1/2，照片遮罩占slot0。

## 全屏编辑、缩略图与应用

[AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>) 用不可变 aod/lock/home/photo Snapshot 分离 draft/applied；导入照片使用 UUID 文件，不覆盖现用照片，保留旧style/照片兼容。实际 Engine 读 applied，系统预览读 draft，页内预览接收显式 Snapshot，预览错误不污染实际服务状态。

[编辑页](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveScreen.kt>)以真实预览铺满背景，顶部返回/编辑/设置壁纸。“编辑”打开当前息屏/锁屏/桌面的底部 Sheet，直接展示样式卡片；可在 Sheet 切阶段，选择即更新草稿与背景，不再进入样式子页。设置成功返回 HOME 并仅移除本模块 task；取消/失败保留草稿，系统选择器与同组件确认边界见 [平台文档](<alive-platform.md>)。

code145 的卡片使用固定上游 **10张 PNG**：六张 AOD、四张锁屏纹理图；桌面复用四种纹理示意。它们不是当前用户照片实时缩略渲染。importer 对固定APK整体 SHA 校验后，分别校验每张白名单PNG的独立SHA与≤160000字节上限，存入私有可选 `previews-v1`，不 bundled。旧导入缺图时可重新选择同一固定 APK 补导；缺图不改变 native ready，不阻止真实渲染，也不拿假图冒充。缩略资源独立于不可变 native generation，**补图不替换已发布/已加载原生库**，不切换同进程 JNI 世代。

[AlivePreview](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)仍是一个 TextureView，使用同一 [AliveRenderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)；点击背景按 **0→1→2→0** 驱动现有 scene 的真实动画，也可直接选阶段，不是录像或三张静态端点页。更换配置才 reload；阶段反向沿当前采样衔接。启航仅有界3秒 idle 演示，不复制无限 AOD 转动。

预览跟随 STARTED 生命周期，停止/离页时异步close；EGL断开native window的 completion 返回后，才释放准确对应的 Surface/SurfaceTexture。关闭中不抢建renderer，TextureView销毁返回false后由对应回调释放，避免BufferQueue race。错误检查仅事件后250/500/1000ms三次。全屏预览不模拟物理DOZE亮度、系统遮挡/认证或时钟布局。

## worker、ABI 与资源生命周期

- GLES3/EGL、动画与 native handle 仅由 `AliveGL` HandlerThread 持有；遮罩另用 `AliveMask`，720px最多4、1080px最多2个复用RGB buffer；照片worker解码，长边最多2048。
- [SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>) / [FrameMotion](<../../app/src/full/java/org/aliveclean/FrameMotion.java>) / [TextureMotion](<../../app/src/full/java/org/aliveclean/TextureMotion.java>) 输出60/36/20 **float**（240/144/80字节），native-order direct ByteBuffer，不是同数字节数。
- [FrameSample](<../../app/src/full/java/org/aliveclean/FrameSample.java>)冻结形态/纹理/遮罩序号，精确解码后提交，不追逐新采样或忙轮询；等待样本提交后再取端点，稳定端点停vsync，启航idle也有限时。
- [NativeScene](<../../app/src/full/java/org/aliveclean/NativeScene.java>)仅加载 importer canonical 私有只读绝对路径；R8保留JNI/反射属性入口，所有照片分支上传真实decorator满足native ABI，不用0/占位图。
- 宿主拥有Surface；renderer只释放自身纹理/native/EGL，不擅自release宿主Surface，共享默认EGLDisplay不由单引擎eglTerminate。

## 睡眠与静态端点交接

等待native visible最多6秒，隐藏不初始化/绘制；无scene只用最近真实LOCK/HOME种子，没有种子静态AOD，不假HOME。完整起始帧成功swap ack后起1800ms收拢；隐藏pause三轨、停帧，恢复再续，从首次sleep信号起绝对上限仍7800ms。

code144日志证实动画窗口到期时仍ON，释放至DOZE空档1.913秒。code145由 [Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 的同episode `holding`把动画完成与buffer释放分开：`hold(visible)`只保留**已存在scene**，结束三轨、清待呈现样本与lens期限，按需呈现静态AOD端点；无scene直接返回，不为交接解码/重建，隐藏不绘制。不新增动画、不延长7800ms绝限，不把等待变无限保活。wake/ambient进入0/OFF/UNKNOWN/SUSPEND取消，DOZE按正常事实门控衔接。

`mode(同阶段,false)`仍结束三轨并清旧样本；进入ambient duration0/DOZE_SUSPEND强端点，退出duration0仍要求真实唤醒展开。隐藏/释放后0→1/2可保留AOD起点，不等待或修改认证。原生LightReveal额外淡出的窄适配见 [SystemUI边界](<alive-systemui.md>)，不是renderer控制系统。

原生AOD target黑底合成局部照片；样式1自身blur0，2为22、3..5为100是继承内容参数，不是字面输出像素半径；系统额外doze blur是另一边界。照片burn-in位移未做，时钟避让延期，只短时测试。

## 失败与验证限制

初始化/解码/native packet/EGL swap失败释放停试，不无限重建；遮罩最多三次尝试。实际服务通过error及applied backend_error报告、成功swap清除，预览独立；仅配置/资源显式变化重试，不让错误键造成reload回环。

按用户要求，本轮只做Phase/目标相关必要检查及R8/签名/身份/资源/产品边界核验，由Lead收口，本文不预报仍在运行的结果或hash，不跑full suite/全功能回归。静态交接、LightReveal投影、六AOD与纹理、全屏Sheet、应用回桌面、指纹/GPU/物理DOZE仍待用户验证；不称全面连续、无闪或其他ROM兼容已通过。
