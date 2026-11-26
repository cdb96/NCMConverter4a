# ARCHITECTURE.md

本文档描述 **NCMConverter4a** 的整体架构、模块划分、核心数据流与构建方式。

## 1. 总体架构

NCMConverter4a 采用 **Kotlin Multiplatform (KMP) + Compose Multiplatform + 共享 C++ SIMD 原生核心** 的分层架构，在 Android 与 Desktop（JVM / GraalVM Native Image）之间共享 UI、转换编排、流式 I/O、元数据标签与底层解密算法：

```text
+-------------------------------------------------------------------+
|                        平台入口与壳层                              |
|  androidApp (MainActivity)        desktopMain (main.kt / Window)  |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                    共享 UI 与状态控制 (commonMain/ui)              |
|  App.kt / MainScreen / ConversionDashboard / DirectoryScanScreen  |
|  ConversionController / DirectoryScanController                   |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                 服务编排与平台 I/O 抽象 (commonMain/service)       |
|  FileConversionService <---> ConversionFiles (expect/interface)   |
|  DirectoryScanService  <---> ScanDirectoryBrowser                 |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|            格式识别、流式解密与标签 (commonMain/converter, io, tag) |
|  detectEncryptedFormat -> NCMConverter / KGMConverter / KGG       |
|  ConversionBufferPool / PrefetchInputStream / ID3 & FLAC Metadata |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                 JNI 契约与平台实现 (jni expect/actual)             |
|  RC4Decrypt / KGMDecrypt / KGGDecrypt                             |
|  Android: System.loadLibrary("ncmc4a")                            |
|  Desktop: NativeLibrary (Classpath 资源解压 / 本地 build 回退)     |
+---------------------------------+---------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|               共享 C++17 SIMD 原生核心 (native/cpp)                |
|  RC4Core.cpp / KGMCore.cpp / KGGCore.cpp                          |
|  SimdCompat.h -> <arm_neon.h> (ARM) | NEON_2_SSE.h (x86/x86_64)   |
+-------------------------------------------------------------------+
```

## 2. 仓库与模块结构

| 路径 | 职责 |
| --- | --- |
| `androidApp/` | Android 应用入口 (`MainActivity`)、Manifest、资源与 NDK CMake 集成，构建 `libncmc4a.so`。 |
| `composeApp/src/commonMain/` | 跨平台共享代码：Compose UI、Controller、批量转换与目录扫描服务、NCM/KGM/KGG 解析与流式转换、音频标签生成、流式 I/O 工具及 `expect` JNI 声明。 |
| `composeApp/src/androidMain/` | Android 平台实现：SAF/MediaStore 文件读写 (`AndroidConversionFiles`)、目录扫描 (`AndroidDirectoryScanner`)、Root 读取 KGG 密钥 (`RootDatabaseReader`) 及 JNI `actual` 绑定。 |
| `composeApp/src/desktopMain/` | Desktop 平台实现：本地文件输出与冲突分配 (`DesktopConversionFiles` / `DesktopOutputAllocator`)、本地目录扫描 (`DesktopDirectoryScanner`)、配置持久化与系统判断 (`DesktopConfig`)、拖拽/文件选择、Skiko 渲染配置、Windows 原生标题栏、`KggDbDecryptor` 与 `NativeLibrary` 加载器。 |
| `composeApp/src/commonTest/` & `desktopTest/` | 跨平台单元测试与桌面端回归测试（含解密向量、并发、元数据、I/O、`MainScreenScrollTest` 及 `DirectoryScanScreenTest`）。 |
| `native/` | 跨平台共享 C++17 解密核心 (`cpp/`)、Windows 标题栏支持 (`windows/NativeCaption.cpp`)、独立自测 (`tests/SelfTest.cpp`) 与 `third_party/NEON_2_SSE/`。 |
| `composeApp/tools/` & `.github/native-image/` | Desktop GraalVM Native Image 打包脚本、冒烟验证入口、Skiko ICU 裁剪工具及可达性元数据。 |

## 3. 核心分层与数据流

### 3.1 UI 与状态控制 (`commonMain/kotlin/ui/`)
- **`App.kt`**：提供共享的 `NCMConverter4aTheme` 包装壳。
- **自适应界面 (`ui/screens/`)**：`MainScreen` 根据窗口宽度在单列与双列布局之间切换，组合 `ConversionDashboard`、`DirectoryScanScreen`（宽屏采用左侧控制与内联目录管理栏 + 右侧固定顶/底栏的结果卡片，窄屏采用单列与弹窗配置目录）、`SettingsScreen` 与 `BenchmarkDialog`。
- **控制器**：`ConversionController` 与 `DirectoryScanController` 管理协程生命周期，通过 `MutableStateFlow` 对工作线程上报的进度进行合并，避免高频状态更新阻塞 UI 线程。

### 3.2 服务编排与内存控制 (`commonMain/kotlin/service/`)
- **`FileConversionService`**：
  - 使用 `Dispatchers.IO.limitedParallelism(workerCount)` 并发执行文件解密，使用独立的 `publishDispatcher` 并发完成输出发布（如 Android `MediaStore` 的 `IS_PENDING` 提交）。
  - 通过 `ConversionBufferPool` 与 `boundedWorkerBufferSize` 为所有并发 worker 分配受总预算约束、且按 256 字节对齐的工作缓冲区，避免大文件整块读入内存。
  - 通过 `ConversionFiles` 接口隔离平台差异，使核心转换逻辑无需感知 `File` 或 `Uri` / `MediaStore`。
- **`DirectoryScanService`**：通过 `ScanDirectoryBrowser` 遍历用户配置的目录，按格式（`.ncm`、`.kgm` / `.kgm.flac`、`.kgg` / `.kgg.flac`）过滤并去重。

### 3.3 格式检测、流式解密与元数据 (`commonMain/kotlin/converter/`, `io/`, `tag/`)
- **`EncryptedFormat.kt`**：`detectEncryptedFormat` 根据文件头魔数（`CTENFDAM` 识别 NCM；16 字节 `KGM_MAGIC` 配合第 20–23 字节版本号识别 KGG 或 KGM）将文件路由至对应转换器。
- **`NCMConverter`**：
  1. 解析 NCM 头，使用固定 `coreKey`（AES-ECB）解密 RC4 密钥，使用 `metaKey`（AES-ECB）与 `SimpleJsonParser` 解析曲目元数据。
  2. 提取或保留封面数据（`CoverImage`）。
  3. 以 `256 KiB` 分块流式读取音频载荷，调用 `RC4Decrypt` 就地解密；非原始写入模式下由 `ID3TagBuilder` 或 `FLACMetadataGenerator` 写入元数据标签。
- **`KGMConverter`**：校验并跳过 1024 字节文件头，提取 17 字节密钥后流式调用 `KGMDecrypt` 解密，并依据首字节识别 `flac` / `mp3`。
- **`converter/kgg/`**：
  - `KggBatchKeys` 在批次内懒加载并缓存 `KggKeyDatabase`。
  - 桌面端由 `KggDbDecryptor` 按页（AES-CBC）解密酷狗数据库并经 `SqliteKggReader` 提取 `ekey`；Android 端通过 `MMKVParser` 或 `RootDatabaseReader` 获取密钥。
  - 结合 `KggHeader`、`QmcCipher`、`TeaCipher`、`keyDerive` 与 `KGGDecrypt` 完成流式解密。
- **流式 I/O (`io/`)**：`BinaryIo.kt` 与 `BufferedStreams.kt` 提供 `BinaryInput` / `BinaryOutput`、`PrefetchInputStream` 及 `PassThroughBufferedOutputStream`，减少不必要的数据拷贝。

### 3.4 平台适配与 JNI 桥接 (`jni/`, `androidMain/`, `desktopMain/`)
- **`commonMain/kotlin/jni/`**：通过 `expect object RC4Decrypt`、`KGMDecrypt`、`KGGDecrypt` 定义统一的 JNI 契约。
- **Android 端**：
  - `AndroidConversionFiles` 使用 `ContentProviderClient` 批量查询并写入 `MediaStore`（支持冲突序号规避），通过 `DocumentFile` 读取 SAF 目录。
  - `libncmc4a.so` 由 `androidApp` 的 NDK CMake 构建打入 APK，直接通过 `System.loadLibrary("ncmc4a")` 加载。
- **Desktop 端**：
  - `NativeLibrary` 按 `-Dncmc4a.library` 覆盖路径 → Classpath 资源 (`ncmc4a/<os>-<arch>/ncmc4a.<ext>`) 按内容哈希解压至临时目录 → 本地 `native/build/` 目录的顺序加载原生库。
  - `DesktopRendering` 管理 GPU/软件渲染偏好，`DesktopNativeCaption` 与 `DesktopTallTitleBar` 适配 Windows 原生窗口标题栏。

## 4. 共享 C++ SIMD 原生核心 (`native/`)

- **单一份实现**：`RC4Core.cpp`、`KGMCore.cpp` 与 `KGGCore.cpp` 均使用 ARM NEON intrinsics 编写，不额外维护标量分支；通过 `SimdCompat.h` 在 ARM/AArch64 上使用 `<arm_neon.h>`，在 x86/x86_64 上通过 `NEON_2_SSE.h` 映射为 SSE（要求 SSSE3）。
- **状态管理**：`RC4Core` 与 `KGMCore` 使用 `thread_local` 存储每线程密钥状态，单文件解密期间在同一个 worker 线程内连续执行 `init` 与 `decrypt`。
- **桌面增强与自测**：Windows 下额外编入 `windows/NativeCaption.cpp` 并静态/独立链接运行库（MinGW 下静态链接运行库以避免外部 DLL 依赖）；`tests/SelfTest.cpp` 提供不依赖 JVM 的算法自测。

## 5. 构建与分发

- **Android**：使用 AGP + Kotlin Multiplatform + NDK (`arm64-v8a`、`x86_64`) 构建，Release 启用 R8 代码压缩与资源缩减。
- **Desktop**：`:composeApp:nativeBuild` 调用 CMake/Ninja 编译 `ncmc4a` 并打入资源；CI 流程支持 GraalVM Native Image 构建（包含 `-Os` 优化、Windows 下 `TrimSkikoIcu.java` 对 Skiko ICU 数据的裁剪以及独立启动冒烟测试）。
