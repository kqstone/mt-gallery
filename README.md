# MT Gallery - 非官方 MT-Photos 安卓客户端
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)]()
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose-orange.svg)]()

**重要提醒：本客户端处于开发与测试阶段，不建议作为日常唯一相册管理工具使用。使用者需自行承担可能遇到的软件错误或文件丢失风险。**

`MT Gallery` 是一款针对开源个人照片管理系统 **[MT-Photos](https://mtmt.tech/)** 开发的非官方 Android 客户端。

本项目采用现代 Android 技术栈（Jetpack Compose + Room + WorkManager）进行开发，实现了本地媒体库与云端相册的整合展示、多端同步操作、后台自动备份以及本地存储空间优化等功能。

> [!NOTE]
> 本项目的代码大量采用 **Claude Code** 联合小米 **Momo-v2.5-pro** 大模型辅助生成。

---

## 🌟 主要功能

### 1. 本地与云端媒体混合显示 (Hybrid Timeline)
* **增量扫描与去重**：后台自动扫描本地媒体库 (`MediaStore`) 并拉取 MT-Photos 服务器时间线，通过比对文件 MD5 值进行去重，避免重复显示。
* **统一时间线**：将本地与云端媒体整合在同一个瀑布流中展示，并定义了以下三种同步状态：
  * `LOCAL_ONLY`：仅存在于本地，尚未备份至云端。
  * `CLOUD_ONLY`：已同步至云端，但本地原图已被清理或不存在。
  * `SYNCED`：本地与云端均存在对应的文件（MD5 一致）。
* **流畅的图片加载**：利用本地 Room 缓存与 Coil 加载框架，实现高效的列表滚动和首屏渲染。

### 2. 双向同步与操作任务队列 (Offline Operation Queue)
* **多端同步销毁**：支持在删除媒体文件时，同时向服务器发送删除请求并移除本地原图。
* **离线操作队列**：所有对云端的操作（如删除、收藏、添加标签、隐藏、重命名人物等）都会被记录在 Room 数据库的 `server_op_tasks` 表中。当网络不可用时，操作会缓存在本地，并在恢复连接后通过退避策略自动重试。
* **防重复任务合并**：在队列执行前自动清理和合并重复或相互冲突的操作任务（例如短时间内的重复删除或重复隐藏操作），减少无效的网络请求。

### 3. 后台自动备份与同步 (Background Sync & Backup)
* **基于 WorkManager 的后台调度**：
  * `SyncWorker`：监听本地媒体库变化，在后台执行增量同步并更新数据库记录。
  * `BackupWorker`：在满足指定约束条件（如连接 Wi-Fi、设备充电中、空闲状态等）时，自动在后台以分块流式上传本地 `LOCAL_ONLY` 媒体文件。
* **状态反馈**：提供系统前台通知展示当前的备份进度、当前文件名及总数，上传完成后通知服务器重新扫描以更新云端数据。

### 4. 私密相册与应用解锁 (Private Album)
* **隐私媒体隐藏**：支持将敏感照片或视频放入私密相册中（向云端发送隐藏请求，并在本地限制显示）。
* **安全解锁机制**：进入私密相册时需通过手势图案 (Pattern) 或 PIN 码进行解锁，确保本地浏览的数据安全。

### 5. 地图足迹 (Geotagged Photo Map)
* **空间数据聚合**：自动读取并解析带有地理位置信息的媒体文件，将其位置坐标存储于 Room 数据库中。
* **地图分布展示**：在内置地图上标注媒体的拍摄位置，并支持随着缩放自动进行标记聚合，方便用户按地理位置浏览照片。

### 6. 存储空间优化 (Storage Optimization)
* **本地原图释放**：识别状态为 `SYNCED`（已成功备份）的本地媒体，允许手动或自动清理这些原图以释放手机存储空间。
* **保留本地预览**：清理原图后，本地仍保留 Room 缓存元数据和轻量级缩略图，在离线或弱网环境下依然可以流畅浏览相册列表。

---

## 🛠️ 技术栈

本项目基于 Kotlin 语言进行构建，使用以下主流 Android 组件及库：

| 维度 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **编程语言** | [Kotlin](https://kotlinlang.org/) | JDK 17 目标平台 |
| **UI 框架** | [Jetpack Compose](https://developer.android.com/compose) | 基于 [Material Design 3](https://m3.material.io/) 规范的声明式 UI |
| **本地数据库** | [Room Database](https://developer.android.com/training/data-storage/room) | SQLite 对象关系映射，作为本地与云端媒体状态的单一可信数据源 |
| **后台任务** | [Jetpack WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | 负责离线备份与增量同步的后台执行引擎 |
| **网络请求** | [Retrofit 2](https://square.github.io/retrofit/) & [OkHttp 3](https://square.github.io/okhttp/) | 用于与 MT-Photos REST APIs 进行通信，包含自定义的重试与 Token 刷新拦截器 |
| **图片加载** | [Coil](https://coil-kt.github.io/coil/) | 支持多级缓存的高性能异步图片加载库 |
| **视频播放** | [Jetpack Media3 ExoPlayer](https://developer.android.com/media/media3) | 提供视频流与本地视频的播放支持，并在列表展示视频时长缩略图 |
| **配置存储** | [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore) | 用于存储登录状态、备份选项等轻量配置 |

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
│   │   │   │   │   │   └── db/           # Room Database, Dao, MediaEntity (同步状态与操作队列定义)
│   │   │   │   │   └── repository/       # 仓库层 (SyncRepository 同步/去重核心, GalleryRepository, AuthRepository)
│   │   │   │   ├── network/              # 网络基础组件 (OkHttpClient 拦截器等)
│   │   │   │   ├── ui/                   # UI 表示层 (Jetpack Compose 界面与组件)
│   │   │   │   │   ├── gallery/          # 混合相册时间线、多选模式 UI、私密相册解锁
│   │   │   │   │   ├── viewer/           # 大图浏览器、视频播放器组件
│   │   │   │   │   ├── discovery/        # 探索发现界面（人物、场景分类、足迹/城市分类）
│   │   │   │   │   ├── folder/           # 文件夹与文件夹详情浏览
│   │   │   │   │   ├── map/              # 媒体足迹地图展示与聚合渲染
│   │   │   │   │   ├── oplog/            # 同步日志与操作队列界面
│   │   │   │   │   ├── search/           # 搜索功能界面
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

### 环境要求
* **JDK 17** 及以上。
* **Android Studio** (推荐 Ladybug 2024.2.1 或更新版本)。
* 目标设备运行环境：Android 8.0 (API 26) 及以上。

### 编译步骤
1. **克隆仓库**：
   ```bash
   git clone https://github.com/yourusername/mt_gallery.git
   cd mt_gallery
   ```
2. **导入项目**：
   * 打开 Android Studio，选择 `File -> Open` 并选中 `mt_gallery` 根目录。
   * 等待 Gradle 依赖同步与构建初始化完成。
3. **编译 Debug APK**：
   * 在根目录下通过命令行编译：
     ```powershell
     .\gradlew.bat assembleDebug
     ```
   * 编译完成后，生成的 APK 文件位于 `app/build/outputs/apk/debug/` 目录下。

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

* 本项目属于第三方开源客户端，与 MT-Photos 官方团队无商业关联或直接隶属关系。
* 您可以自由地将本项目的代码用于学习、修改或二次开发，但请遵循 Apache License 2.0 协议要求，保留原作者的版权声明及许可协议。
