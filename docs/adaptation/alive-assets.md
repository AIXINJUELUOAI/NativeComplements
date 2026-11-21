# Alive 本地资源与发布合同

[AliveAssets](<../../app/src/full/java/dev/local/sunwindowdemo/features/alive/AliveAssets.java>)只接受用户经系统文件选择器授权的 `content:` 流。没有联网下载、root、查询已安装 OpenAlive 或执行外来 DEX 的路径。磁盘、完整性校验、照片解码均在 worker 执行。

## 固定来源与许可

运行资源来自 [OpenAlive 固定提交](<https://github.com/Maga-King/OpenAlive/tree/2d54c72a573b2f66e20b558283bef58cdb0ada60>)对应的官方 `0.4.41-preview` APK。完整输入须为 643,140,270 bytes，SHA-256 为 `99361d0275ef98f00206c9f53202efadf5686cc82011497a58c2d37a0e47bb71`；包名、文件名及重打包副本不能替代校验。此摘要是导入信任合同，不是模块交付包摘要。

- 全文件验证后，仅提取固定 252 个确切路径和长度：arm64 原生库、五套遮罩帧/start/end/manifest、三个 shader PNG；总计 29,539,884 bytes，单条最多 16MiB，数量与压缩比有界。
- 原生库为 467,592 bytes，另验 SHA-256 `43ff8838e2140f9564ac29b5cc5e73eb5dfca9696aedfc5cf4b324b899eaf027`。其他 APK 内容及 DEX 不提取。
- 十张样式示意 PNG 独立按白名单、逐文件摘要及每张 160,000 bytes 上限导入 CE 的 `previews-v1`。缺图可补导同一 APK，不改变核心资源 ready，也不替换已加载库。
- native、shader、遮罩与示意图不随模块 APK/Git 再分发。用户本地导入不消除上游许可及二进制再分发限制。复制的 Java 源码保留[完整 MIT 许可](<../../app/src/full/java/org/aliveclean/LICENSE>)。

## 不可变资源世代

核心运行资源发布到本用户应用私有 DE no-backup 目录，以固定来源摘要标识世代。独立随机暂存目录使用打开后去写权限的文件，写完 fsync、生成每文件哈希清单、完整校验并同步目录，最后 rename 发布。路径由固定白名单和 canonical 子路径检查限定，不任意拼接 ZIP 路径。

不覆盖已有发布代，不替换同进程已加载的 native 路径；失败只清本次暂存。坏的发布代显式拒绝覆盖，避免把半份资源当成就绪。旧 CE 迁移仅在已解锁 worker 中完整验证原代后，复制固定核心集合及清单到 DE；不复制额外文件，不删 CE 源。迁移与首次应用限制见[平台与数据合同](<alive-platform.md>)。

## 调用边界

| 接口 | 合同 |
| --- | --- |
| `prepare(Context, Uri)` | 验证固定 APK并发布缺少的核心/示意资源；核心成功变化以 DE `resources_revision` 通知 |
| `migrate(Context)` | DE 已好则快返；未解锁不访问 CE；不覆盖任何已发布代 |
| `ready(Context)` | 首次校验清单、全部长度/SHA、普通文件/当前 UID/只读权限及 arm64 64位进程；缓存命中仍验长度/权限 |
| `library(Context)` | 返回 canonical 私有只读库给 `System.load`；ready 不是 JNI/ELF 链接或 GPU 成功保证 |
| `open(Context, path)` | 只开放核心白名单或固定示意图；首次可能完整校验，必须 worker |
| `importPhoto(Context, Uri)` | 输入最多 32MiB，按比例解码到最长边 4096、800 万像素以内并重编码 JPEG；失败保留旧照片 |

新编辑照片采用 UUID 文件名，不覆盖正在使用的照片；不持久化外部 URI、原路径或图片元数据。保留宽高比，取景以归一化中心与缩放保存在配置。已应用照片先不可变复制至 DE，再原子发布快照；锁前隐私、历史照片保留及故障处理见[平台合同](<alive-platform.md>)。
