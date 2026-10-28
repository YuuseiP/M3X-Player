# 歌曲扫描方案对比与实测

调研、测量日期：2026-10-28。

## 对比的开源实现

以下链接固定到本次读取的提交，便于复核。测量的是本项目改动前后，并非这些播放器整机运行速度的横向排名。

| 项目 | 源码中的做法 | 本项目的决定 |
| --- | --- | --- |
| Auxio | 从文件描述符使用 TagLib 读取标签；提取流水线并发为 8；内嵌封面按图片字节生成标识，缓存文件存在时跳过转码 | 采用图片内容缓存思路，使用现有 SHA-256 工具和 Android Bitmap 实现；不引入 TagLib/JNI，不直接照搬线程数 |
| Gramophone | 遍历文件，并根据 MediaStore 记录和修改时间决定是否调用系统扫描器 | 本地已有 MediaStore 来源和大小/修改时间缓存；没有发现值得整体替换的证据 |
| Vanilla Music | 区分目录扫描与媒体库验证；文件修改时间未变化时跳过标签读取 | 本地已有等价的增量检查，继续保留 |

原始源码：

- Auxio `CoverIdentifier.kt`：<https://github.com/OxygenCobalt/Auxio/blob/c05cebc52fe2393bf3a988068e4060fd6dba408f/musikr/src/main/java/org/oxycblt/musikr/covers/embedded/CoverIdentifier.kt>
- Auxio `CoverStorage.kt`：<https://github.com/OxygenCobalt/Auxio/blob/c05cebc52fe2393bf3a988068e4060fd6dba408f/musikr/src/main/java/org/oxycblt/musikr/covers/stored/CoverStorage.kt>
- Auxio `MetadataExtractor.kt`：<https://github.com/OxygenCobalt/Auxio/blob/c05cebc52fe2393bf3a988068e4060fd6dba408f/musikr/src/main/java/org/oxycblt/musikr/metadata/MetadataExtractor.kt>
- Auxio `ExtractStep.kt`：<https://github.com/OxygenCobalt/Auxio/blob/c05cebc52fe2393bf3a988068e4060fd6dba408f/musikr/src/main/java/org/oxycblt/musikr/pipeline/ExtractStep.kt>
- Gramophone `SdScanner.kt`：<https://github.com/FoedusProgramme/Gramophone/blob/55b5ba30abb9dc551f5460f6acfd3fd1e763fd00/app/src/main/java/org/akanework/gramophone/logic/utils/SdScanner.kt>
- Vanilla Music `MediaScanner.java`：<https://github.com/vanilla-music/vanilla/blob/38e1761320cd22faa4533d69b0bb3fa9f4f0b20d/app/src/main/java/ch/blinkenlights/android/medialibrary/MediaScanner.java>

未复制上述播放器的实现代码或添加其依赖，仅根据扫描流程中可减少的重复工作修改本地代码。

## 替换的部分

`MusicLibrary.cacheArtwork` 原来按歌曲 ID 写缓存，同一图片出现在 80 首歌中就会解码、缩放、编码和写盘 80 次。

现在根据图片字节计算现有格式的 SHA-256 截断摘要（96 位），以 `art-<摘要>.jpg` 命名；同一内容使用同一把锁，其他图片仍可并行处理。已存在的图片直接复用，首次写入使用 `AtomicFile`，成功后才发布封面地址。内嵌图片直接传递原字节数组，省去流包装后再次复制。

目录发现、SAF 授权、4 路标签读取、FLAC 头部快速路径、CUE、分类、歌曲 ID、收藏/歌单引用、旧封面地址和取消扫描保留原有机制。文件变更后的独立图片产生不同地址。旧曲库不强制迁移封面；图片缓存被系统回收后，后续扫描会重新生成。

影响范围：扫描和外部单曲导入共用的封面函数、目录侧封面的调用、摘要工具重载、对应测试和本文档。WebView 与后台播放服务已有按封面地址读取的逻辑，无需修改。

## 前后测量

同一台 Windows 机器上的 Android 7.1.1 / API 25 x86_64 模拟器 `emulator-5580`，使用相同本地发布签名的 release 构建。基线为本次修改前的工作区源码，包含当时已有的未提交改动。

每轮清空内存曲库以禁止复用歌曲元数据，采用现有扫描结果中的 `rate`。未清除操作系统文件缓存。下面是首次成对比较的原始结果，后续回归测试没有覆盖这些记录。

| 场景 | 改动前，首/秒（3 轮） | 改动后，首/秒（3 轮） | 解释 |
| --- | --- | --- | --- |
| 80 首测试 FLAC，共享 1600×1600 JPEG | 251.57 / 279.72 / 169.13 | 567.38 / 625.00 / 930.23 | 每轮生成新图片，歌曲元数据和该图片缓存均不复用；中位数由 251.57 升至 625.00，约 2.48 倍 |
| 10 首完整 24bit/96kHz FLAC，合计约 1.36 GB | 52.36 / 54.64 / 40.65 | 43.29 / 111.11 / 117.65 | 共有 9 张不同内嵌图片，改动后第 2、3 轮可复用图片缓存；首次扫描没有显示提速 |

原始 JSON 保存在本机忽略目录 `android/captures/scan-{shared,full}-{before,after}.json`。

结论：已证实共享封面及已有图片缓存的场景可以减少重复工作，因此替换封面处理。没有证据证明替换整个扫描器或增加线程数会普遍更快；首次扫描大量不同大封面时仍需计算摘要，不能据此承诺提速。上述数字不是 M3X 真机、microSD 或 SAF 的吞吐结果，也没有单独测量取消延迟或断电恢复。

## 验证与复现

JVM 测试检查原歌曲 ID 摘要保持一致，既有音频逻辑测试继续运行。设备测试检查：并发扫描共享图片只发布一个地址；不同图片分别保存；图片变更产生新地址；已存在图片不重写；缺失缓存重建；无效/超大图片处理；真实 FLAC 信息；分类、目录侧封面、手动扫描、取消保留曲库、CUE 和后台播放。

在 `android` 目录构建签名匹配的应用与测试包：

```powershell
& '.\.tools\gradle-8.9\bin\gradle.bat' -I .tools/release-test.init.gradle :app:testDebugUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest --console=plain
```

`.tools/release-test.init.gradle` 是已有本地测试配置，用于将 `testBuildType` 设为 `release`。完成 README 中的工具准备及测试夹具复制后：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5580 install -r '.\app\build\outputs\apk\release\app-release.apk'
if ($LASTEXITCODE -ne 0) { throw '应用安装失败，请检查签名是否一致' }
& $adb -s emulator-5580 install -r '.\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
if ($LASTEXITCODE -ne 0) { throw '测试包安装失败，请检查签名是否一致' }
& $adb -s emulator-5580 shell am instrument -w -e class cn.yuusei.m3x.NativePlayerTest -e scanDirectory /sdcard/Download/m3x-full-scan-fixtures cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

明确指定测试类可避开本机 API 25 旧测试运行器在自动发现测试时出现的 `libart.so` 崩溃。单独重复任一吞吐测试时，将 `-e class` 的值改为 `cn.yuusei.m3x.NativePlayerTest#testSharedArtworkScanThroughput` 或 `cn.yuusei.m3x.NativePlayerTest#testFullLengthScanThroughput`。完整歌曲测试需要 `scanDirectory`；共享图片测试自行生成夹具。
