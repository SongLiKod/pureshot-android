# 净截 · PureShot

轻量、无广告、权限克制的原生 Android 专业截图编辑工具。

- 包名：`com.pureshot.screenshot`
- 语言：Kotlin（无第三方图片处理依赖，仅 ML Kit 端侧 OCR）
- 架构：单 Activity + 多 Fragment，MVVM 分层（View → ViewModel/Fragment → Manager → Util → Core）
- minSdk 29 / targetSdk 34 / compileSdk 34 / JDK 17 / AGP 8.2.2 / Kotlin 1.9.22 / Gradle 8.2

> 产品与需求背景见 [docs/净截PureShot-安卓截图工具全套官方文档.md](docs/净截PureShot-安卓截图工具全套官方文档.md)。

## 功能总览

### 五种截图模式

`CaptureMode.FULL / APP / REGION / DELAY / LONG`，首页卡片、通知栏磁贴、悬浮球、自动化 Intent 四个入口共用同一条捕获链路。

| 模式 | 实现要点 | 关键文件 |
| --- | --- | --- |
| 全屏截图 | MediaProjection + 常驻 VirtualDisplay 取帧 | `core/capture/CaptureSession.kt` |
| 应用截图 | Android 14+ 反射原生窗口捕获；10–13 自动裁切状态栏/导航栏，预览页提示可二次微调 | `core/capture/AppCaptureHelper.kt`、`core/util/SystemBars.kt` |
| 区域截图 | 先截全屏缓存为静态底图，再自由框选（8 手柄缩放、整块移动、三分网格） | `core/capture/RegionCaptureActivity.kt`、`RegionSelectorView.kt` |
| 延迟截图 | 有悬浮窗权限走倒计时悬浮提示；无权限则「先授权、后倒计时」 | `core/capture/CountdownOverlay.kt` |
| 长截图 | 半分辨率帧采样 + 行哈希重叠识别拼接；可选无障碍自动滚动 | `core/longshot/LongShotEngine.kt`、`FrameStitcher.kt` |

### 捕获调度与会话复用（`core/capture/CaptureManager.kt`）

- 首次截图由独立的真实前台页 `ConsentActivity` 承载系统授权，授权结果**就地**创建 `MediaProjection`，授权令牌不跨组件传递（规避 MIUI 等 ROM 兼容问题）。
- 之后投影保活复用：一点即截、无需重复授权；空闲超过保活时长（默认 5 分钟，可设 1–30）自动释放；用户在系统托盘点「停止共享」由 `MediaProjection.Callback.onStop` 立即回收。
- 单次授权内只维护**一个** `VirtualDisplay`，靠 `setSurface()` 重绑强制推帧；反复创建/销毁显示会被部分 ROM 判定为终止会话（表现为「第二次点击截不到图」）。
- Android 14+ 发起授权前先启动 `mediaProjection` 类型前台服务 `CaptureService`；服务启动失败不阻断（低版本无需 FGS）。
- 串行队列 `pump()` 消费待执行任务；捕获前隐藏应用自身叠加窗（悬浮球 / 悬浮预览）并等待窗口刷新，避免残影入画；授权完成后额外等待 1.2s 让系统「共享屏幕」弹窗消散。
- 全界面增强截取（可选，默认关闭）：仅小米 / 鸿蒙走厂商底层接口反射尝试，可截到系统对话框与输入法，失败静默回退标准捕获，不破解 `FLAG_SECURE`。见 `core/capture/RomEnhancedCapture.kt`、`core/util/RomUtils.kt`。

### 结果分发（`core/capture/ResultDispatcher.kt`）

统一产出三个出口：右下角悬浮预览（6s 自动消失，点击进入预览页）、结果通知（编辑 / 保存 / 分享 / 删除快捷操作）、可选原图自动保存。大图经 `CacheStore`（`cacheDir/pending`，24h 清理）以文件路径中转，规避 Binder 传输限制。

### 图层化编辑器（`ui/editor/`、`core/editor/`）

四层画布：原图层 / 马赛克层 / 标注层 / 裁剪蒙版层。所有元素独立存储、独立绘制、可单元素删除，**原图永久零损坏**；全局撤销重做基于元素快照（上限 60 步）。

- 工具：选择、裁剪、马赛克（矩形/圆形/自由涂抹/高斯模糊 + 橡皮擦局部恢复）、数字序号（自动递增、圆角/圆/方三种底色）、箭头（单双向、箭头大小、端点二次编辑）、形状（矩形/正方形/椭圆/圆/直线/多边形，空心或半透明填充）、文字、画笔、荧光笔、导出。
- 裁剪层：自由框选、比例预设（1:1 / 4:3 / 16:9 / 9:16 / 2:1 …）、旋转 90°、水平/垂直翻转、三分网格、自动裁黑边、±10° 水平校正；确认后烘焙进底图并用变换矩阵同步映射所有元素坐标。
- 参数记忆：颜色、线宽、字号、箭头大小按 `Prefs` 持久化（可关闭）。
- 马赛克/模糊底图按需生成并缓存，闲置即释放，避免 OOM（`core/editor/MosaicCache.kt`）。

### 全格式导出（`core/export/ExportManager.kt`）

`webp`（默认，画质体积平衡）/ `png` / `jpg` / `jpeg` / `svg` / `ico`，另有 GIF 多图合成。

- 导出范围三档：全图（含裁剪）、未裁剪原图（元素经累积矩阵逆变换映射回原图空间）、仅标注图层。
- 高级参数：JPG 质量 60/80/100、WebP 独立质量档（默认 90）、导出缩放 50/75/100（大图采样压缩）。
- SVG 单独导出矢量标注图层，可二次编辑；ICO 多尺寸（16/32/48/64，PNG 压缩条目）；GIF 为自研无依赖 GIF89a 编码器（1–24 fps、循环次数、最长边 720px）。
- 存储：SAF 自定义目录 → MediaStore（`Pictures/PureShot`）→ 关闭相册显示时写应用专属目录；文件名模板支持 `{date} {time} {ts} {w} {h}`。

### 其他能力

- 图库管理：本地截图浏览、多选删除、分享、GIF 合成（`ui/gallery/`）。
- OCR 文字提取：ML Kit 中文模型，全程离线、无网络权限（`core/util/OcrHelper.kt`）。
- 三主题：跟随系统（默认）/ 浅色 / 深色，品牌绿 `#009458` / `#34B97C` / `#007244` 全局硬编码（`core/theme/ThemeManager.kt`）。
- 折叠屏适配：`androidx.window` 动态网格重排（首页/相册列数随宽度变化）。
- 触发入口：QuickSettings 磁贴（单击模式选择、长按进应用）、悬浮球（可选）、自动化 Intent API。
- 全流程异常兜底与友好提示，避免崩溃（`core/util/ErrorReporter.kt`）。

## 自动化 Intent API

`api/CaptureApiActivity`（exported）供 Tasker / 系统快捷指令调用，仍按需申请媒体投影授权，不外泄任何界面数据：

```bash
am start -a com.pureshot.screenshot.action.CAPTURE --es mode full|app|region|delay|long
# 或直接使用对应 action
com.pureshot.screenshot.action.CAPTURE_FULL / _APP / _REGION / _DELAY / _LONG
```

## 权限说明（最小权限原则）

| 权限 | 必要性 | 用途 |
| --- | --- | --- |
| `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`(≤32) | 必选 | 图库读取、导出写入 |
| MediaProjection 授权 | 必选（按需弹窗） | 屏幕捕获，用完即释放，非常驻 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 必选 | Android 14 创建虚拟屏前置条件 |
| `SYSTEM_ALERT_WINDOW` | 可选 | 悬浮球、截图后悬浮预览、倒计时提示 |
| `POST_NOTIFICATIONS` | 可选 | 截图结果通知 |
| 无障碍服务 | 可选（默认关闭） | 长截图自动滚动；`canRetrieveWindowContent=false`，不读取界面内容 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 可选 | 厂商省电下保持触发可用 |

明确不申请：麦克风、位置、通讯录、网络、广告追踪等一切无关权限。

## 目录结构

```
app/src/main/java/com/pureshot/screenshot/
├── MainActivity.kt              # 单 Activity 容器 + 底部导航 + Intent 分发
├── PureShotApp.kt               # 初始化 Prefs / AppContext / 主题 / 缓存清理 / 悬浮球
├── api/CaptureApiActivity.kt    # 外部 Intent 触发截图
├── core/
│   ├── Prefs.kt                 # SharedPreferences 全量配置与默认值
│   ├── capture/                 # 授权、投影会话、五种模式调度、结果分发、缓存中转
│   ├── editor/                  # EditDocument（图层+撤销栈）、元素模型、马赛克缓存
│   ├── export/                  # 导出、GIF/ICO/SVG 编码器
│   ├── longshot/                # 帧采样引擎、拼接算法、无障碍自动滚动
│   ├── theme/                   # 三主题切换
│   └── util/                    # 位图处理、命名、OCR、权限、ROM 识别、折叠屏、异常上报
├── tile/ScreenshotTileService.kt
└── ui/
    ├── home/ gallery/ settings/ # 首页、图库、设置中心
    ├── editor/                  # 编辑工具栏、图层画布、裁剪蒙版
    ├── floating/                # 悬浮球服务
    ├── longshot/                # 长截图页（双引擎容错、接缝微调）
    └── preview/                 # 预览页、悬浮预览、结果通知
```

## 构建

### 本地

环境要求：JDK 17、Android SDK（Platform 34、Build-Tools 34.0.0）。

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17

./gradlew assembleDebug      # Linux/macOS（若提示权限：chmod +x gradlew 或 sh ./gradlew）
gradlew.bat assembleDebug    # Windows
./gradlew assembleRelease    # 未配置 keystore.properties 时产物为未签名 APK
```

产物：`app/build/outputs/apk/`。

Release 签名：在仓库根目录创建 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=pureshot-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Release 构建开启 R8 混淆与资源压缩；混淆保留规则见 `app/proguard-rules.pro`（保留 `core.export` 下自研编码器）。

### CI

`.github/workflows/release.yml`（手动 `workflow_dispatch` 触发）：

- 不填 `tag`：仅构建 debug APK 做编译验证，上传 artifact。
- 填 `tag`：校验签名 Secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）→ 解码 keystore → 生成 `keystore.properties` → `assembleRelease` → 打 Git tag → 创建 GitHub Release 并附带 `PureShot-<tag>.apk`。

## 配置默认值

集中定义于 `core/Prefs.kt`：

| 分组 | 项 | 默认值 |
| --- | --- | --- |
| 主题 | 主题模式 | 跟随系统 |
| 截图 | 延迟秒数 | 3（可 1–30） |
| 截图 | 投影保活分钟 | 5（可 1–30） |
| 截图 | 全界面增强截取 | 关闭 |
| 截图 | 无障碍自动滚动长截图 | 关闭 |
| 截图 | 悬浮球 / 悬浮预览 | 关 / 开 |
| 编辑 | 马赛克大小 / 模糊强度 | 中 / 中（块 8/16/24，半径 10/20/32） |
| 编辑 | 记住上次参数 | 开 |
| 导出 | 默认格式 / WebP 质量 / JPG 质量 | webp / 90 / 80 |
| 导出 | 导出缩放 / 导出范围 | 100% / 全图（含裁剪） |
| 导出 | 保存目录 / 相册显示 / 原图自动保存 | `Pictures/PureShot` / 开 / 关 |
| 导出 | 命名模板 | `PureShot_{date}_{time}` |

## 已知取舍

- 区域截图采用「先截全屏再框选」，因此首次使用需完成一次媒体投影授权。
- 长截图主引擎为手动滚动 + 帧采样拼接（无需任何权限）；无障碍自动滚动为可选备选引擎，需用户手动开启。
- 应用截图在 Android 13 及以下为系统栏裁切降级方案，边界机型可能需手动二次微调。
- 全界面增强截取依赖厂商私有接口，仅小米 / 鸿蒙可能生效，任何异常均静默回退。
