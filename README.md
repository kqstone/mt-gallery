# MT Gallery - 非官方 MT-Photos 安卓客户端
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)]()
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-orange.svg)]()

**重要提醒，不建议作为日常使用，仅作测试，一切bug与可能导致的文件丢失，使用者自行承担，与作者无关**

`MT Gallery` 是一款针对开源个人照片管理系统 **[MT-Photos](https://mtmt.tech/)** 的非官方开源 Android 客户端。

相较于官方客户端，本项目采用现代化的 Android 技术栈（Jetpack Compose + Room + WorkManager）重新构建，主打**本地与云端媒体的智能混合显示**、**高度灵活的同步删除**以及**设备存储空间优化**功能，旨在提供极致流畅、一体化的跨端个人相册体验。

> [!NOTE]
> 本项目的代码大量采用 **Claude Code** 联合小米 **Momo-v2.5-pro** 大模型辅助生成。

---

## 🌟 核心特性

### 1. 本地与云端媒体混合显示 (Hybrid Timeline)
* **智能去重合并**：在后台自动扫描本地媒体库 (`MediaStore`) 并拉取云端服务器时间线，基于文件 MD5 值进行智能比对与去重，消除重复显示。
* **统一时间线**：将本地与云端媒体无缝融合于一个优雅的瀑布流时间线中，并在数据模型上定义了清晰的同步状态：
  * `LOCAL_ONLY`（仅存在于本地）：尚未备份到云端的本地照片。
  * `CLOUD_ONLY`（仅存在于云端）：已在云端存储，但本地已没有原图的照片。
  * `SYNCED`（已同步）：本地和云端均存在同一份文件（MD5 一致）。
* **流畅的浏览体验**：使用本地高性能缓存与 Coil 异步加载框架，实现极佳的滚动吞吐量与首屏加载速度。

### 2. 本地与云端同步删除 (Synchronous Deletion)
支持真正的一键双向/同步删除，用户无需重复在手机和服务器上多次操作：
* **多端同步销毁**：删除选中媒体时，客户端会自动向 MT-Photos 云端服务器发送删除请求，同时物理删除本地存储介质中的原图文件。
* **多层次缓存清理**：同步清理 Room 数据库记录以及本地高清缩略图缓存，杜绝“幽灵照片”。
* **双删除模式**：
  * **系统确认模式 (Confirm)**：使用标准的 Android 11+ `createDeleteRequest` API，弹出系统删除对话框，保护隐私与数据安全。
  * **直接删除模式 (Direct)**：在用户授予 `MANAGE_EXTERNAL_STORAGE` 权限后，支持零弹窗一键直接删除，提供极速顺畅的管理体验。

### 3. 后台自动备份与同步 (Background Sync & Backup)
* **基于 WorkManager 的稳健后台调度**：无需常驻前台，符合系统最佳省电实践：
  * `SyncWorker`：监听本地媒体库变化，一旦发生变动，自动在后台进行增量同步并智能清理孤立记录。
  * `BackupWorker`：在满足触发条件（如连接 Wi-Fi、处于充电状态、设备空闲等）下，自动在后台以分块流式上传本地 `LOCAL_ONLY` 媒体至云端。
* **前台通知与进度反馈**：备份过程中提供系统前台通知，直观呈现当前上传进度（第几张、文件名、总数），上传成功后自动触发服务器端重新扫描。

### 4. 手机存储空间优化 (Storage Optimization)
* **无缝腾出空间**：支持识别所有已成功备份至云端（状态为 `SYNCED`）的本地媒体。
* **原图自动释放**：可手动或自动清理这些本地原图以释放珍贵的手机存储空间，同时在本地保留 Room 缓存和精简的缩略图，确保离线或弱网下依然能流畅浏览相册脉络。

---

## 🛠️ 技术栈

本应用采用 Kotlin 编写：

| 维度 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **编程语言** | [Kotlin](https://kotlinlang.org/) | Android 官方开发语言 (JDK 17) |
| **UI 框架** | [Jetpack Compose](https://developer.android.com/compose) | 声明式 UI，完全基于 [Material Design 3](https://m3.material.io/) 规范设计 |
| **持久化数据库** | [Room Database](https://developer.android.com/training/data-storage/room) | SQLite 对象的本地映射层，作为本地与云端融合的 Single Source of Truth |
| **后台异步任务** | [Jetpack WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | 负责离线备份与增量同步的标准后台执行引擎 |
| **网络请求** | [Retrofit 2](https://square.github.io/retrofit/) & [OkHttp 3](https://square.github.io/okhttp/) | 负责与 MT-Photos REST APIs 进行高效通信。后端接口主要依据官方 Swagger API 文档实现，部分非公开的核心接口通过 Web 抓包逆向获取 |
| **图片加载** | [Coil](https://coil-kt.github.io/coil/) | 基于 Kotlin 协程的高性能异步图片加载与多级缓存框架 |
| **视频播放** | [Jetpack Media3 ExoPlayer](https://developer.android.com/media/media3) | 提供流畅稳定的视频流与本地视频播放 |
| **配置存储** | [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore) | 现代化的轻量级配置存储，用以替代传统的 SharedPreferences |

---

## 📁 项目目录结构

```text
mt_gallery/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/kqstone/mtphotos/
│   │   │   │   ├── data/                 # 数据层
│   │   │   │   │   ├── api/              # Retrofit 接口定义 (GatewayApi)
│   │   │   │   │   ├── local/            # 本地扫描器、媒体监听器、缓存、存储优化器
│   │   │   │   │   │   └── db/           # Room Database, Dao, MediaEntity (同步状态核心定义)
│   │   │   │   │   └── repository/       # 仓库层 (SyncRepository 同步/去重核心, GalleryRepository, AuthRepository)
│   │   │   │   ├── network/              # 网络基础组件 (OkHttpClient 拦截器等)
│   │   │   │   ├── ui/                   # UI 表示层 (Jetpack Compose 界面与组件)
│   │   │   │   │   ├── gallery/          # 混合相册时间线、多选模式 UI 及逻辑
│   │   │   │   │   ├── viewer/           # 大图浏览器、视频播放器组件
│   │   │   │   │   ├── discovery/        # 探索发现界面（人物、场景分类、足迹/城市分类）
│   │   │   │   │   ├── folder/           # 文件夹与文件夹详情浏览
│   │   │   │   │   ├── settings/         # 备份、同步、存储优化、服务连接配置界面
│   │   │   │   │   ├── theme/            # Material 3 动态色彩与主题配置
│   │   │   │   │   └── navigation/       # Compose Navigation 导航路由
│   │   │   │   └── worker/               # Background Workers (SyncWorker 同步, BackupWorker 备份)
│   │   │   └── res/                      # 静态资源与 XML 布局/配置
│   └── build.gradle.kts                  # 模块构建配置
├── gradle/                               # Gradle 包装器与版本依赖目录 (libs.versions.toml)
├── docs/                                 # 项目设计与架构文档
├── build.gradle.kts                      # 项目级构建配置
└── settings.gradle.kts                   # 模块管理与 Gradle 仓库声明
```

---

## 🚀 编译与运行

### 前提条件
* **JDK 17** 及以上。
* **Android Studio** (建议 Ladybug 2024.2.1 或更新版本)。
* 目标设备/模拟器运行环境：Android 8.0 (API 26) 或以上。

### 开发与部署步骤
1. **克隆项目**：
   ```bash
   git clone https://github.com/yourusername/mt_gallery.git
   cd mt_gallery
   ```
2. **导入 Android Studio**：
   * 打开 Android Studio，选择 `File -> Open`，定位并选中 `mt_gallery` 根目录。
   * 等待 Gradle 依赖同步与构建初始化完成。
3. **配置网络与服务**：
   * 启动应用后，在登录页输入您的 MT-Photos 服务器地址（如 `http://192.168.1.100:8063`）以及 API Token / 账户凭证完成授权。
4. **编译与运行**：
   * 在 Android Studio 工具栏中选中目标安卓设备，点击 **Run** 按钮（绿色三角）进行编译并在设备上部署调试版 APK。

---

## 👥 作者

* **kqstone** 

---

## ☕ Buy me a coffee / 赞助支持

如果您觉得这个项目对您有帮助，欢迎请作者喝杯咖啡！您的支持是作者持续维护和更新项目的最大动力。

<div align="center">
  <img src="docs/alipay_qr.jpg" width="280" alt="支付宝扫码支持" />
  <p><b>支付宝扫码支持</b></p>
</div>

---

## 📜 开源协议

本项目采用 **[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)** 开源协议。

* 本项目属于第三方个人开源客户端，与 MT-Photos 官方团队无直接隶属或商业关联。
* 您可以自由地将本项目的代码用于学习、修改、二次开发甚至分发，但请务必遵循 Apache License 2.0 协议要求保留原作者的版权声明和许可协议。
