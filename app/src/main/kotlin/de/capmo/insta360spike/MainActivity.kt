package de.capmo.insta360spike

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Both files live in src/main/assets. The .insv is the raw X4/X5 dual-fisheye
// format that exercises the SDK's sphere projection + on-device stitching.
// The .mp4 is a flat-stitched export kept as a secondary test file.
private const val BUNDLED_ASSET_NAME = "test.insv"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpikeScreen()
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen() {
    val context = LocalContext.current
    var localFilePath by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val copyScope = androidx.compose.runtime.rememberCoroutineScope()

    // Copy bundled asset → cacheDir once on first composition, then auto-play.
    LaunchedEffect(Unit) {
        if (localFilePath != null) return@LaunchedEffect
        try {
            val destination = File(context.cacheDir, BUNDLED_ASSET_NAME)
            withContext(Dispatchers.IO) {
                if (!destination.exists() || destination.length() == 0L) {
                    context.assets.open(BUNDLED_ASSET_NAME).use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            localFilePath = destination.absolutePath
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            errorMessage = "Failed to extract bundled asset: ${t.message}"
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        copyScope.launch {
            try {
                // Copy to app-private cache so the SDK gets a plain filesystem path.
                // Runs on IO so large files don't ANR the picker callback.
                val path = withContext(Dispatchers.IO) {
                    val displayName = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(
                                    android.provider.OpenableColumns.DISPLAY_NAME,
                                )
                                if (nameIndex >= 0) cursor.getString(nameIndex) else null
                            } else null
                        } ?: "picked.insv"
                    val destination = File(context.cacheDir, displayName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                    destination.absolutePath
                }
                localFilePath = path
                errorMessage = null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errorMessage = "Failed to copy file: ${t.message}"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Insta360 Spike", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = localFilePath?.let { "Loaded: ${File(it).name}" } ?: "No file selected",
            style = MaterialTheme.typography.bodyMedium,
        )
        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { pickFileLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Pick a different file (optional)")
        }
        Spacer(Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val path = localFilePath
            if (path != null) {
                Insta360PlayerView(
                    filePath = path,
                    modifier = Modifier.fillMaxSize(),
                    onExported = { exportedPath ->
                        // Swap in the freshly exported MP4 → player re-prepares automatically.
                        localFilePath = exportedPath
                    },
                )
            } else {
                Text(
                    text = "Pick a file to start playback.",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
