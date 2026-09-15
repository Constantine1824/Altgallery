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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altgallery.data.repository.MetadataRepository
import com.altgallery.permissions.MediaPermissions
import com.altgallery.ui.theme.AltGalleryTheme
import com.altgallery.work.IndexingScheduler
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {
    val indexedCount = metadataRepository.observeIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
private fun PlaceholderHome(
    viewModel: PlaceholderViewModel = hiltViewModel(),
    onPermissionGranted: () -> Unit,
) {
    val count by viewModel.indexedCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Null = not yet asked this session; false = denied (no nagging, just a
    // retry button — opening the app after a settings grant still starts via
    // the granted branch below). Survives rotation so a denial is asked once.
    var granted by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        if (isGranted) onPermissionGranted()
    }
    LaunchedEffect(Unit) {
        if (MediaPermissions.hasReadImages(context)) {
            granted = true
            onPermissionGranted()
        } else if (granted == null) {
            launcher.launch(MediaPermissions.readImages)
        }
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
        when (granted) {
            true -> Text(
                text = "Indexing your library…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            false -> {
                Text(
                    text = "Photo access is off, so nothing is indexing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Button(onClick = { launcher.launch(MediaPermissions.readImages) }) {
                    Text("Grant photo access")
                }
            }
            null -> Unit
        }
    }
}
}
