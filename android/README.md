# 澄 · M3X Android

## 安装

安装文件：`D:\CodeFile\M3X-Player\dist\Cheng-M3X-0.4.2.apk`。

这是带本地发布签名的侧载 APK，不是 Google Play 发布包。面向原版 M3X 的 Android 7.1 / API 25，`minSdk=25`、`targetSdk=28`。不要求电脑预览服务、不捆绑测试歌曲、不登录账户。

1. 将 APK 复制到 M3X，用系统文件管理器打开，按系统提示允许安装。
2. 首次启动允许读取音乐；也可进入 **我的 → 设置 → 添加扫描目录**，分别授权内置存储、microSD 或已挂载 U 盘中的音乐文件夹。已添加目录按添加顺序显示在按钮下方，可单独移除。
3. 可同时启用 Android 媒体库与多个固定目录。启动、授权、目录修改、筛选保存和存储卡挂载均不会自动扫描；配置完成后点击 **开始扫描**，扫描完成后进入曲库播放。
4. 软件内没有音量滑块；使用 M3X 实体音量键。声音处理、提交格式信息仍在播放页底部弹出面板。

不需要把 Windows 上的 `D:\BaiduNetdiskDownload` 路径填进设备；设备只能访问自身存储及已授权的网络服务器。

## 0.4.2 AIFF 播放修复

- 补充 AIFF / 未压缩 AIFF-C（NONE、twos、sowt）容器提取器，实时读取 PCM，支持 8/16/24/32 位、进度拖动和现有 DSP；不生成整曲转换缓存。
- 扫描直接读取 COMM / SSND 的时长、采样率、声道和位深，识别 `.aif`、`.aiff`、`.aifc`。
- 真机测试与边界见 `AIFF.md`。测试歌曲仅在 androidTest APK 中。

## 0.4.1 启动、交互与动画

- 曲库缓存后台加载，文档提供者按 authority 缓存；本地音频直接使用渐进提取，避免 SAF 长路径的流媒体格式推断。
- 曲库与详情异步读取；导航不存盘、不检查原生队列；未变化列表复用，原生歌曲与分类每次加载 40 条，不缩短真实播放队列。
- 重写页面/弹层动画，取消布局驱动波纹；保留减少动态效果、键盘、弹层返回/重开。M3X 原生按钮在有效触摸结束时触发一次点击，排除滚动、取消和多点手势。
- 暂停时降低轮询频率，音频路由通过设备事件更新。保留已有歌曲 ID、收藏、歌单和扫描设置；启动不扫描。
- 测量方法、完整曲库就绪时间和验证边界见 `PERFORMANCE.md`。

## 0.4.0 解码与扫描

- 平台解码优先，未支持的 FLAC 使用 Media3 libFLAC 扩展实时软解，ALAC 使用 Media3 FFmpeg 扩展实时软解；不预转整首歌曲、不写入源文件。无 DSP 时保留高位深输入到 AudioSink，开启 DSP 时沿用现有 PCM16 处理链。
- 同一软件后端也用于系统 FLAC 解码不可用时的片段导出/倒放；导出仍为原有 PCM16 WAV，不标称高位深导出或硬件解码。
- 直接读取 PCM WAV 的时长、采样率、声道和位深，支持 32bit/96kHz；系统标签和音轨解析各自独立回退。大 FLAC 图片/填充块跳过而非拒绝歌曲。
- CUE 支持相对路径、反斜杠及同目录文件名回退，整个索引验证成功后才替换整轨，坏 CUE 不再留下部分分轨。真实缺失/改名文件仅报告警告，不猜测匹配。
- 设置页显示完整错误路径和原因，区分音频失败、CUE 警告、不支持格式，可导出 JSON，重启后保留。`.wv` 明确报告不支持，不再静默漏扫；本版未加入 WavPack 解码器。
- 保留原有签名、歌曲 ID、收藏和歌单。升级后首次手动扫描会重读旧版元数据，之后继续增量扫描。

## 0.3.0 界面与内置分类（保留）

- 复用当前网页预览的曲库、播放与「我的」界面。角色、组合、歌手独立浏览，分类排序和歌曲排序互不干扰；文件夹逐层进入，系统返回键也逐层返回。
- 收藏、歌单与本地歌曲放在「我的」整行横向栏目中，沿用原来的收藏、歌单、队列和歌曲 ID；不清除旧版本数据。
- 构建时从 `artist-classification/semantic/library-preview.json` 和已保存的 `web-preview/.classification-draft.json` 内置当前确认结果：4,235 首歌曲、461 个角色/组合/歌手实体。配置只保留相对路径，不包含 Windows 根目录，不捆绑歌曲，不调用电脑服务。
- 按完整相对路径和文件大小匹配已保存归属，支持内置存储、microSD、SAF 和选择更上层/下层目录；保留原目录结构即可。改名后的新文件仅在 `THE_IDOLMASTER` 目录内复用无歧义的原始歌手标签精确映射。不重新识别、不拆分未知署名、不推断组合成员。
- 名称保留目前分类中已核实的百科汉字名；未命中配置的歌曲按完整原始署名放入普通歌手分类。匹配数量在扫描设置中显示，不设置待检查项目，不修改源文件标签。
- 扫描仍由用户手动触发；扫描完成才发布真实时长、位深、采样率与封面，不把预览快照中的零时长放入 Android 曲库。取消/失败保留上次完整曲库，缓存重扫重新应用内置分类。
- 长队列的「当前歌曲」会先加载到对应位置再滚动定位。此次不提供 APK 内分类编辑器；分类调整仍先在网页确认，再构建进 APK。

## 架构

- 页面与网页版共用界面源码，构建时转为 Android 7.1 旧 WebView 可执行的脚本和样式，所有页面资源打包在 APK 中。
- 界面使用本机 Endfield Game `sdkresources\font` 目录中复制的 HarmonyOS Sans 等字体；常规与粗体及多语言回退字体均打包在 APK 中，离线可用。
- WebView 只负责展示和输入。音频由 **Media3 ExoPlayer / MediaSessionService / AudioTrack** 在原生服务中播放，不使用 HTML Audio 播放。
- MediaStore、SAF 持久目录授权、元数据、CUE、DSD 转 PCM、DSP、网络访问、文件导入导出均在 Java 层处理。
- 通知、锁屏控制、耳机键、音频焦点、拔耳机暂停、播放唤醒锁由原生媒体会话和播放引擎接入。
- libFLAC 和 FFmpeg 扩展包含 ARM32、ARM64 和 x86_64 原生库；其余格式取决于系统解码器和 Media3 支持。DAC 输出与文件解码是两条独立链路。
- 网页不调用原生桥；原生 WebView 只允许内置 `https://m3x.local/` 页面，外部页面不进入桥接上下文。

## 构建

需要 Windows PowerShell、JDK 17 或 21、Node/npm、Python 3.11+、Android SDK（Android 35 平台与 Build Tools 34.0.0）。本机已准备这些工具；`local.properties` 指向当前 SDK。

```powershell
Set-Location 'D:\CodeFile\M3X-Player\android'
python '.\setup-tools.py'
.\build-apk.ps1 -Test
```

Gradle 固定为 8.9，AGP 为 8.7.3；npm 依赖有锁文件。构建脚本先生成旧 WebView 资源，再构建、签名、复制 APK 并写 SHA-256。
内置分类的生成及全量映射检查可运行 `node android\test_classification_config.cjs`；Android JVM 测试还逐条核对跨卷路径、署名回退、CUE 与元数据保留。

首次发布签名保存在 `.signing\m3x-local.jks`，密码配置在 `local-signing.properties`。**两者应自行备份，不能提交或公开。后续更新必须沿用这把密钥；丢失后不能覆盖安装已有版本。** 未配置本地签名时，不应直接分发 Gradle 生成的未签名 release APK。

原生调试包和发布包证书不同，不能互相覆盖安装。普通用户只安装 `dist` 内的发布包。

## 测试

```powershell
Set-Location 'D:\CodeFile\M3X-Player\android'
python '.\setup-tools.py' --emulator
npm run assets
.\prepare-ffmpeg.ps1
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
& $adb -s emulator-5580 shell am instrument -w -e class cn.yuusei.m3x.NativePlayerTest cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

测试创建专用夹具，扫描测试不能指向用户歌曲目录。FLAC、AAC 和 16/24 位 ALAC 测试片段只在 `androidTest` 包中，不进入发布 APK。`AlacPlaybackTest` 可通过 `probeUri` 参数只读验证一首设备歌曲，不修改曲库。

可选完整歌曲吞吐测试使用网页预览已有的 10 首完整 24bit/96kHz FLAC（约 1.36 GB），仅复制到指定模拟器测试目录，不进入 APK。安装匹配签名的应用与测试包后运行：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5580 push '..\web-preview\media' '/sdcard/Download/m3x-full-scan-fixtures'
& $adb -s emulator-5580 shell am instrument -w -e class cn.yuusei.m3x.NativePlayerTest#testFullLengthScanThroughput -e scanDirectory /sdcard/Download/m3x-full-scan-fixtures cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

测试进行三次不复用曲库元数据的扫描，每次要求至少 10 首/秒，并检查时长、24/96、声道、歌手和封面；完成后恢复原曲库和扫描设置。常规扫描目标为 10–30 首/秒，不人为限制更快设备。模拟器结果不等于 M3X 或真实 microSD / SAF 性能，文件系统缓存也未清除。

扫描中的封面处理改用图片内容缓存：相同图片只缩放、编码一次，独立封面保持独立；保留原来的歌曲 ID、旧封面缓存和目录扫描方式。开源方案对比、三轮前后测量与复现命令见 [SCAN_RESEARCH.md](SCAN_RESEARCH.md)。共享封面夹具的吞吐提升不代表所有格式、存储设备都同比提速。

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
- FLAC 扩展：Media3 1.5.1（Apache-2.0）+ libFLAC 1.4.3（BSD）；来源、局部改动和构建方式见 `decoder-flac/README.md`。首次构建需要 Android NDK 27.2.12479018 和 CMake 3.22.1。
- FFmpeg 扩展：Media3 FFmpeg 解码器（Apache-2.0）+ FFmpeg 6.0（LGPL-2.1-or-later）。`prepare-ffmpeg.ps1` 固定下载 AerioTV 的预构建 AAR 并校验 SHA-256；该 AAR 的 Media3 1.4.1 解码器 Java/JNI 源码与 1.5.1 相同。上游构建配置及可替换构建说明：`https://github.com/jonzey231/AerioTV-Android/blob/340a6eead602c25d7dfdddc3d72a48a24fc9cb47/app/libs/README.md`；FFmpeg 源码：`https://github.com/FFmpeg/FFmpeg/tree/release/6.0`。FFmpeg 许可证随 APK 附带于开源许可页面。
- AndroidX Core：Apache-2.0，版本 1.13.1；用于标准 FileProvider。
- OkHttp：Apache-2.0，版本 4.12.0。
- AutoEq：`https://github.com/jaakkopasanen/AutoEq`，参数按需下载，不复制其他播放器的私有耳机库。
- 构建期 Babel（MIT）、core-js（MIT）、测试期 JUnit（EPL-1.0）、JSON-java（公开项目依赖，见对应发行许可）。

本项目未使用参考 APK 的代码、图标、BASS 动态库、授权模块、品牌音效或私有服务协议。
