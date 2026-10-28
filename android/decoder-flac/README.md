# FLAC software fallback

This module vendors the decoder-only Java/JNI sources from AndroidX Media3
1.5.1 (`libraries/decoder_flac`, Apache-2.0). libFLAC 1.4.3 is downloaded with a
pinned SHA-256 by `../prepare-flac.ps1`; its BSD license is in
`src/main/assets/flac-COPYING.Xiph`.

The native extractor is deliberately NOT included. Media3's Java FLAC extractor
keeps compressed frames intact, so `EXTENSION_RENDERER_MODE_ON` tries platform
decoders first and uses `LibflacAudioRenderer` only when unsupported. Including
the native extractor would decode to PCM before renderer selection.

Build with `../build-apk.ps1` (Android NDK 27.2.12479018, CMake 3.22.1). Gradle
installs those SDK components when their licenses have been accepted.

Upstream:
- https://github.com/androidx/media/tree/1.5.1/libraries/decoder_flac
- https://github.com/xiph/flac/tree/1.4.3

Local changes: expose `FlacDecoderJni` for the existing clip/reverse exporter;
convert signed FLAC 8-bit samples to Android's unsigned PCM8; use the pinned
libFLAC source directory in CMake. All other decoder code is unmodified.
