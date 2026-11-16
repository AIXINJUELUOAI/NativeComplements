# Alive 渲染与资源生命周期合同

实现基于 [OpenAlive 固定提交](<https://github.com/Maga-King/OpenAlive/tree/2d54c72a573b2f66e20b558283bef58cdb0ada60>)的 Java 动画/矩阵/遮罩解码及照片分支，保留[完整 MIT 许可](<../../app/src/full/java/org/aliveclean/LICENSE>)。不是 Flyme 系统服务移植，不承诺原版系统时序/性能。native 与素材经[本地固定来源导入](<alive-assets.md>)，不执行外来 DEX，也不修改 JNI 内嵌 shader。

## 场景与线程

[AliveRenderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)由 `AliveGL` HandlerThread 独占 GLES3/EGL、动画与 native handle；照片在 worker 解码，最长边2048。遮罩 `AliveMask` 使用复用 RGB buffer（720px 最多4个、1080px 最多2个）。

| 阶段 | 选择 |
| --- | --- |
| AMBIENT | 0启航 lens；1留光、2星月、3轻启、4山脉、5银河照片遮罩 |
| LOCK | 0无、1长虹、2波浪、3雾花 |
| HOME | 6无、7长虹、8波浪、9雾花 |

LOCK/HOME 默认0/6，独立配置。启航不创建 FrameMotion/MaskFrames；雾花按需加载对应纹理，照片遮罩 slot0，雾花 slots1/2。每条照片分支上传真实 decorator，不以0/占位纹理假装 ABI 完整。

[SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>)、[FrameMotion](<../../app/src/full/java/org/aliveclean/FrameMotion.java>)、[TextureMotion](<../../app/src/full/java/org/aliveclean/TextureMotion.java>)输出60/36/20 **float**，即240/144/80 bytes 的 native-order direct ByteBuffer。[FrameSample](<../../app/src/full/java/org/aliveclean/FrameSample.java>)冻结形态/纹理/遮罩序号，精确解码后提交，不追逐新采样或忙轮询；稳定端点停 vsync，lens idle 有期限。

## 睡眠、唤醒与隐藏

[Engine](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveWallpaperService.java>)采样平台事实，[SleepWindow/WakeWindow](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePhase.java>)限制生命周期，而非授权认证或强制显示：

- 首次 sleep 信号开启 episode；重复不续期。等待原生可绘最多6000ms，完整起始帧成功 `eglSwapBuffers` 后才启动三轨收拢及1800ms窗口；总绝限7800ms。临时隐藏暂停三轨/帧，恢复续播仍受绝限约束。
- `holding` 只保已存在 scene 的 AMBIENT 端点以衔接原生 ON→DOZE，不专为 holding 初始化，不延长绝限。静态 AMBIENT 回执也必须等真实 swap。
- 非 preview 的 idle scene 在应用遮住壁纸时可保留，停止帧与传感器；Surface 销毁、资源 reload、Engine close 释放。**隐藏不等于释放，也不是所有隐藏路径绝不初始化/绘制。**默认屏自有 sleep 起始可 `prewarm` 构建/复用 awake scene并提交起始帧，以替换旧 buffer；这不能替代真正 visible/有效 Surface/swap 的交接确认。
- visibility 隐藏 grace 为150ms；超出后按真实隐藏收尾。成功 sleep 同 render epoch 的 wake 短空档可保现有 scene，首 wake 固定最多2000ms、不重复续期；这是资源保留期限，不是唤醒延时或动画时长。新 sleep、解锁、非ON或资源/表面失效清资格；visible 到期只撤资格，不强停正在展开的动画。
- 明确真实 native wake 可从 AMBIENT 冷种子开始，目标跟随真实锁定选择 LOCK/HOME；同轮 LOCK→HOME 沿当前值切换，不重复 seed。普通隐藏恢复只同步当前端点，不借旧 wake 补播。
- OFF/UNKNOWN/移除与 DOZE_SUSPEND 结束 sleep；SUSPEND 仍可按正常 ambient 事实显示静态端点。ambient duration0仅在已有有效 PREPARE/active/defaultON/非交互时保留有界等待，不新建/续期 episode。

快速 AMBIENT→LOCK→HOME 时按实际 AMBIENT 混合占比选择展开轨，不以逻辑 mode 误走普通 LOCK→HOME 的装饰长尾；纯 HOME 不重新采球。AMBIENT 的固定 `anchorY=.05` 对应中心45%屏高，LOCK/HOME 仍居中；不是动态时钟避让。样式自身模糊与 SystemUI doze blur 是不同贡献，不混写为全局去模糊。

## 页面预览

[AlivePreview](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreview.kt>)每卡独立 TextureView、GL worker/EGL/native handle，接显式 Snapshot 并显示静态端点；不后台循环阶段，不模拟认证、时钟布局或物理 DOZE。错误按阶段聚合，健康卡不清空失败卡。

[缓存](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AlivePreviewCache.kt>)仅进程内真实成功帧，≤4MiB/12项、长边512，完整 Snapshot/phase/DE资源 revision/尺寸匹配并 LRU 淘汰。不持有 Activity、不写磁盘、不常驻GL；冷进程/未命中仍需首次加载。

submitted+TextureUpdated 后绘制外主线程最多16/50/150ms三次小图读回，先同步消费再核 timestamp；ticket/renderer/texture/key/active/error 同代且新帧确认后才撤 cover，不以全黑像素判断失败，超时明确错误而不露旧 buffer。停止/dispose取消 pending；关闭 EGL 的 completion 返回后才释放准确 Surface/SurfaceTexture，不抢建新 renderer。

## 失败与所有权

[NativeScene](<../../app/src/full/java/org/aliveclean/NativeScene.java>)只加载 canonical 私有只读库；R8 保留 JNI/反射入口。宿主拥有 Surface，renderer 只释放自己的纹理/native/EGL，不擅自 release 宿主 Surface，也不 `eglTerminate` 共享默认 display。

初始化、解码、native packet、swap失败释放并停试，遮罩尝试有上限；仅配置/资源显式变化重试，不让 error键造成 reload回环。实际服务报告 backend error，成功swap清除；预览状态独立。

scene保留/预绘是内存与首帧的明确取舍，不保证零额外内存/功耗。照片防烧屏位移未实现；真实 GPU、物理 DOZE、RenderThread时间戳与视觉连续性不能由主机测试证明，不宣称零闪或全ROM兼容。
