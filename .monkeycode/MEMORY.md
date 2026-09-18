# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

[Project Knowledge Summary]
- Date: 2026-09-18
- Context: Discovered by Agent while troubleshooting「点击区域截图闪退」(MIUI/HyperOS, 悬浮球入口)
- Category: Troubleshooting & Debugging
- Instructions:
  - 本应用闪退排查优先看两类机制：① 捕获协程（CaptureManager.pump）内未捕获异常会直接杀死进程（Kotlin 协程默认行为）；② startForegroundService 启动的 CaptureService 若 startForeground 抛异常被吞且未 stopSelf，系统约 5 秒后以 RemoteServiceException 杀进程。
  - MIUI/澎湃对 MediaProjection 会话管控严格：第二次抓帧（setSurface 换绑）可能触发投影终止，onStop 回调在主线程 clear 队列，与捕获协程并发操作队列必须用线程安全队列（已改 ConcurrentLinkedDeque）。
  - 区域截图链路：capture → CacheStore 落盘 → startActivity(RegionCaptureActivity) → 加载底图；选区页秒关（闪一下）= 底图 decodeSampled 返回 null（文件缺失或 OOM），查缓存目录 pending/ 下文件。
  - 若用户反馈仍闪退，需索取 logcat（adb logcat -b crash）或 MIUI「服务与反馈」崩溃记录再定位。

## Entries

[Project Knowledge Summary]
- Date: 2026-09-15
- Context: Discovered by Agent while building the Android project to verify code changes
- Category: Environment Configuration
- Instructions:
  - 本开发环境默认未安装 JDK 与 Android SDK，需先准备：
    - `export DEBIAN_FRONTEND=noninteractive && apt-get install -y openjdk-17-jdk-headless unzip`
    - Android cmdline-tools 解压到 `/opt/android-sdk/cmdline-tools/latest`
    - `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`
  - 构建命令（在 /workspace 下执行）：
    - `export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
    - `sh ./gradlew assembleDebug --no-daemon --console=plain`
  - 仓库内 `gradlew` 无执行权限（mode 644），直接 `./gradlew` 会报 Permission denied，请改用 `sh ./gradlew`，不要 chmod 以免污染工作区。
  - 构建属 CPU/内存密集型任务，需通过 background terminal 执行并设置 cpu/memory 限制。

[Project Knowledge Summary]
- Date: 2026-09-15
- Context: Discovered by Agent while fixing screenshot self-overlay bug
- Category: Troubleshooting & Debugging
- Instructions:
  - 截图链路：`CaptureManager.request(ctx, mode, settleDelayMs)` → `performRequest` → `runMode`。
  - 由应用自身 UI（首页/模式选择弹窗）触发截图时，必须传入 `CaptureManager.UI_DISMISS_SETTLE_MS` 等待窗口关闭；首页还需先 `moveTaskToBack(true)`，否则会把本应用界面截入画面。
  - 悬浮球/悬浮预览属应用自身叠加窗，截图前会由 `CaptureManager` 调用 `hideForCapture()` 隐藏；长截图会话期间保持隐藏，由 `LongShotActivity.finishSession()` 调 `CaptureManager.restoreOverlays()` 恢复。
  - Android 11+ 优先走无障碍 `takeScreenshot()`（需在 `accessibility_service_config.xml` 声明 `android:canTakeScreenshot="true"`），可免除每次 MediaProjection 的「共享屏幕」授权弹窗；不可用时自动回退 MediaProjection。
