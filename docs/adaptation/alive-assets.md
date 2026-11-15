# Alive 本地资源接入

[AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)仅存在 full 源集，接受用户通过系统文件选择器授予的 `content:` 文件流；没有联网下载、root、查询已安装 OpenAlive 或执行外来 DEX 的路径。

## 固定来源与发布

用户必须自行提供 OpenAlive `0.4.41-preview` 官方发布 APK（上游 commit `2d54c72`）。本地导入先限制完整输入为 **643,140,270 bytes**，验证官方发布全文件 SHA-256 `99361d0275ef98f00206c9f53202efadf5686cc82011497a58c2d37a0e47bb71`；不以包名、文件名或重新打包副本作为可信依据。

完整 APK 验证之后，仅提取代码内固定的 **252 个确切名字与长度**：arm64 原生库、五组 mask 的所有帧／start／end／manifest，以及三个 shader 用 PNG；合计 29,539,884 bytes。单条最大 16MiB、条目数及压缩比有界，不使用任意 ZIP 路径拼接或通配提取。库固定 467,592 bytes，另验 SHA-256 `43ff8838e2140f9564ac29b5cc5e73eb5dfca9696aedfc5cf4b324b899eaf027`。其余 APK 内容包括 DEX 一律不解出。

导入写独立随机暂存目录，打开文件描述符后即去掉写权限，再写入、同步并生成每文件哈希清单。所有文件就绪后 rename 发布至私有 no-backup 固定 digest 世代，不覆盖已发布路径，避免替换已经加载的库。失败删除本次暂存，不清理现有世代。坏的已发布世代显式拒绝重新覆盖，提示用户清除本功能数据并重启，不偷偷覆盖活跃代码。库和资源是本地导入，不随本模块 APK 或 Git 重新分发；上游的许可／原生二进制再分发限制仍需独立遵守。

## 调用合同

- `prepare(Context, Uri)`：worker 调用，失败抛 `IOException`；重复调用已就绪状态无操作。成功新发布写 `alive_wallpaper/resources_revision`，供活动壁纸刷新。
- `ready(Context)`：首次每进程校验完整清单、全部文件长度及 SHA、普通文件／当前 UID／无写权限，并要求 arm64 64 位进程；已校验状态仍检查长度与文件权限。**这是资源／ABI 就绪，不是 JNI 链接或渲染成功保证。**
- `library(Context)`：返回私有只读库文件，供保留上游 JNI 包名的渲染器 `System.load`；加载及 GPU/ROM 失败必须显式报告。没有把 Android 只读 DEX 要求泛化为所有 native 路径拒绝，也不假称 private native 一定可加载。
- `open(Context, "masks/..." 或 "shader/...")`：只允许固定资源路径。第一次仍可触发全校验，务必 worker；缓存后只检查目标资源。不返回任意私有文件或代码。
- `photo(Context)`：固定用户 JPEG 路径，缺失就保持缺失，不生成渐变或其他占位冒充照片。
- `importPhoto(Context, Uri)`：worker 限输入 32MiB、图像尺寸／总像素，采样到最长边不超过 2048，重新编码 JPEG 后 AtomicFile 替换。失败保留旧照片；成功写 `photo_revision` 触发重新读取。没有把图片元数据、原路径或外部 URI 持久化。

首次完整检查约读取 29.5MB，不能放 renderer 构造的主线程。资源验证不等于目标设备运行验证；JNI ABI、ELF／链接环境、GPU、图片实际方向与屏幕裁剪由执行侧核验。导入保留照片宽高比，屏幕 fill/crop 属渲染器，不在存储阶段按猜测的显示器比例裁掉用户内容。
