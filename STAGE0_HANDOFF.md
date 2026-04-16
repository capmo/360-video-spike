# Stage 0 handoff — Insta360 SDK playback spike

## TL;DR

- Spike builds successfully with `sdkmedia:1.10.1` + `sdkcamera:1.10.1`.
- Final APK size: **265 MB release / 271 MB debug** (arm64-v8a only).
- The SDK crashes on the Pixel-10-Pro emulator (Android 16 dev / 16KB pages) with a native linker alignment error. **This is a potential Play Store blocker for Capmo** — see "16KB page finding" below.
- Still needs a **4KB-page arm64 device** (most production Android phones today) to verify actual playback — the emulator is 16KB.

## What the spike is

Minimal single-module Android + Compose app at `/Users/braian.gapur/StudioProjects/insta360-spike/` that integrates the Insta360 Media SDK and plays a 360 file via `InstaVideoPlayerView` (wrapped in `AndroidView`). No Capmo code touched.

APKs:
- `app/build/outputs/apk/debug/app-debug.apk`   — 271 MB
- `app/build/outputs/apk/release/app-release.apk` — 265 MB

## What I learned while getting it to build

### 1. `sdkmedia` alone is not enough — `sdkcamera` is also required

Even though Capmo's integration will never use the camera (no pairing, read-only playback), `libarvbmg.so` (the rendering engine) has hard dlopen dependencies on libs that ship only with `sdkcamera`'s transitive AAR tree: `libarffmpeg.so`, `libapeg.so`, `libarypto.so`, `libasl.so`, `libturbojpeg.so`, `libusb-1.0.so`, `libOne.so`. Without `sdkcamera` on the classpath → `UnsatisfiedLinkError` at `InstaMediaSDK.init()`.

This is almost certainly an Insta360 packaging bug — the media SDK should bring its own runtime deps. For Stage 1: we depend on both artifacts even though we only use the media one.

### 2. Aggressive native-lib exclusion does not work

My first attempt dropped SNPE + MNN + PlatformValidator (~95 MB of AI-inference libs we don't need for basic playback). Result: `libarvbmg.so` failed to load — it has a static link to `libSNPE.so`. So we must ship everything. The *only* safely-excludable libs are the Hexagon DSP skeleton files (`lib*Skel.so`) and `libcalculator_skel.so`, matching what the SDK's own demo does. That saves ~40 MB.

### 3. APK size is ~265 MB — and 115 MB of that is one unavoidable `libarvbmg.so`

Breakdown after all safe excludes:
```
115 MB   libarvbmg.so              (Insta360 renderer — no way around)
 53 MB   libSnpeHtpPrepare.so      (AI, dlopen'd at startup)
 18 MB   libSNPE.so                (AI, dlopen'd at startup)
 12 MB   libPlatformValidatorShared.so
 10 MB   libarffmpeg.so            (ffmpeg build — video decode)
 22 MB   classes*.dex              (SDK Java/Kotlin + our Compose deps)
 10 MB   assets/insta360/          (stitching LUTs/templates)
 ~25 MB  other small libs (MNN, tbb, insbase2, nativeplayer, SNPE stubs, usb, jpeg, etc.)
```

For Capmo this means an expected added download size of **~150-250 MB** to any user who gets the 360-viewer code path. Mitigation options (must decide in Stage 1): Play Store dynamic feature module, or split APK by ABI (already doing arm64-only).

### 4. 16KB page compatibility — ⚠ material finding

Starting Nov 2025, Google requires all apps with `targetSdk ≥ 35` to support 16KB memory pages, or they can't be uploaded to Play Store. Capmo has `targetSdk = 36`.

The Insta360 SDK libs — at least `libturbojpeg.so`, `libapeg.so`, `libmobvoidsp.so`, `libusb-1.0.so` — report `p_align = 65536` in their ELF program headers. Mathematically that's ≥ 16384 and should be fine. But the Android 16-based Pixel emulator (16KB pages) refuses to load `libturbojpeg.so` with:

```
"libturbojpeg.so" program alignment (4096) cannot be smaller than system page size (16384)
```

I don't fully understand why the linker reports "4096" when the ELF says 65536 — possibly related to how AGP packages the uncompressed lib inside the APK at offsets that are 16KB-aligned but not 64KB-aligned. Already set `packaging.jniLibs.useLegacyPackaging = false`, which stored the libs uncompressed + page-aligned, but it didn't fix the error.

**Implications**:

- On a **4KB-page arm64 device** (effectively every production Android phone still today) the spike should run. Most devs test on 4KB.
- On a **16KB-page device** (Pixel 8 Pro+ on Android 15 QPR1, new Pixel emulators, and the device fleet over the next 12-24 months) the spike will crash on app start.
- For Capmo to ship 360 to Play Store at `targetSdk = 36`, **Insta360 must publish a 16KB-page-compatible SDK build**. Verify with their support: ask for a 1.11.x or later build with `-Wl,-z,max-page-size=16384` linker flags on all shipped .so files. This is the standard fix: rebuild with that flag and the ELF + packaging math works out.
- If no 16KB-compatible SDK is available before Capmo's ship target, options are: (a) drop Capmo's `targetSdk` to 34 (not acceptable long-term, blocks other Play Store features), (b) put the 360 feature in a dynamic feature module with a separate `targetSdk` compatibility story (unclear if Play Store allows this), (c) delay the feature until Insta360 ships compatible libs.

Raise this with Insta360 dev support before committing to a ship date.

## What you need to do — verify playback on real hardware

The test MP4 is bundled into the APK as an asset — just install and launch, no file pushing, no permissions:

```bash
# Plug in an arm64 Android phone (currently 4KB page in almost all cases).
~/Library/Android/sdk/platform-tools/adb devices   # confirm it shows up

cd /Users/braian.gapur/StudioProjects/insta360-spike
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n de.capmo.insta360spike/.MainActivity
```

The spike copies the bundled 360 clip (`src/main/assets/test_360.mp4`, 8 MB) to the app's cache dir on first launch and plays it automatically. The **Pick a different file (optional)** button is there if you want to try a raw `.insv` later.

### Verify:

- [ ] App opens without crashing (no `UnsatisfiedLinkError` at `InstaMediaSDK.init`).
- [ ] Status line under the picker changes to `Playing 1280×640 @ 30fps` once the first frame lands.
- [ ] Touch-drag pans the view (equirectangular sphere nav).
- [ ] Tap toggles play/pause.
- [ ] Normal / Fisheye / Perspective buttons switch projection modes.

If the phone is 16KB-page and you hit the `libturbojpeg.so` alignment crash: confirms the finding above; we're blocked on an Insta360 SDK update. Capture `adb logcat -d | grep -iE "linker|FATAL|ARVBMG"`.

Also worth trying: a **raw `.insv` off the X4/X5 card** to confirm the SDK really does stitch dual-fisheye on the fly (that's its biggest value-add over a generic 360 player).

## Test file analysis (for reference)

You supplied `20260415_144043_974.mp4` (7.9 MB, 18.3s, H.264). ffprobe:
- **1280×640** — 2:1 aspect, classic equirectangular stitched 360.
- 30 fps, 3.5 Mbps.
- 4-channel AAC audio (4.0 layout) — matches Insta360 spatial-audio capture.
- No Spherical Video V2 metadata boxes (no `st3d`/`sv3d`).

Looks like a stitched export from the Insta360 phone app. The missing spherical metadata means generic 360 players (YouTube, VLC) may not auto-detect it as 360 — they'd display it flat-squashed. The Insta360 SDK doesn't rely on that metadata, so it should play correctly.

## What Stage 1 looks like (after Stage 0 is verified)

Blocking prerequisite: resolution on the 16KB page issue (either confirm it works on the Capmo device fleet's 4KB phones, or wait for an Insta360 SDK update).

1. Add Insta360 Maven repo to Capmo's `settings.gradle.kts` (creds from `local.properties`).
2. New module `core/presentation/video-360` depending on `sdkmedia` + `sdkcamera` (required for runtime libs, even though we never use the camera API).
3. Same `packaging { jniLibs { excludes = [...]; useLegacyPackaging = false } }` block.
4. Arm64-only (or later: dynamic feature module to keep APK size off non-360 users).
5. `.insv` / `.insp` MIME types added to `FileTypeMapper.kt` → new `AttachmentType.Video360`.
6. New `Spherical360VideoPlayerScreen` behind `fp-android-insta360-player` flag.
7. Route 360 files from `VideoPlayerEntry`; regular videos stay on the existing Media3 player.

## Known weirdness in the spike (harmless)

- `LocalLifecycleOwner` deprecation warning — switch to `androidx.lifecycle.compose.LocalLifecycleOwner` in Stage 1.
- Release APK signs with debug key (unsigned for Play but fine for local install).
- `AUTOLOAD_PATH = "/sdcard/Download/test_360.mp4"` is a spike-only dev shortcut — gone in Stage 1.
