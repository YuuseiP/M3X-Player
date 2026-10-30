# 澄 · M3X Player

面向山灵 M3X 的本地音乐播放器，包含 Android 原生播放服务、内置 WebView 界面和内置的歌手署名分类配置。当前版本为 **0.6.0**，Android 应用 ID 为 `cn.yuusei.m3x`。

本项目不是山灵官方产品。面向 Android 7.1 / API 25；0.4.0 已在 M3X 真机验证 FLAC 软解、扫描恢复和 96kHz PCM DIRECT 系统输出，尚未完成实听、DAC 模拟输出测量与长期播放验证。

## 下载与安装

0.6.0 新增列表/双列网格、封面动态配色、终末地工业主题、歌词更多菜单和听歌图表，沿用原发布签名，可覆盖安装。主题入口为「我的 → 设置 → 界面主题」。实际 M3X 验证和边界见 [0.6.0 验证报告](android/APPEARANCE_060_VERIFICATION.md)，视觉研究见 [终末地参考](android/ENDFIELD_VISUAL_REFERENCES.md)。

从 [0.6.0 Release](../../releases/tag/v0.6.0) 下载 `Cheng-M3X-0.6.0.apk`、`.apk.sha256` 校验文件与 `verification-0.6.0.json` 验证记录；本机构建输出位于 `dist/`。历史版本保留在 [Releases](../../releases) 中。

1. 将 APK 复制到设备，使用文件管理器打开并按系统提示允许安装。
2. 首次启动授予读取音乐的权限，或在「我的 → 设置 → 添加扫描目录」授权音乐文件夹。
3. 点击「开始扫描」后播放；授权和目录修改不会自动扫描。音量使用设备实体音量键控制，安装包不附带歌曲。

在下载目录校验 APK：

```powershell
$expected = ((Get-Content -Raw -Encoding UTF8 '.\Cheng-M3X-0.6.0.apk.sha256').Trim() -split '\s+')[0]
$actual = (Get-FileHash -LiteralPath '.\Cheng-M3X-0.6.0.apk' -Algorithm SHA256).Hash
if ($actual -ne $expected) { throw 'APK SHA-256 mismatch' }
```

## 主要功能

- 本地曲库：MediaStore、多目录 SAF 授权、歌曲/角色/组合/歌手/专辑分类、逐层文件夹、搜索、收藏、歌单、队列和 CUE 虚拟分轨。
- 内置分类：保留已确认的 4,235 首歌曲归属与百科汉字名，离线匹配设备上的音乐；收藏和歌单集中在「我的」横向栏目中。
- 原生播放：Media3 / ExoPlayer 后台服务、媒体通知、音频焦点、系统媒体键、倍速、睡眠定时和播放统计。
- 声音处理：十段 EQ、32 段参数 EQ、ReplayGain、AutoEQ 参数导入、混响、压限和手动重采样。
- 歌词：QQ／网易云联网检索、候选预览、曲名／歌手／专辑／整曲偏差标注、离线采用、双语 LRC 写入歌曲标签和完整文件恢复。
- 扩展功能：LRC 歌词、WebDAV / DLNA 浏览播放、片段导出、倒放，以及设置与歌单备份。
- 界面：浅色/深色、经典/终末地主题、列表/双列网格、封面动态配色、两种播放布局、收藏封面预览与听歌图表；外观通过 ADB 真机截图与本地网页批注审阅。

逐项状态及验收边界见 [Android 实现记录](M3X-Android-Implementation.md)。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `android/` | Android Java 源码、共享页面构建、签名打包脚本、JVM 与设备测试 |
| `android/ui/` | APK 内置 HTML/CSS/JavaScript 界面及字体源码 |
| `android/capture-appearance.ps1` | ADB 真机逐页截图与审阅索引 |
| `android/appearance-review.py` | 真机截图批注网页，自动保存本地草稿供 agent 读取 |
| `artist-classification/` | 模型语义署名配置、按文件夹精确查表及本地分类试听，不改写音频或标签 |
| `M3X-Player-Design-Draft.md` | 产品与界面设计草案 |
| `M3X-Specifications.md` | 整理时的目标设备规格 |
| `RELEASE_NOTES.md` | 本次发布说明 |

`dist/`、构建缓存、工具下载、签名文件、私人曲库扫描报告、歌曲和封面不纳入 Git。APK 与校验文件单独放在 GitHub Release 中。

## 真机外观审阅

连接并解锁开启 USB 调试的 M3X 后执行：

```powershell
.\android\capture-appearance.ps1
python -u '.\android\appearance-review.py'
```

脚本读取已安装的发布版和真实曲库，通过匹配发布签名的 instrumentation 打开页面、截取物理显示屏，并生成 `android/captures/appearance-时间/INDEX.md` 和 `manifest.json`。打开 `http://127.0.0.1:18766/` 可平铺查看截图、绘画与填写要求；批注自动保存到该批次的 `review/draft.json`，后续 agent 直接读取草稿实施。操作、覆盖范围及草稿格式见 [外观审阅说明](android/APPEARANCE.md)。

## Android 构建

需要 Windows PowerShell、JDK 17 或 21、Node.js/npm、Python 3.11+，以及已安装 Android 35 平台和 Build Tools 34.0.0 的 Android SDK。FLAC 模块还需要 NDK 27.2.12479018 和 CMake 3.22.1；接受 SDK 许可后可由 Gradle 安装。`setup-tools.py` 下载 Gradle 8.9 并生成本机 SDK 路径配置，不负责安装完整 SDK。

在项目根目录运行：

```powershell
Set-Location '.\android'
python '.\setup-tools.py'
.\build-apk.ps1 -Test
```

脚本安装锁定的 npm 依赖、下载并校验 libFLAC 1.4.3、生成旧 WebView 页面、执行 JVM 测试，并将签名 APK 和 SHA-256 写入根目录 `dist/`。首次构建会生成自己的本地签名。

**`.signing/` 与 `local-signing.properties` 只保存在本机，必须自行备份。** 自行生成的密钥不能覆盖安装本仓库已发布 APK；发布更新必须沿用原签名，不能将私钥上传到仓库。设备测试命令见 [Android README](android/README.md)。

## 测试

在项目根目录执行不依赖设备的检查：

```powershell
node '.\android\ui\test_player_tools.cjs'
node '.\android\test_classification_config.cjs'
python -m unittest discover -s '.\artist-classification' -p 'test_*.py'
```

Android 功能验收使用 JVM 与设备测试；截图只证明对应界面的实际显示状态，不能替代播放、扫描和音频输出验收。

## 已知边界

- AudioTrack 提交格式、源文件的位深/采样率不等于实际 DAC 输出，也不构成 bit-perfect 证明。
- DSF/DFF 使用 PCM 转换缓存，不是原生 DSD、DoP 或 DST 解码。
- 当前 DSP 处理链为 PCM16 输入/输出；尚未验证采样级无缝播放。
- 未实现 USB 独占/UAC、厂商 Direct/DAC 控制、MQA 或跨音源节奏分析过渡。
- 整张 microSD/SAF 曲库重扫、真实 NAS/USB DAC、长期后台播放、功耗与长曲处理性能仍待验收；本版真机验证范围见 Release 附带的验证记录。

## 依赖与资源

Android 依赖及已收集的许可文本位于 [`android/licenses/`](android/licenses/)，具体版本见 Gradle 配置与 npm 锁文件。

字体来源记录见 [`android/ui/fonts/README.md`](android/ui/fonts/README.md)：现有字体从开发机游戏安装目录复制，随源码和现有 APK 保留，**其再分发授权尚未核实**。在确认授权或替换为明确允许分发的字体之前，不应将本仓库及安装包作为已完成授权审查的公开发行物。

本次未替项目作者选择开源许可证；仓库可访问不等于获得源码或第三方资源的再分发授权。
