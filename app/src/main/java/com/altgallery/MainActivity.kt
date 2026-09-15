package com.altgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.Context
import com.altgallery.data.repository.MetadataRepository
import com.altgallery.permissions.MediaPermissions
import com.altgallery.ui.theme.AltGalleryTheme
import com.altgallery.work.IndexingScheduler
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AltGalleryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlaceholderHome(onPermissionGranted = { IndexingScheduler.enqueue(this) })
                }
            }
        }
    }
}

// Minimal screen proving the Hilt + Room + Compose stack is wired up.
// Replaced by the real HomeScreen in M7.
@HiltViewModel
class PlaceholderViewModel @Inject constructor(
    metadataRepository: MetadataRepository,
    @ApplicationContext appContext: Context,
) : ViewModel() {
    val indexedCount = metadataRepository.observeIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * True while an indexing run is enqueued or running. Drives the status
     * line so it never claims indexing when no run exists.
     */
    val indexing = WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(IndexingScheduler.UNIQUE_WORK_NAME)
        .map { infos ->
            infos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

@Composable
private fun PlaceholderHome(
    viewModel: PlaceholderViewModel = hiltViewModel(),
    onPermissionGranted: () -> Unit,
) {
    val count by viewModel.indexedCount.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Null = not yet asked this session; false = denied (no nagging, just a
    // retry button). Survives rotation so a denial is asked once.
    var granted by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Guards the schedule trigger across recreation: rotation must not
    // re-enqueue (REPLACE would cancel the in-flight run). Fresh processes
    // start false, so cold starts and fresh grants always schedule.
    var scheduled by rememberSaveable { mutableStateOf(false) }
    fun noteGranted() {
        granted = true
        if (!scheduled) {
            scheduled = true
            onPermissionGranted()
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) noteGranted() else granted = false
    }
    LaunchedEffect(Unit) {
        if (MediaPermissions.hasReadImages(context)) {
            noteGranted()
        } else if (granted == null) {
            launcher.launch(MediaPermissions.readImages)
        }
    }
    // A grant made in Settings while the activity lived returns here with no
    // recomposition trigger of its own; re-check on every resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && MediaPermissions.hasReadImages(context)) {
                noteGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "ALT:GALLERY",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "$count photos indexed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (granted == false) {
            Text(
                text = "Photo access is off, so nothing is indexing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Button(onClick = { launcher.launch(MediaPermissions.readImages) }) {
                Text("Grant photo access")
            }
        } else if (indexing) {
            Text(
                text = "Indexing your library…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
