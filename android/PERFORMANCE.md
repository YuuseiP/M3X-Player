# M3X 0.4.1 performance verification

Date: 2026-10-28. Device: Shanling M3X, Android 7.1.1, 4,158 existing tracks.
The device's catalog, music files, playlists and scan configuration were not replaced.
The release APK retains the existing signing key. No remote release was published.

## Measurements

| Measurement | Before | After |
| --- | --- | --- |
| Activity displayed (`am start -W` / ActivityManager) | 20.056-21.092 s; launch wait timed out | 881 / 864 / 899 ms |
| Complete UI ready | Not separately instrumented in baseline | 5,723 / 5,757 / 5,767 ms after page navigation |
| Native catalog restore | 13,637 ms | 4,390-4,501 ms, on the worker thread |
| Native queue construction and installation | 5,035-5,294 ms | 862-935 ms |
| Navigation JavaScript handler | 377-837 ms | 54 / 42 / 50 / 35 / 34 / 39 ms |
| Button release to the next UI frame | 377-400 ms after the initial rendering improvements; click synthesis still took about 239 ms | 51 / 124 / 240 ms (profile / player / library) |
| Category JavaScript handler | Not recorded | 59-132 ms |

The three cold starts use `am force-stop` before each launch. They are process-cold,
not power-on/storage-cache-cold. `performance.now()` measures the complete UI from
WebView navigation; it is not the same clock/origin as ActivityManager launch time.
The first screen contains a loading message until the catalog is ready; it is not
presented as a fully usable 0.9-second catalog.
Touch timings include the next frame callback, not only the event handler, and
vary with device scheduling; an earlier run was 57-71 ms. The final recorded run
above is retained rather than selecting the fastest result.

Evidence:

- `../dist/verification-0.4.1.json`: release hash, installation match and check results.
- `captures/perf-0.4.1-startup.json`: three release APK startup runs.
- `captures/perf-0.4.1-live.log`: final instrumentation log, including touch-to-frame and category timings.
- `captures/perf-0.4.1-library.png`, `captures/perf-0.4.1-sheet.png`: device screenshots.
- `app/build/reports/lint-results-release.html`: complete Android lint report.
- `app/build/test-results/testDebugUnitTest/`: JVM test results.

## Changes and review fixes

- Load the catalog and classification on the existing background executor. Read
  library/details through asynchronous WebView requests instead of blocking JS.
- Cache document-provider discovery once per authority. Parse encoded SAF
  document IDs without decoding the full tree URI repeatedly.
- Specify progressive extraction for local audio. This avoids expensive streaming
  manifest inference over long SAF paths; network source inference is unchanged.
- Do not save preferences or inspect the native queue just to navigate. Reuse
  unchanged DOM rows, index classification entities, and create only visible lists.
- Show native songs and groups in batches of 40. Pagination does not truncate the
  actual playback queue. Existing browser-preview pagination remains 150 songs.
- Replace allocated, layout-measuring ripples with immediate pressed feedback.
  Replace page and sheet animations with short opacity/transform keyframes.
- Cancel stale sheet-close timers on reopen, retain keyboard/back behavior and
  honor reduced motion. Animation waits never gate playback or navigation.
- Dispatch valid native button taps at touch release. Movement, cancellation,
  multiple fingers, disabled buttons and long presses do not enter this fast path.
  Prevent the delayed compatibility click from firing the same action twice.
- Preserve saved track/position and listening history during initial queue setup.
- Lower paused polling frequency and update audio routes through device callbacks
  rather than repeatedly querying devices on every playback snapshot.
- Send summary metadata, not duplicated source URIs/paths and embedded lyrics,
  for the initial catalog. Full details remain available on demand.
- Fix Media3 opt-in declarations and validate persisted directory read grants.

## Checks

- Signed release build, 15 JVM tests, complete release Lint.
- Device `UiPerformanceTest`: original library retained, SAF ID equivalence,
  saved track/position/history, navigation, real injected touchscreen events,
  category pages and sheet open/close.
- Device `AlacPlaybackTest`: 16/24-bit ALAC, seek, PCM16 DSP at 48 kHz and AAC.
- Device FLAC/export test: PCM24 reference hash, PCM16 export, clipping/reverse.
- Browser tests: 4,200-track performance fixture; touch/drag/cancel/multi-touch;
  keyboard/form activation; pagination; sheet reopening and reduced motion;
  folders, sorting, profile collections, scan reports, full preview playback,
  playlists, avatar, EQ/ReplayGain, lyrics and sleep timer.
- Python preview tests: 19 passed. Built-in classification config: all 4,235
  mappings and 461 entities passed.

## Boundaries

Lint retains five warnings: JavaScript in the deliberately local-only WebView,
the exported MediaSession service needed by external media controllers, and
three storage-space API suggestions for the Android 7.1-compatible conversion
paths. These are not reported as fixed or silently suppressed.

This run does not include another full scan of the user's music, hours-long
playback, power consumption measurement, physical DAC/analog measurement or
physical USB/Bluetooth hot-plug testing. Decode/seek tests are not an auditory
quality evaluation. The results describe this M3X and catalog, not every Android
device or storage card.

## Reproduce

Run from the `android` directory after building the release and test APKs:

```powershell
adb -s a6840768 shell am force-stop cn.yuusei.m3x
adb -s a6840768 shell am start -W -n cn.yuusei.m3x/.MainActivity
adb -s a6840768 shell am instrument -w -e class cn.yuusei.m3x.UiPerformanceTest cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

The instrumented UI probe does not scan, replace the catalog or play user songs.
