package de.capmo.insta360spike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import com.arashivision.sdkmedia.export.ExportUtils
import com.arashivision.sdkmedia.export.ExportVideoParamsBuilder
import com.arashivision.sdkmedia.export.IExportCallback
import com.arashivision.sdkmedia.player.listener.PlayerGestureListener
import com.arashivision.sdkmedia.player.listener.PlayerViewListener
import com.arashivision.sdkmedia.player.video.InstaVideoPlayerView
import com.arashivision.sdkmedia.player.video.VideoParamsBuilder
import com.arashivision.sdkmedia.work.WorkWrapper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val TAG = "Insta360PlayerView"

/** Export lifecycle surfaced to the UI. Thread-safe via [StateFlow]. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Succeeded(val path: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Non-observable state holder that owns the native [InstaVideoPlayerView] and the
 * currently loaded [WorkWrapper]. The View is intentionally NOT held in a
 * [androidx.compose.runtime.MutableState] — Compose shouldn't recompose on View
 * mutation, and storing a mutable View in snapshot state is an anti-pattern.
 *
 * Public state ([statusFlow], [exportStateFlow]) is exposed via [StateFlow], which
 * safely marshals SDK-thread writes onto the Compose snapshot system via
 * [collectAsState] without triggering a recomposition per write at 30+Hz.
 */
@Stable
private class Insta360PlayerState {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    internal var view: InstaVideoPlayerView? = null
        private set

    @Volatile
    private var workWrapper: WorkWrapper? = null

    // Signals view attachment so a [loadAndPlay] coroutine that started before
    // `AndroidView.factory` ran can await the view instead of racing and giving up.
    private val _viewReady = MutableStateFlow(false)

    private val _statusFlow = MutableStateFlow("Loading…")
    val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

    private val _exportStateFlow = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportStateFlow: StateFlow<ExportState> = _exportStateFlow.asStateFlow()

    fun attachView(view: InstaVideoPlayerView, lifecycle: Lifecycle) {
        this.view = view
        _viewReady.value = true
        view.setLifecycle(lifecycle)
        view.setPlayerViewListener(object : PlayerViewListener {
            override fun onLoadingStatusChanged(isLoading: Boolean) {
                Log.d(TAG, "onLoadingStatusChanged $isLoading")
            }
            override fun onFirstFrameRender() { Log.d(TAG, "onFirstFrameRender") }
            override fun onLoadingFinish() { Log.d(TAG, "onLoadingFinish") }
            override fun onFail(errorCode: Int, errorMsg: String) {
                Log.e(TAG, "onFail $errorCode $errorMsg")
                _statusFlow.value = "Error $errorCode: $errorMsg"
            }
        })
        view.setGestureListener(object : PlayerGestureListener {
            override fun onTap(e: MotionEvent): Boolean {
                val v = this@Insta360PlayerState.view ?: return false
                if (v.isPrepared) {
                    if (v.isPlaying) v.pause() else if (!v.isLoading && !v.isSeeking) v.resume()
                }
                return false
            }
        })
    }

    /**
     * Load the Insta360 metadata footer from [filePath] off-main and start playback.
     * Safe to call repeatedly; each invocation should follow a cancellation of the
     * prior coroutine (Compose handles this via [LaunchedEffect] keyed on filePath).
     */
    suspend fun loadAndPlay(filePath: String) {
        // Wait for the AndroidView.factory to call attachView. On first composition
        // the LaunchedEffect and the factory can race; this collapses the race into
        // a deterministic wait so the coroutine never silently gives up.
        _viewReady.filter { it }.first()
        val view = this.view ?: return
        _statusFlow.value = "Loading metadata…"
        try {
            val work = withContext(Dispatchers.IO) {
                WorkWrapper(arrayOf(filePath)).also { wrapper ->
                    // loadExtraData is a blocking JNI call; it doesn't honour Kotlin
                    // cancellation. Re-check ensureActive after it returns so we don't
                    // keep going if the composable left before we resumed.
                    if (!wrapper.isExtraDataLoaded) wrapper.loadExtraData()
                }
            }
            coroutineContext.ensureActive()
            workWrapper = work
            if (!work.isVideo) {
                _statusFlow.value = "File is not a video (spike plays video only)"
                return
            }
            view.prepare(work, VideoParamsBuilder())
            view.play()
            _statusFlow.value = "Playing ${work.width}×${work.height} @ ${work.fps}fps"
        } catch (t: Throwable) {
            coroutineContext.ensureActive() // rethrow cancellation instead of swallowing it
            Log.e(TAG, "Failed to prepare player", t)
            _statusFlow.value = "Error: ${t.message}"
        }
    }

    fun canExport(): Boolean = workWrapper?.isVideo == true

    /**
     * Start an export. Progress callbacks arrive on the SDK's encoder thread; we
     * marshal terminal + throttled progress events onto the main thread before
     * writing to [_exportStateFlow]. This prevents recomposition storms at the
     * 30+Hz cadence the SDK emits.
     */
    fun startExport(cacheDir: File) {
        val work = workWrapper ?: return
        val target = File(cacheDir, "export_${System.currentTimeMillis()}.mp4").absolutePath
        postToMain { _exportStateFlow.value = ExportState.Running(0f) }
        ExportUtils.exportVideo(
            work,
            ExportVideoParamsBuilder().apply {
                targetPath = target
                exportMode = ExportUtils.ExportMode.PANORAMA
                width = work.width
                height = work.height
                fps = work.fps.toInt()
                bitrate = work.bitrate
            },
            object : IExportCallback {
                override fun onStart(id: Int) {
                    Log.d(TAG, "Export onStart id=$id → $target")
                }
                override fun onProgress(progress: Float) {
                    postToMain { _exportStateFlow.value = ExportState.Running(progress) }
                }
                override fun onSuccess() {
                    Log.d(TAG, "Export onSuccess $target")
                    postToMain { _exportStateFlow.value = ExportState.Succeeded(target) }
                }
                override fun onFail(code: Int, msg: String?) {
                    Log.e(TAG, "Export onFail $code $msg")
                    postToMain { _exportStateFlow.value = ExportState.Failed("$code: $msg") }
                }
                override fun onCancel() {
                    postToMain { _exportStateFlow.value = ExportState.Idle }
                }
            },
        )
    }

    /** Called from AndroidView.onRelease. Cleans up native resources. */
    fun release() {
        _viewReady.value = false
        view?.destroy()
        view = null
        workWrapper = null
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}

/** Throttle [ExportState.Running] emissions to once per whole-percent change. */
private fun StateFlow<ExportState>.throttlingRunningProgress() =
    distinctUntilChanged { old, new ->
        old is ExportState.Running && new is ExportState.Running &&
            (old.progress * 100).toInt() == (new.progress * 100).toInt()
    }

@Composable
fun Insta360PlayerView(
    filePath: String,
    modifier: Modifier = Modifier,
    onExported: (String) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context: Context = LocalContext.current
    val state = remember { Insta360PlayerState() }
    val status by state.statusFlow.collectAsState()
    val exportState by state.exportStateFlow.throttlingRunningProgress()
        .collectAsState(initial = ExportState.Idle)

    // Single-keyed LaunchedEffect. The View is attached via AndroidView.factory;
    // loadAndPlay tolerates being called before attach (no-ops) and after attach.
    // Rotation disposes AndroidView (triggers onRelease → release()), the View is
    // recreated, and this effect is re-triggered by Compose's LaunchedEffect contract.
    LaunchedEffect(filePath, state) { state.loadAndPlay(filePath) }

    Column(modifier = modifier) {
        Text(status, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                InstaVideoPlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    state.attachView(this, lifecycleOwner.lifecycle)
                }
            },
            onRelease = { state.release() },
        )
        ModeButtons(state)
        ExportRow(
            exportState = exportState,
            enabled = state.canExport(),
            onExport = { state.startExport(context.cacheDir) },
            onPlayExported = onExported,
        )
    }
}

@Composable
private fun ModeButtons(state: Insta360PlayerState) {
    // Mode switches target the native view directly; state holder exposes a helper
    // (not a StateFlow) because these are fire-and-forget side effects.
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Button(onClick = { state.withView { switchNormalMode() } }) { Text("Normal") }
        Button(onClick = { state.withView { switchFisheyeMode() } }) { Text("Fisheye") }
        Button(onClick = { state.withView { switchPerspectiveMode() } }) { Text("Perspective") }
    }
}

/** Fire-and-forget action on the attached View; no-op if View has been released. */
private inline fun Insta360PlayerState.withView(action: InstaVideoPlayerView.() -> Unit) {
    view?.action()
}

@Composable
private fun ExportRow(
    exportState: ExportState,
    enabled: Boolean,
    onExport: () -> Unit,
    onPlayExported: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        when (val s = exportState) {
            is ExportState.Idle -> {
                Button(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Export to MP4 (PANORAMA 2:1)")
                }
            }
            is ExportState.Running -> {
                Text("Exporting… ${(s.progress * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = { s.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            is ExportState.Succeeded -> {
                Text("Exported: ${File(s.path).name}", color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = { onPlayExported(s.path) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text("Play exported MP4")
                }
            }
            is ExportState.Failed -> {
                Text("Export failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry export")
                }
            }
        }
    }
}
