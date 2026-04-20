# 360 Video Spike

Evaluation repo for adding 360-video support to the Capmo Android app. Two
throwaway modules, two approaches, one recommended architecture.

> **Full writeup:** [SPIKE_HANDOFF.md](SPIKE_HANDOFF.md) — findings, tradeoffs,
> limitations, and the phased production plan.

## Modules

| Module     | Approach                           | APK overhead   | What it proves                                                        |
|------------|------------------------------------|----------------|-----------------------------------------------------------------------|
| `:viewer`  | Media3 `SphericalGLSurfaceView`    | ~0 MB          | Equirectangular MP4 playback with touch-drag pan, no SDK needed       |
| `:app`     | Insta360 Mobile SDK (sdkmedia 1.10.1) | +265 MB    | Raw `.insv` dual-fisheye playback, projection modes, MP4 export       |

The recommended production path is **Media3 for viewing, Insta360 SDK only for
raw `.insv` ingestion** — see the handoff for rationale.

## Requirements

- Android Studio (Koala+) with the Android Gradle Plugin 8.9 toolchain
- JDK 17
- An **arm64** device or emulator — both modules ship arm64-only
- For `:app`: a **4KB-page** device. Insta360 1.10.1 is not 16KB-page safe
  (blocking issue — see handoff §Limitations)
- For `:app`: Insta360 Maven credentials in `local.properties` (defaults in
  `settings.gradle.kts` work; override if you have project-specific credentials)

## Quick start

```bash
# Clone
git clone https://github.com/capmo/360-video-spike.git
cd 360-video-spike

# Media3 viewer — no SDK, small APK, plays bundled sphere.mp4
./gradlew :viewer:installDebug
adb shell am start -n de.capmo.insta360viewer/.MainActivity

# Insta360 SDK spike — plays bundled .insv, exports spherical MP4
#   test.insv (137 MB) is gitignored; push manually before launching:
adb push path/to/test.insv /sdcard/Android/data/de.capmo.insta360spike/cache/test.insv
./gradlew :app:installDebug
adb shell am start -n de.capmo.insta360spike/.MainActivity
```

## Verify

```bash
./gradlew check
```

Should finish green with only informational "newer version available" warnings
on the pinned spike dependencies.

## Layout

```
├── app/                 Insta360 SDK spike — .insv playback + export
├── viewer/              Media3-only spike — spherical MP4 playback
├── gradle/
│   └── libs.versions.toml   Version catalog (AGP, Kotlin, Compose BOM, Media3, Insta360)
├── settings.gradle.kts  Module list + Insta360 Maven repo
├── SPIKE_HANDOFF.md     Findings, tradeoffs, production plan
└── STAGE0_HANDOFF.md    Earlier spike notes (playback-only stage)
```

## Status

Spike complete. Both approaches are validated. Next step is Phase 1 of the
handoff's production plan: ship the Media3 viewer behind
`fp-android-insta360-player` in the main Capmo app. Phase 2 (Insta360 SDK
module) is blocked on Insta360 shipping a 16KB-page-safe SDK build.
