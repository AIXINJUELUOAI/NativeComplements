# NativeComplements 优化补丁包

这个目录包含 6 个独立的优化补丁,每个都可以单独应用和测试。

## 补丁列表

### 1. `0001-fix-keep-native-fullscreen.patch`
**修复全屏应用被强制转小窗**
- 问题: 从小窗启动已在前台全屏运行的应用时,会被强制转成小窗
- 修复: 实时检查目标是否已是全屏前台,如果是则保留其全屏状态
- 影响: WindowEngine.activityStarting()

### 2. `0002-perf-fast-return-hooks.patch`
**热路径 Hook 快速返回优化**
- 问题: 没有小窗时,所有 Hook 仍执行反射和加锁操作
- 优化: 在 9 个热路径 Hook 入口添加 sessions.isEmpty() 快速返回
- 影响: blockContentTouch, keepVisibleResumed, preserveImmersive 等 9 个方法
- 效果: 无小窗时 Hook 开销降至一次 volatile 读取

### 3. `0003-perf-reduce-polling.patch`
**优化 SystemUI 心跳轮询策略**
- 问题: SystemUI 每 750ms 发广播,每天 46 万条,熄屏时也不停
- 优化: 熄屏时暂停轮询,无会话时延长到 3000ms(减少 75%)
- 影响: SystemUiHost 轮询逻辑和屏幕广播接收器
- 效果: 显著降低待机功耗和系统唤醒频率

### 4. `0004-perf-cache-reflection.patch`
**缓存热路径反射调用**
- 问题: 每次反射都拼接字符串,所有查找经过 synchronized
- 优化: 
  - Transaction 公开 API 改为直接调用(每帧减少约 20 次反射)
  - 缓存 9 个常用 Method 和 5 个常用 Field
  - call() 和 field() 添加快速路径
- 影响: FrameworkAccess 核心反射机制
- 效果: 每帧减少约 30 次字符串拼接和锁竞争

### 5. `0005-build-enable-r8.patch`
**启用 R8 代码优化和混淆**
- 问题: APK 23MB,dex 22MB 完全未混淆,加载到 3 个系统进程
- 优化: 
  - 启用 isMinifyEnabled = true
  - 完整的 ProGuard 规则保护 Xposed 入口和反射目标
  - 保留行号便于调试
- 影响: build.gradle.kts 和新增 proguard-rules.pro
- 效果: 预期 dex 缩小到 5-8MB,三个系统进程内存占用显著降低
- **重要**: 需要真机完整测试所有功能

### 6. `0006-fix-window-focus-steal.patch`
**修复小窗焦点抢占问题**
- 问题: 小窗打开动画期间,后台应用仍可响应触摸
- 修复:
  - blockContentTouch() 增加 entering/transitioning 检查
  - 小窗 entering 时屏蔽所有后台任务的触摸
  - activate() 中立即设置焦点,不等动画结束
- 影响: WindowEngine.blockContentTouch() 和 activate()
- 效果: 小窗打开时后台应用完全无法响应触摸

## 如何应用补丁

### 方法 1: 在电脑上应用 (推荐)

1. 把这个目录复制到电脑上
2. 在 NativeComplements 仓库根目录执行:

```bash
# 应用所有补丁
git am /path/to/patches/*.patch

# 或者逐个应用
git am /path/to/patches/0001-fix-keep-native-fullscreen.patch
git am /path/to/patches/0002-perf-fast-return-hooks.patch
# ... 依次应用其他补丁
```

### 方法 2: 在手机上应用

在 `/root/workspace/NativeComplements` 目录执行:

```bash
git am /sdcard/Download/DSH-Go/patches/*.patch
```

### 应用后验证

```bash
# 查看提交历史
git log --oneline -6

# 编译测试
./gradlew.bat --no-daemon :app:assembleRelease

# 运行单元测试
python tests/verify_runtime.py
```

## 推荐应用顺序

1. **先应用修复类补丁** (0001, 0006)
   - 测试小窗打开和切换功能
   - 验证焦点行为正常

2. **再应用性能优化补丁** (0002, 0003, 0004)
   - 观察系统流畅度
   - 检查待机功耗

3. **最后应用 R8 构建优化** (0005)
   - 完整测试所有功能
   - 检查是否有混淆导致的问题

## 如果遇到问题

### 撤销某个补丁

```bash
# 撤销最后一个提交
git reset --hard HEAD~1

# 撤销多个提交
git reset --hard HEAD~3  # 撤销最近 3 个提交
```

### 重新从 main 开始

```bash
git checkout main
git branch -D fix/keep-native-fullscreen perf/fast-return-hooks \
  perf/reduce-polling perf/cache-reflection build/enable-r8 fix/window-focus-steal
```

## 技术细节

所有补丁都基于 origin/main

- 分支已在本地创建,可用 `git branch -a` 查看
- 每个分支只包含一个提交,便于 review 和测试
- 提交信息包含详细的问题描述、修复方案和影响范围

---

生成时间: 2026-11-13
生成工具: Kiro AI Assistant
