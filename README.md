# NCMConverter4a
---
一个适用于安卓的歌曲转换器
### 使用方法：
1.从右下角按钮选择文件 

2.从外部文件管理器选择文件分享至该软件

PS:上述方法均支持批量选择文件
### 设置说明：
1.原始写入模式：开启后软件仅进行解密，不进行写入元数据操作（只适用于NCM文件，因为KGM文件根本没提供元数据）

2.线程数滑条：调整转换时使用的线程数量

### 桌面版内存设置

安装版和 Gradle `:composeApp:run` 默认使用 32 MiB 初始堆、768 MiB 最大堆及 Serial GC。最大堆是按需增长的上限；进程 RAM 还包含界面、JVM 和本地库。批量转换结束后，若堆超过 64 MiB，会请求回收并收缩空闲堆。

Windows 界面默认使用软件绘制，以减少图形驱动的常驻 RAM。直接使用 `java -jar` 时需自行传入 JVM 参数，安装包的启动配置不会自动应用到 JAR。

### 免责声明：
本项目仅用于学习用途，请确保您已充分了解相关版权协议，否则请勿使用。
### 代码参考
taurusxin/ncmdump; charlotte-xiao/NCM2MP3; anonymous5l/ncmdump; unlock-music/unlock-music

#### DeepWiki链接
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/cdb96/NCMConverter4a)
