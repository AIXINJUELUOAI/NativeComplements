# Alive 照片渲染内核

## 当前范围：0.11.2-alive-test1 / code144

接入基于 [OpenAlive 固定提交 2d54c72](<https://github.com/Maga-King/OpenAlive/tree/2d54c72a573b2f66e20b558283bef58cdb0ada60>) 的 MIT Java 动画/矩阵/遮罩解码及照片渲染分支，不是 Flyme 官方服务移植，不声称原版系统时序、性能或其他 ROM 兼容。复制源码保留 [完整 MIT 许可](<../../app/src/full/java/org/aliveclean/LICENSE>)。

用户通过 [AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>) 本地导入固定 OpenAlive 发布 APK，验证 SHA/ABI/白名单后取得只读 JNI、装饰图、玻璃纹理及五套 RGB 遮罩；不执行外来 DEX，不随模块重新分发 native/素材，不生成占位渐变冒充就绪。JNI 内嵌着色器不在本适配重新编译。

| 阶段 | 当前选择 |
| --- | --- |
| AOD | 0启航（独立 lens 路径），1留光、2星月、3轻启、4山脉、5银河（照片遮罩路径） |
| 锁屏 | 0无、1长虹、2波浪、3雾花 |
| 桌面 | 6无、7长虹、8波浪、9雾花 |

锁屏/桌面独立配置，默认0/6。历史 code140–142 未选择却固定曲面玻璃2属于错误，code143 改为原图；code144 将上游已有特效恢复为显式选项，不自创“流沙”或加入其他厂商引擎。启航不是给五种遮罩加名称：它不创建 FrameMotion/MaskFrames，使用 SceneMotion lens 参数和真实 decorator。雾花按需加载 ground gray/mask，绑定 native texture slots1/2；照片遮罩占slot0。

## 配置、真实预览与应用

[AliveSettings](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveSettings.java>) 用不可变 aod/lock/home/photo Snapshot 分离 draft/applied。导入照片使用 UUID 文件，不覆盖现用照片；保留旧 style 及旧照片兼容，新配置默认启航。实际 Engine 只读 applied；系统预览读 draft；页内预览接收显式 draft Snapshot，预览错误不污染实际服务的错误状态。应用确认与系统选择核验见 [平台边界](<alive-platform.md>)。

[AlivePreview](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>) 是 MD3 编辑页中一个 TextureView，使用与壁纸相同的 [AliveRenderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)，点击同一预览按 **0→1→2→0** 驱动现有 scene 的真实动画；也可直接选择阶段。不是录像、三张静态端点图或每点击重建独立页面。更换配置才 reload；阶段反向沿当前动画采样衔接。启航提供有界3秒 idle 演示，不复制上游无限 AOD 转动。

预览跟随 STARTED 生命周期，停止/离页时异步 close；renderer 的 close completion 在 EGL 断开 native window 后返回，再释放该 Surface/对应 SurfaceTexture。关闭中不得抢建新 renderer；TextureView 销毁返回false，待准确 texture 的关闭回调后再释放，避免 BufferQueue 先销毁的 race。错误检查仅用户/资源事件后250/500/1000ms三次，不常驻轮询。页面展示实际渲染，不模拟物理 DOZE 亮度、系统遮挡/认证或真实时钟布局，不能作为真机 AOD 验证。

## worker、ABI 与资源生命周期

- GLES3/EGL、动画和 native handle 只由单个 `AliveGL` HandlerThread 持有；遮罩另用 `AliveMask`，720px最多4、1080px最多2个复用RGB buffer。照片在 worker 解码，长边最多2048。
- [SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>) / [FrameMotion](<../../app/src/full/java/org/aliveclean/FrameMotion.java>) / [TextureMotion](<../../app/src/full/java/org/aliveclean/TextureMotion.java>) 分别输出60/36/20 **float**（240/144/80字节），native-order direct ByteBuffer，不是同数字节数。
- [FrameSample](<../../app/src/full/java/org/aliveclean/FrameSample.java>) 冻结形态/纹理/遮罩序号，精确遮罩解码后提交；不追逐新采样、不忙轮询。提交等待过的样本后再取动画端点，避免停在中间。稳定端点停止 vsync；启航 idle 也受有限期限约束。
- [NativeScene](<../../app/src/full/java/org/aliveclean/NativeScene.java>) 仅加载 importer canonical 私有只读绝对路径，同进程不得切 native 世代。R8 保留 JNI/反射属性入口；所有照片分支都上传真实 decorator 满足 native ABI，不用0或占位图。
- 宿主拥有 Surface。renderer detach/close/release 释放自身纹理/native/EGL，不擅自 release 宿主 Surface；进程共享默认 EGLDisplay 不由单引擎 eglTerminate。

## 睡眠与唤醒交接

等待 native visible 最多6秒；隐藏不初始化/绘制。可见 sleep 请求若无 scene，只接受最近真实 LOCK/HOME 种子，没有种子静态AOD，不捏造HOME。先提交完整起始帧，成功 swap ack 后才启动三轨收拢和1800ms期限；从首次sleep信号算绝对上限7800ms。暂时隐藏时三轨pause、停帧并仅保留已有scene，恢复再续，不能通过隐藏无限保活。

`mode(同阶段,false)` 仍结束三轨并清除旧待呈现样本；进入 ambient duration0/DOZE_SUSPEND 强制端点。相反，**退出 ambient duration0 仍要求真实唤醒展开**；隐藏/释放后的0→1/2可保留AOD起点种子。本轮针对指纹直接0→2跳动画的路径已改代码，未称真机复测通过。相关状态与截止由 [Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>) 管理，不由 renderer Hook 系统。

原生 AOD target 黑底合成局部照片；样式1自身blur0，2为22、3..5为100是继承的内容参数，并非字面输出像素半径。系统额外 doze 背景blur是 [另一适配边界](<alive-systemui.md>)，不能把所有模糊都归为纹理。照片锚点未接防烧屏位移，时钟避让延期，只短时测试。

## 失败与验证限制

初始化/解码/native packet/EGL swap失败释放并停试，不无限重建；遮罩最多三次尝试。实际服务通过 `error()` 和 applied backend_error 暴露错误，成功swap后清除；预览独立报告。配置/资源显式变化可重试，不让错误键造成reload回环。

已有一轮双 flavor unit 成功，但后续交接/preview race/ambient-exit改动未全测；剩余构建、签名、身份和资源边界核验不代表视觉PASS，本文不填写未产出的hash。用户要求少测试，不新增套件/全功能回归。六AOD、玻璃特效、反向转场、关闭race、真实熄屏/指纹和GPU行为仍待设备验证；不承诺原生controller/scrim已被替代或所有闪烁已消除。
