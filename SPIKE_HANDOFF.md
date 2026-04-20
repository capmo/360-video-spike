# 360 Video Spike — Findings & Handoff

## TL;DR

We evaluated two approaches for 360 video playback on Android. Both work. The right production architecture combines them: **Media3 for viewing**, **Insta360 SDK optionally for raw `.insv` ingestion**.

|                                             | Media3 (`:viewer`)              | Insta360 SDK (`:app`) |
|---------------------------------------------|---------------------------------|-----------------------|
| **APK overhead**                            | ~0 MB (Media3 already in Capmo) | +265 MB               |
| **Equirectangular MP4 playback**            | Yes                             | Yes                   |
| **Touch-drag pan**                          | Yes                             | Yes                   |
| **Gyro/accelerometer**                      | Yes (automatic)                 | Yes                   |
| **Projection modes** (Perspective, Fisheye) | No                              | Yes                   |
| **Raw `.insv` dual-fisheye stitching**      | No                              | Yes                   |
| **Export to spherical MP4**                 | No                              | Yes                   |
| **16KB page compatible**                    | Yes                             | No (blocked)          |

**Repo**: https://github.com/capmo/360-video-spike

---

## Approach 1: Media3 SphericalGLSurfaceView (`:viewer` module)

### What it proves

Standard equirectangular 360 MP4s play with **zero custom rendering code**. Media3's `SphericalGLSurfaceView` auto-detects spherical metadata (Google Spherical V1/V2) and projects frames onto a sphere. Touch-drag panning works out of the box.

### How it works

1. `ExoPlayer` decodes the video
2. `SphericalGLSurfaceView` provides a GL surface that projects frames onto a sphere
3. Three listeners wire them together: `VideoSurfaceListener` (surface lifecycle), `CameraMotionListener` (device orientation), `VideoFrameMetadataListener` (per-frame metadata)
4. That's it — no shaders, no projection math, no SDK

### Key code (`MainActivity.kt`, 207 lines total)

- `ViewerPlayerState` — state holder wrapping `ExoPlayer` + `StateFlow` for UI state
- `ViewerScreen` — Compose screen embedding `SphericalGLSurfaceView` via `AndroidView`
- Asset extraction from APK to cache (Media3 needs a file path, not an asset stream)

### Dependencies

Only `androidx.media3:media3-exoplayer:1.10.0`. Everything else is standard Compose/Lifecycle.

### Limitations

- **No projection modes** — only equirectangular sphere. No perspective/fisheye switching
- **No raw format support** — can't play `.insv` dual-fisheye; requires pre-stitched MP4
- **No export** — playback only
- **Touch handling is internal** — can't customize drag/tap behavior without subclassing

### What's needed for production

- Move player to a `ViewModel` (survives config changes)
- Accept file path/URI from the calling screen instead of bundled asset
- Handle error/loading/empty states properly

---

## Approach 2: Insta360 SDK (`:app` module)

### What it proves

The SDK can play raw `.insv` dual-fisheye files with on-device stitching, switch projection modes (Normal/Fisheye/Perspective), and export to standard equirectangular MP4 with Google Spherical V1 metadata.

### How it works

1. `InstaMediaSDK.init(context)` in `Application.onCreate()` — one-time init
2. `WorkWrapper(filePaths)` loads video metadata (blocking JNI, must run on `Dispatchers.IO`)
3. `InstaVideoPlayerView` renders the video (native Android `View`)
4. `ExportUtils.exportVideo()` encodes to MP4 (async, callbacks on encoder thread)

### Key code

- `SpikeApplication.kt` (13 lines) — SDK initialization
- `MainActivity.kt` (135 lines) — file picker + bundled asset extraction
- `Insta360PlayerView.kt` (320 lines) — player state holder, Compose `AndroidView` bridge, mode switching, export with progress UI

### SDK APIs used

| API | Purpose |
|---|---|
| `InstaMediaSDK.init(context)` | Initialize rendering engine |
| `WorkWrapper(filePaths)` + `.loadExtraData()` | Load video metadata (blocking JNI) |
| `InstaVideoPlayerView` | Native 360 player view |
| `.switchNormalMode()` / `.switchFisheyeMode()` / `.switchPerspectiveMode()` | Projection switching |
| `ExportUtils.exportVideo(work, params, callback)` | Encode to equirectangular MP4 |
| `PlayerViewListener` / `PlayerGestureListener` | Lifecycle + touch callbacks |

### Dependencies

```
com.arashivision.sdk:sdkmedia:1.10.1
com.arashivision.sdk:sdkcamera:1.10.1  // required for runtime native libs, even though camera API is unused
```

Maven repo: `https://androidsdk.insta360.com/repository/maven-public/` (HTTP, public credentials: `insta360guest` / `EXMSjSo8OeOrjU7d`). Credential resolution: `local.properties` > env vars > fallback defaults.

### Limitations & gotchas

#### 1. 16KB page compatibility — BLOCKER

Starting Nov 2025, Play Store requires apps with `targetSdk >= 35` to support 16KB memory pages. Several Insta360 native libs (`libturbojpeg.so`, `libapeg.so`, `libmobvoidsp.so`, `libusb-1.0.so`) crash on 16KB-page devices/emulators:

```
"libturbojpeg.so" program alignment (4096) cannot be smaller than system page size (16384)
```

**Insta360 must rebuild their SDK with `-Wl,-z,max-page-size=16384`.** This needs to be raised with their support before committing to a ship date.

#### 2. `sdkcamera` drag-along

`sdkmedia` has undeclared runtime dependencies on native libs that only ship via `sdkcamera`. Without it: `UnsatisfiedLinkError` at init. Likely an Insta360 packaging bug. Must include both artifacts.

#### 3. APK size is ~265 MB

Breakdown:

| Library | Size | Notes |
|---|---|---|
| `libarvbmg.so` | 115 MB | Renderer, unavoidable |
| `libSnpeHtpPrepare.so` | 53 MB | AI inference |
| `libSNPE.so` | 18 MB | Qualcomm neural processor, statically linked from renderer |
| `libPlatformValidatorShared.so` | 12 MB | Platform validation |
| `libarffmpeg.so` | 10 MB | ffmpeg video decode |
| `classes*.dex` | 22 MB | SDK Java/Kotlin + Compose deps |
| `assets/insta360/` | 10 MB | Stitching LUTs/templates |
| Other small libs | ~25 MB | MNN, tbb, insbase2, nativeplayer, SNPE stubs, usb, jpeg, etc. |

Only safely excludable: Hexagon DSP skeletons (`*Skel.so`) saving ~40 MB. Can't strip further because `libarvbmg.so` has static links to the AI libs.

#### 4. Blocking JNI calls

`WorkWrapper.loadExtraData()` is a blocking JNI call that doesn't honor Kotlin cancellation. Must `ensureActive()` after it returns.

#### 5. File path requirement

SDK only accepts filesystem paths, not content URIs. Files must be copied to app-private storage first.

---

## Recommended Production Architecture

### Phase 1: Ship Media3-based viewer (low risk, no APK impact)

1. New screen `Spherical360VideoPlayerScreen` using `SphericalGLSurfaceView` + `ExoPlayer`
2. Behind feature flag `fp-android-insta360-player`
3. Route 360 files from `VideoPlayerEntry`; regular videos stay on existing player
4. Detect 360 via aspect ratio (2:1) or spherical metadata boxes
5. No new dependencies (Media3 already in Capmo)

### Phase 2: Insta360 SDK as optional module (when 16KB blocker resolved)

1. **Resolve 16KB page issue first** — contact Insta360 support, request SDK rebuild
2. New Gradle module `core/presentation/video-360` depending on `sdkmedia` + `sdkcamera`
3. Same `packaging { jniLibs { excludes = [...] } }` block from the spike
4. Arm64-only; consider Play Store dynamic feature module to keep APK size off non-360 users
5. Register `.insv` / `.insp` MIME types in `FileTypeMapper.kt` as `AttachmentType.Video360`
6. Add Insta360 Maven repo to Capmo's `settings.gradle.kts` (credentials from `local.properties`)

### Decision framework

| User has... | Use |
|---|---|
| Pre-stitched equirectangular MP4 | Media3 viewer (Phase 1) |
| Raw `.insv` from Insta360 camera | Insta360 SDK module (Phase 2) |
| Need for projection mode switching | Insta360 SDK module (Phase 2) |

---

## Verification checklist (for dev picking this up)

### On a real arm64 device (4KB pages)

- [ ] Install `:app` debug APK — confirm no `UnsatisfiedLinkError`
- [ ] Status shows `Playing 1280x640 @ 30fps` after first frame
- [ ] Touch-drag pans the sphere
- [ ] Tap toggles play/pause
- [ ] Normal / Fisheye / Perspective mode buttons work
- [ ] Export produces playable MP4
- [ ] Install `:viewer` debug APK — confirm 360 playback with drag-to-pan

### With raw `.insv` from X4/X5

- [ ] Pick `.insv` via file picker in `:app`
- [ ] Confirm dual-fisheye on-device stitching works
- [ ] Export produces equirectangular MP4 playable in `:viewer`

---

## Repo structure

```
capmo/360-video-spike/
├── app/                          # Insta360 SDK spike
│   ├── build.gradle.kts          # SDK deps, native lib packaging, arm64-only
│   └── src/main/kotlin/
│       ├── SpikeApplication.kt   # InstaMediaSDK.init()
│       ├── MainActivity.kt       # File picker + asset extraction
│       └── Insta360PlayerView.kt # Player state, Compose bridge, export
├── viewer/                       # Media3-only spike
│   ├── build.gradle.kts          # media3-exoplayer only
│   └── src/main/kotlin/
│       └── MainActivity.kt       # ExoPlayer + SphericalGLSurfaceView
├── gradle/libs.versions.toml     # Version catalog
├── settings.gradle.kts           # Insta360 Maven repo config
└── STAGE0_HANDOFF.md             # Original spike notes
```
