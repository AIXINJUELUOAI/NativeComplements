# Alive 照片渲染内核

## 来源与边界

本接入使用 [OpenAlive 固定提交 2d54c72](<https://github.com/Maga-King/OpenAlive/tree/2d54c72a573b2f66e20b558283bef58cdb0ada60>) 的 MIT Java 照片动画/矩阵/遮罩解码与 RenderLoop 照片分支，不是 Flyme 官方壁纸服务或 APK 的移植。OpenAlive 是独立复刻实现：Java 动画输出参数，Rust GLES 图执行照片、锁屏曲面玻璃、色键遮罩和合成；即使其参数/着色器源自对原版的研究，也不能据此声称与 Flyme 原版效果、系统时序或性能完全一致。原版 Flyme 与其他厂商引擎、系统时钟 Hook、官方 UI 均未接入。

复制的源码保留版权归属与 [完整 MIT 许可](<../../app/src/full/java/org/aliveclean/LICENSE>)。用户须通过本地导入精确版本的 OpenAlive 发布 APK，由 [AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)验证 SHA/ABI/资产白名单后取得只读不可变 JNI 文件及五套 RGB 遮罩；APK/native/遮罩不随本模块重新分发。JNI 内嵌着色器使用原发布构建，不由本适配重新编译；未导入或未选照片不生成默认渐变冒充就绪。

## 宿主接入

[AliveRenderer](<../../app/src/full/java/org/aliveclean/AliveRenderer.java>)提供 `AliveRenderer(Context)`、`attach(Surface,w,h)`、`mode(scene,animate)`、`visible(boolean)`、`reload()`、`detach()`、`close()` 与线程安全 `error()`。scene 为 0=AOD、1=锁屏、2=桌面；`alive_wallpaper/style` 独立选择 1..5（默认留光），锁屏固定曲面玻璃 2、桌面固定原图 6。Surface 归宿主所有，渲染器不 release 宿主 Surface。服务负责实际显示、锁屏和偏好事件，不由渲染器 Hook 系统。

- 所有 GLES3/EGL、动画及 native 资源仅单个 `AliveGL` HandlerThread 持有；遮罩另一个 `AliveMask` 工作者，720px 至多 4、1080px 至多 2 个复用 RGB buffer。用户照片在 worker 解码并限制长边 2048。
- [SceneMotion](<../../app/src/full/java/org/aliveclean/SceneMotion.java>)输出 60 **float**（240 字节），[FrameMotion](<../../app/src/full/java/org/aliveclean/FrameMotion.java>)输出 36 float（144 字节），[TextureMotion](<../../app/src/full/java/org/aliveclean/TextureMotion.java>)输出 20 float（80 字节），native-order direct ByteBuffer。这是 JNI ABI，不是 60/36/20 字节。
- [FrameSample](<../../app/src/full/java/org/aliveclean/FrameSample.java>)冻结一帧的形态、纹理和遮罩序号，等对应 RGB 解码完成再提交；不能等待遮罩时继续追逐新样本。解码完成回 GL worker 呈现，不忙轮询。消费等待过遮罩的旧采样后必须额外调度一帧，重新采样期间已结束的动画终点，避免停在中间态。稳定端点停止 vsync，真实 Android 动画活跃时继续帧回调。
- 不可见/脱离/关闭停止帧，取消动画、关闭遮罩 worker，释放自有 texture/native/EGL surface/context；默认 EGLDisplay 在同进程多个壁纸引擎间共享，不由单引擎 `eglTerminate`，保活至进程退出由系统回收。不可见期间收到 0→1/2 唤醒转场会记录 seed=0，重建后先建立 AOD 状态再真正启动三组动画；普通应用遮挡后的 2→2 不伪造睡眠。仍存活场景的反向转场沿用当前动画采样。
- [NativeScene](<../../app/src/full/java/org/aliveclean/NativeScene.java>)只从 importer 给出的 canonical 私有只读绝对路径 `System.load`；同进程不得切换 native 世代。R8 必须保留 JNI 类/方法及 ObjectAnimator 属性入口。五种 chromakey 照片着色分支不采样 lens decorator，但 native 总入口仍要求非零装饰纹理；必须从本地导入的真实 `lens_decorator.png` 解码上传并传入，不能用 0 或占位纹理规避 ABI，释放时同步删除。

## 失败与证据限制

初始化、遮罩解码（最多三次）、native packet 或 EGL swap 失败时释放资源并停试，不无限重建；`error()` 与 `alive_wallpaper/backend_error` 暴露错误，只在值变化写偏好，第一次成功 swap 后清除。用户更换资源/照片/样式触发显式 reload 才重试；缺资源时不是渲染成功，也不以占位图掩盖失败。`photo_revision/resources_revision/style` 变化由服务转交 reload，错误键不应触发 reload 回环。

Java/R8/协议及本地导入检查不能证明 native 驱动和系统 AOD 生命周期效果。当前仍需实际设备验证：五样式遮罩、锁屏/指纹/桌面与中途反向转场、不可见回收、失败恢复及 GPU/native 行为；不声称 Flyme、LOS 或其他 ROM 兼容。
