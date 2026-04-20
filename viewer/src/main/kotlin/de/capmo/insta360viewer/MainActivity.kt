package de.capmo.insta360viewer

import android.content.Context
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val BUNDLED_ASSET_NAME = "sphere.mp4"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                ViewerScreen()
            }
        }
    }
}

/**
 * Non-observable holder for the [ExoPlayer] + its bundled asset path. Same shape
 * as `Insta360PlayerState` in the sibling spike: the Player is a native resource
 * and shouldn't live in Compose's snapshot state. Exposed state is emitted via
 * [StateFlow] so SDK-thread callbacks don't trigger recomposition storms.
 *
 * Stage 1 port: replace this with a proper ViewModel hoisted out of the composable,
 * so rotation preserves playback position and error state survives config changes.
 * For the spike, keeping it inside [remember] is sufficient.
 */
@UnstableApi
@Stable
private class ViewerPlayerState(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = ExoPlayer.REPEAT_MODE_ONE
        playWhenReady = true
    }

    private val _localPath = MutableStateFlow<String?>(null)
    val localPath: StateFlow<String?> = _localPath.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var released = false

    suspend fun extractBundledAsset(context: Context, assetName: String) {
        try {
            val dest = File(context.cacheDir, assetName)
            withContext(Dispatchers.IO) {
                if (!dest.exists() || dest.length() == 0L) {
                    context.assets.open(assetName).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            _localPath.value = dest.absolutePath
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _error.value = context.getString(R.string.viewer_asset_extract_failed, t.message.orEmpty())
        }
    }

    /**
     * Prepare the player for a local file. No-op if the player has already been
     * released by a concurrent disposal — without this guard, a prepare() call
     * that resumes after [release] would throw IllegalStateException.
     */
    fun prepareWithFile(path: String) {
        if (released) return
        player.setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
        player.prepare()
    }

    /** Safely route a GL-created surface to the player; no-op after [release]. */
    fun setVideoSurface(surface: Surface?) {
        if (released) return
        player.setVideoSurface(surface)
    }

    /** Safely copy the view's own frame-metadata / camera-motion listeners onto
     *  the player once. No-op after [release]. */
    fun setSphericalListeners(
        cameraMotion: androidx.media3.exoplayer.video.spherical.CameraMotionListener?,
        frameMetadata: androidx.media3.exoplayer.video.VideoFrameMetadataListener?,
    ) {
        if (released) return
        cameraMotion?.let { player.setCameraMotionListener(it) }
        frameMetadata?.let { player.setVideoFrameMetadataListener(it) }
    }

    fun release() {
        released = true
        player.release()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ViewerScreen() {
    // Android Studio previews have no assets dir and no GL surface — constructing
    // ExoPlayer + extracting a 63 MB MP4 would throw. Render a stateless stand-in.
    if (LocalInspectionMode.current) {
        ViewerScreenPreviewContent()
        return
    }
    val context = LocalContext.current
    val state = remember(context) { ViewerPlayerState(context) }
    val localPath by state.localPath.collectAsStateWithLifecycle()
    val error by state.error.collectAsStateWithLifecycle()

    LaunchedEffect(state) { state.extractBundledAsset(context, BUNDLED_ASSET_NAME) }
    LaunchedEffect(localPath) {
        localPath?.let(state::prepareWithFile)
    }

    // Player lifecycle is tied to the composable's lifecycle via AndroidView.onRelease
    // on the SphericalGLSurfaceView — but the Player is created before the View, so
    // we still need a fallback dispose path if the Surface view never got created.
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { state.release() }
    }

    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Text(stringResource(R.string.viewer_title), style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                stringResource(R.string.viewer_subtitle),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SphericalGLSurfaceView(ctx).apply {
                            // Route the GL-created Surface to the player once it exists,
                            // and clear it when destroyed. Media3 reads Google Spherical
                            // V1 (uuid/XMP) and V2 (st3d/sv3d) metadata from the MP4 and
                            // auto-projects decoded frames onto a sphere — no extra code
                            // on our side beyond this wiring.
                            addVideoSurfaceListener(
                                object : SphericalGLSurfaceView.VideoSurfaceListener {
                                    override fun onVideoSurfaceCreated(surface: Surface) {
                                        state.setVideoSurface(surface)
                                    }
                                    override fun onVideoSurfaceDestroyed(surface: Surface) {
                                        state.setVideoSurface(null)
                                    }
                                },
                            )
                            // Forward the view's own camera-motion and frame-metadata
                            // listeners to the player. These are getters on
                            // SphericalGLSurfaceView (Kotlin resolves `.cameraMotionListener`
                            // → `getCameraMotionListener()`), NOT references to top-level
                            // vals — `this.` qualifies to make that unambiguous.
                            state.setSphericalListeners(
                                cameraMotion = this.cameraMotionListener,
                                frameMetadata = this.videoFrameMetadataListener,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewerScreenPreviewContent() {
    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Text(stringResource(R.string.viewer_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.viewer_subtitle),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Preview(showBackground = true, name = "Light")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark")
@Composable
private fun ViewerScreenPreview() {
    MaterialTheme {
        ViewerScreenPreviewContent()
    }
}
