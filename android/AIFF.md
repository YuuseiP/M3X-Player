# AIFF playback fix — 0.4.2

Date: 2026-10-29. Device: Shanling M3X, Android 7.1.1, ADB serial `a6840768`.

## Cause and implementation

The scanner accepted `.aif` / `.aiff`, but the configured Media3 1.5.1 progressive
extractors had no AIFF container reader. The FFmpeg decoder extension decodes
compressed packets; adding it did not add an AIFF demuxer.

`AiffExtractor` now runs before the default extractors, leaving their other format
readers available. It streams frame-aligned PCM, converts big-endian integer
samples to little-endian, converts signed 8-bit to unsigned PCM, and exposes a
sample-frame seek map. It does not convert or cache the complete song. Supported
inputs are integer PCM AIFF and uncompressed AIFF-C (`NONE`, `twos`, `sowt`), at
8/16/24/32 bits. Compressed / float AIFF-C is outside this implementation.

The shared bounded `AiffHeader` parser supplies duration, rate, channels and bit
depth to `TagReader`; scanning also recognizes `.aifc`. It validates FORM/chunk
bounds, sample rate, frame count, SSND offset and declared PCM size. Playback
checks the physical file length when available and reports truncated samples.

## Verified on the release APK

- Existing signing certificate retained; installed using `adb install -r`.
- `AiffPlaybackTest`: **2 test methods passed** in 33.927 seconds.
- Generated 997 Hz / 44.1 kHz stereo fixtures: 8/16/24/32-bit AIFF and 16-bit
  little-endian AIFF-C. All report correct metadata, deliver nonzero PCM, seek,
  pause/resume and reach the end. A FileProvider content URI is also exercised.
- 24-bit AIFF with EQ and resampling delivers audio to the existing 48 kHz DSP
  output path. No DSP source PCM encodings remain 8/16/24/32-bit at the sink input.
- Three existing full-length **32-bit / 96 kHz** AIFF songs in the device library:
  metadata, playback, nonzero PCM, seek to 30 seconds, pause/resume. AudioTrack
  reports 96 kHz. The third song has a quiet opening, so the signal check waits
  for audio rather than requiring nonzero samples at 400 ms.
- **18 JVM tests passed**, including parser / metadata tests, odd chunk padding,
  SSND offset, exact sample byte conversion, and malformed / unsupported headers.
- Full release Lint: **0 errors, 5 existing warnings**.

Playback tests use a separate muted player and inspect the PCM before volume
application. They do not change playlists, catalog or scanning settings, write
source songs, or perform a full library rescan. These checks establish decoded
signal and playback behavior, not an auditory or physical DAC measurement.

Local evidence (ignored generated files):

- `aiff-device-test.log`: instrumentation result.
- `captures/aiff-0.4.2-device.log`: PCM peaks, encodings and rates.
- `app/build/test-results/testDebugUnitTest/`: JVM reports.
- `app/build/reports/lint-results-release.html`: full Lint report.
- `../dist/Cheng-M3X-0.4.2.apk` and `.apk.sha256`.

APK SHA-256: `661b202abd8319f3c6362cbf392f6b56a04178b61b2cfae98810a305191b9da0`.
No remote GitHub Release was created.

## Reproduce

The fixtures are synthetic audio generated with FFmpeg, packaged only in the
androidTest APK. For each N in 8/16/24/32, use `pcm_s8` / `pcm_sNbe`; use
`pcm_s16le -f aiff` for `fixture-sowt.aifc`. Source:
`sine=frequency=997:sample_rate=44100:duration=3`, output `-ac 2`.

Build the release and a matching release-signed instrumentation APK from the
repository root, using the existing local signing configuration:

```powershell
$init = Join-Path $env:TEMP 'm3x-aiff-release-test.gradle'
@'
gradle.beforeProject { p ->
    p.plugins.withId('com.android.application') {
        p.android.testBuildType = 'release'
    }
}
'@ | Set-Content -Encoding UTF8 $init
& .\android\.tools\gradle-8.9\bin\gradle.bat -p android -I $init :app:testDebugUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest :app:lintRelease --console=plain
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s a6840768 install -r android\app\build\outputs\apk\release\app-release.apk
& $adb -s a6840768 install -r android\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk
& $adb -s a6840768 shell am instrument -w -e class cn.yuusei.m3x.AiffPlaybackTest cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

`testExistingDeviceAiff` requires AIFF songs already present in the saved library.
For fixtures alone, select `cn.yuusei.m3x.AiffPlaybackTest#testAiffPlaybackAndSeek`.
