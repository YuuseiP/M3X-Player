# 澄 · M3X Player

面向山灵 M3X 的本地音乐播放器，包含 Android 原生播放服务、共享网页界面和内置的歌手署名分类配置。当前本地版本为 **0.4.2**，Android 应用 ID 为 `cn.yuusei.m3x`。

本项目不是山灵官方产品。面向 Android 7.1 / API 25；0.4.0 已在 M3X 真机验证 FLAC 软解、扫描恢复和 96kHz PCM DIRECT 系统输出，尚未完成实听、DAC 模拟输出测量与长期播放验证。

## 下载与安装

0.4.2 修复 AIFF 播放，安装包在 `dist/Cheng-M3X-0.4.2.apk`，沿用发布签名，可覆盖安装；本次未创建或更新远程 Release。AIFF 真机验证见 `android/AIFF.md`，启动、触摸与动画验证见 `android/PERFORMANCE.md`。

从 [0.4.0 Release](../../releases/tag/v0.4.0) 下载 `Cheng-M3X-0.4.0.apk`、`.apk.sha256` 校验文件与 `verification-0.4.0.json` 验证记录；本机构建输出位于 `dist/`。历史版本保留在 [Releases](../../releases) 中。仓库现已公开。

1. 将 APK 复制到设备，使用文件管理器打开并按系统提示允许安装。
2. 首次启动授予读取音乐的权限，或在「我的 → 设置 → 添加扫描目录」授权音乐文件夹。
3. 点击「开始扫描」后播放；授权和目录修改不会自动扫描。音量使用设备实体音量键控制，安装包不附带歌曲。

在下载目录校验 APK：

```powershell
$expected = ((Get-Content -Raw -Encoding UTF8 '.\Cheng-M3X-0.4.0.apk.sha256').Trim() -split '\s+')[0]
$actual = (Get-FileHash -LiteralPath '.\Cheng-M3X-0.4.0.apk' -Algorithm SHA256).Hash
if ($actual -ne $expected) { throw 'APK SHA-256 mismatch' }
```

## 主要功能

- 本地曲库：MediaStore、多目录 SAF 授权、歌曲/角色/组合/歌手/专辑分类、逐层文件夹、搜索、收藏、歌单、队列和 CUE 虚拟分轨。
- 内置分类：保留已确认的 4,235 首歌曲归属与百科汉字名，离线匹配设备上的音乐；收藏和歌单集中在「我的」横向栏目中。
- 原生播放：Media3 / ExoPlayer 后台服务、媒体通知、音频焦点、系统媒体键、倍速、睡眠定时和播放统计。
- 声音处理：十段 EQ、32 段参数 EQ、ReplayGain、AutoEQ 参数导入、混响、压限和手动重采样。
- 扩展功能：LRC 歌词、WebDAV / DLNA 浏览播放、片段导出、倒放，以及设置与歌单备份。
- 共享界面：浅色/深色主题、两种布局、头像裁切、个人资料和独立的本机网页预览。

原生版与网页版能力不同，逐项状态及验收边界见 [Android 实现记录](M3X-Android-Implementation.md) 和 [网页功能记录](M3X-Feature-Completion.md)。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `android/` | Android Java 源码、共享页面构建、签名打包脚本、JVM 与设备测试 |
| `web-preview/` | HTML/CSS/JavaScript 界面、本机 Python 曲库服务与网页测试 |
| `artist-classification/` | 模型语义署名配置、按文件夹精确查表及本地分类试听，不改写音频或标签 |
| `M3X-Player-Design-Draft.md` | 产品与界面设计草案 |
| `M3X-Specifications.md` | 整理时的目标设备规格 |
| `RELEASE_NOTES.md` | 本次发布说明 |

`dist/`、构建缓存、工具下载、签名文件、私人曲库扫描报告、歌曲和封面不纳入 Git。APK 与校验文件单独放在 GitHub Release 中。

## 本机网页预览

需要 Python 3.11+；扫描音乐还需要 `ffmpeg` 和 `ffprobe` 可从 `PATH` 调用。在项目根目录运行：

```powershell
python '.\web-preview\server.py'
```

浏览器打开 `http://127.0.0.1:18765/`，在设置中选择自己的音乐目录并扫描。服务仅监听本机，按 Ctrl+C 停止。

仓库保留了原预览曲目的标签清单，但**不包含对应歌曲或封面**；首次打开可能看到无法播放的示例条目，扫描自己的目录后替换。不要为了普通使用运行 `prepare_music.py`，该脚本针对原开发机的音乐目录。更多说明见 [网页 README](web-preview/README.md)。

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
python -m unittest discover -s '.\web-preview' -p 'test_*.py'
node '.\web-preview\test_player_tools.cjs'
python -m unittest discover -s '.\artist-classification' -p 'test_*.py'
```

网页曲库测试使用临时合成音频，需要本机 FFmpeg；未安装时相关用例会跳过。浏览器 Playwright 测试和 Android 模拟器测试需另行准备环境及音乐夹具，不能仅凭上述命令认定全部端到端验证通过。

## 已知边界

- AudioTrack 提交格式、源文件的位深/采样率不等于实际 DAC 输出，也不构成 bit-perfect 证明。
- DSF/DFF 使用 PCM 转换缓存，不是原生 DSD、DoP 或 DST 解码。
- 当前 DSP 处理链为 PCM16 输入/输出；尚未验证采样级无缝播放。
- 未实现 USB 独占/UAC、厂商 Direct/DAC 控制、MQA 或跨音源节奏分析过渡。
- 整张 microSD/SAF 曲库重扫、真实 NAS/USB DAC、长期后台播放、功耗与长曲处理性能仍待验收；本版真机验证范围见 Release 附带的验证记录。

## 依赖与资源

Android 依赖及已收集的许可文本位于 [`android/licenses/`](android/licenses/)，具体版本见 Gradle 配置与 npm 锁文件。

字体来源记录见 [`web-preview/fonts/README.md`](web-preview/fonts/README.md)：现有字体从开发机游戏安装目录复制，随源码和现有 APK 保留，**其再分发授权尚未核实**。在确认授权或替换为明确允许分发的字体之前，不应将本仓库及安装包作为已完成授权审查的公开发行物。

本次未替项目作者选择开源许可证；仓库可访问不等于获得源码或第三方资源的再分发授权。
