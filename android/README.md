# 澄 · M3X Android

## 安装

安装文件：`D:\CodeFile\M3X-Player\dist\Cheng-M3X-0.2.0.apk`。

这是带本地发布签名的侧载 APK，不是 Google Play 发布包。面向原版 M3X 的 Android 7.1 / API 25，`minSdk=25`、`targetSdk=28`。不要求电脑预览服务、不捆绑测试歌曲、不登录账户。

1. 将 APK 复制到 M3X，用系统文件管理器打开，按系统提示允许安装。
2. 首次启动允许读取音乐；也可进入 **我的 → 设置 → 添加扫描目录**，分别授权内置存储、microSD 或已挂载 U 盘中的音乐文件夹。已添加目录按添加顺序显示在按钮下方，可单独移除。
3. 可同时启用 Android 媒体库与多个固定目录。启动、授权、目录修改、筛选保存和存储卡挂载均不会自动扫描；配置完成后点击 **开始扫描**，扫描完成后进入曲库播放。
4. 软件内没有音量滑块；使用 M3X 实体音量键。声音处理、提交格式信息仍在播放页底部弹出面板。

不需要把 Windows 上的 `D:\BaiduNetdiskDownload` 路径填进设备；设备只能访问自身存储及已授权的网络服务器。

## 架构

- 页面与网页版共用界面源码，构建时转为 Android 7.1 旧 WebView 可执行的脚本和样式，所有页面资源打包在 APK 中。
- 界面使用本机 Endfield Game `sdkresources\font` 目录中复制的 HarmonyOS Sans 等字体；常规与粗体及多语言回退字体均打包在 APK 中，离线可用。
- WebView 只负责展示和输入。音频由 **Media3 ExoPlayer / MediaSessionService / AudioTrack** 在原生服务中播放，不使用 HTML Audio 播放。
- MediaStore、SAF 持久目录授权、元数据、CUE、DSD 转 PCM、DSP、网络访问、文件导入导出均在 Java 层处理。
- 通知、锁屏控制、耳机键、音频焦点、拔耳机暂停、播放唤醒锁由原生媒体会话和播放引擎接入。
- Java 代码无自带 ABI 限制；解码能力仍取决于设备系统解码器。模拟器测试不等同于 M3X ARM 真机测试。
- 网页不调用原生桥；原生 WebView 只允许内置 `https://m3x.local/` 页面，外部页面不进入桥接上下文。

## 构建

需要 Windows PowerShell、JDK 17 或 21、Node/npm、Python 3.11+、Android SDK（Android 35 平台与 Build Tools 34.0.0）。本机已准备这些工具；`local.properties` 指向当前 SDK。

```powershell
Set-Location 'D:\CodeFile\M3X-Player\android'
python '.\setup-tools.py'
.\build-apk.ps1 -Test
```

Gradle 固定为 8.9，AGP 为 8.7.3；npm 依赖有锁文件。构建脚本先生成旧 WebView 资源，再构建、签名、复制 APK 并写 SHA-256。

首次发布签名保存在 `.signing\m3x-local.jks`，密码配置在 `local-signing.properties`。**两者应自行备份，不能提交或公开。后续更新必须沿用这把密钥；丢失后不能覆盖安装已有版本。** 未配置本地签名时，不应直接分发 Gradle 生成的未签名 release APK。

原生调试包和发布包证书不同，不能互相覆盖安装。普通用户只安装 `dist` 内的发布包。

## 测试

```powershell
Set-Location 'D:\CodeFile\M3X-Player\android'
python '.\setup-tools.py' --emulator
npm run assets
.\.tools\gradle-8.9\bin\gradle.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Android 7.1.1 模拟器配置放在 `.tools\avd`，测试时明确指定 `emulator-5580`，不会操作连接的用户真机。

```powershell
$env:ANDROID_AVD_HOME = 'D:\CodeFile\M3X-Player\android\.tools\avd'
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd M3X_API25 -no-window -no-boot-anim -no-snapshot -no-audio -gpu swiftshader_indirect -port 5580
```

在另一终端安装测试包后运行：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5580 install -r '.\app\build\outputs\apk\debug\app-debug.apk'
& $adb -s emulator-5580 install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
& $adb -s emulator-5580 shell am instrument -w cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

测试创建专用夹具，不能指向用户歌曲目录。FLAC 测试片段只在 `androidTest` 包中，不进入发布 APK。

## 输出与功能边界

- 面板分开显示源文件信息和 AudioTrack 提交信息。**提交采样率、已连接设备名称不代表 DAC 实际端点格式，也不是 bit-perfect 证明。**
- 不开启 DSP 时保留引擎可用的 PCM/Float 输出路径；系统解码器不保证将所有高位深源文件以原位深输出。
- EQ、ReplayGain、混响、压限、音乐厅或手动 SRC 开启时，当前处理链是 **PCM16 输入/输出、double 滤波运算**；不能称作端到端 32 位浮点 DSP。SRC 使用带低通的 sinc 插值。
- 无压缩单/双声道 DSF/DFF 转为 **24bit / 88.2kHz PCM 缓存**。不是原生 DSD、DoP 或 DST 解码。倒放缓存、片段导出为 PCM16 WAV；生成缓存可能花费时间和空间。
- 原生队列接入引擎无缝衔接；不同格式、设备解码器和编码延迟可能产生间隙，尚未做采样级无缝测量。
- “专辑感知淡变”是不同专辑边界的短淡出/淡入，同专辑保留衔接；**不是双源交叉混音、BPM/段落分析，也不是 Morvanium**。
- 倍速支持 0.25～4×（实际可用性受解码器/设备性能影响）；不提供 50× 等极端模式。
- DLNA 是服务器发现/浏览和拉流播放，不是向外部渲染器投送。WebDAV 支持目录浏览；带密码账户要求 HTTPS，凭据通过 Android KeyStore 加密保存，不包含在资料备份中。不自动跟随重定向以免转发授权头。
- 网络歌词通过用户提供的 LRC 地址导入；未接付费或无授权歌词搜索服务。备份走系统文档选择器，可选已安装云盘提供者，不是自动云同步。
- 歌曲片段导出针对系统解码器可读取的歌曲，DSD 先转 PCM。标签/歌词编辑交给用户安装的外部编辑器，原播放器不会自动改写源文件。某些远程源不能被系统片段解码器读取时会报告失败。
- 睡眠定时不跨应用强制停止、系统重启恢复；播放服务被系统杀死时，不承诺继续执行内存中的计时。
- USB 独占/UAC 协商、原生 DSD、厂商 Direct/强制 DAC 采样率、OpenSL ES/AAudio 后端、MQA、专有远程协议未实现，不以空开关替代。M3X AGLO 不能当成第三方应用直出已验证的依据。

完整状态与验收项目见根目录 `M3X-Android-Implementation.md`。

## 参考与开源依赖

- Android 原生后台播放说明：`https://developer.android.com/media/media3/session/background-playback`
- Android 音频焦点：`https://developer.android.com/media/optimize/audio-focus`
- Android 存储访问框架：`https://developer.android.com/training/data-storage/shared/documents-files`
- Media3：AndroidX / Apache-2.0，版本 1.5.1。
- AndroidX Core：Apache-2.0，版本 1.13.1；用于标准 FileProvider。
- OkHttp：Apache-2.0，版本 4.12.0。
- AutoEq：`https://github.com/jaakkopasanen/AutoEq`，参数按需下载，不复制其他播放器的私有耳机库。
- 构建期 Babel（MIT）、core-js（MIT）、测试期 JUnit（EPL-1.0）、JSON-java（公开项目依赖，见对应发行许可）。

本项目未使用参考 APK 的代码、图标、BASS 动态库、授权模块、品牌音效或私有服务协议。
