# PixIt — On-Device AI Photo Organizer for Android

## Overview

PixIt is an Android application that uses on-device ML models to automatically classify, describe, and organize a user's photo library. The core value proposition: users can semantically search their photos by describing what they vaguely remember, and the app finds matching images instantly — no cloud, no internet required after initial setup.

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                 │
│  Jetpack Compose UI (Folders → Grid → Detail → Search)│
└──────────────┬───────────────────────────┬───────────┘
               │                           │
┌──────────────▼───────────┐ ┌─────────────▼───────────┐
│     SEARCH ENGINE        │ │    FOLDER / CLUSTER      │
│  Sentence-Transformer    │ │    MANAGER               │
│  Embeddings + Cosine     │ │    Rule-based +          │
│  Similarity              │ │    Optional K-Means      │
└──────────────┬───────────┘ └─────────────┬───────────┘
               │                           │
┌──────────────▼───────────────────────────▼───────────┐
│                 METADATA STORE                        │
│  Room Database (image_metadata, embeddings, folders)  │
└──────────────┬───────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────┐
│              PROCESSING PIPELINE                     │
│  WorkManager Background Tasks                        │
│  ┌─────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ ML Kit  │  │ ONNX     │  │ Sentence-        │   │
│  │ OCR +   │→ │ Captioner│→ │ Transformer      │   │
│  │ Labels  │  │ (BLIP/   │  │ Embedding        │   │
│  │         │  │ Florence)│  │ (MiniLM-L6-v2)   │   │
│  └─────────┘  └──────────┘  └──────────────────┘   │
└──────────────┬──────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────┐
│              ANDROID MEDIA ACCESS                    │
│  MediaStore API (content:// URIs, scoped storage)    │
└─────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin | Target SDK 34, min SDK 26 (Android 8+) |
| UI | Jetpack Compose + Material 3 | Single-activity, Compose Navigation |
| DI | Hilt | Standard Android DI |
| Database | Room | Local metadata + FTS for text search fallback |
| Background | WorkManager | Batch processing that survives app kills |
| OCR | ML Kit Text Recognition v2 | On-device, free, no API key |
| Image Labeling | ML Kit Image Labeling | Generic labels (person, food, text, etc.) |
| Image Captioning | ONNX Runtime Mobile | Run quantized BLIP-2 / Florence-2 / MoondreamTiny |
| Embeddings | ONNX Runtime Mobile | all-MiniLM-L6-v2 quantized (~22MB) |
| Image Loading | Coil 3 | Compose-native, content URI support |
| State | ViewModel + StateFlow | Unidirectional data flow |

---

## Data Model

### Room Database Schema

```kotlin
@Entity(tableName = "image_metadata")
data class ImageMetadata(
    @PrimaryKey val contentUri: String,        // content://media/external/images/media/{id}
    val displayName: String,                    // IMG_2041.jpg
    val filePath: String?,                      // nullable — scoped storage may not expose
    val dateTaken: Long,                        // epoch millis
    val width: Int,
    val height: Int,
    val mimeType: String,                       // image/jpeg, image/png
    val isMeme: Boolean,                        // classifier output
    val description: String,                    // generated caption
    val ocrText: String?,                       // extracted text if any
    val labels: String,                         // comma-separated ML Kit labels
    val tags: String,                           // comma-separated derived tags
    val clusterId: String,                      // folder assignment
    val processedAt: Long,                      // when this was indexed
    val modelVersion: String,                   // e.g. "pixit-v0.1"
    val embeddingId: Long?                      // FK to embeddings table
)

@Entity(tableName = "image_embeddings")
data class ImageEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentUri: String,
    val vector: ByteArray                       // float32 array serialized to bytes (384-dim)
)

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey val id: String,                 // "memes", "screenshots", etc.
    val displayName: String,
    val icon: String,                           // emoji or material icon name
    val autoRule: String?,                       // rule that populates this folder
    val imageCount: Int,
    val createdAt: Long
)
```

### JSON Export Format (per image)

```json
{
  "content_uri": "content://media/external/images/media/12345",
  "display_name": "IMG_2041.jpg",
  "is_meme": true,
  "description": "Drake meme format comparing two programming approaches...",
  "ocr_text": "Writing documentation | Adding TODO comments",
  "labels": ["person", "text", "humor"],
  "tags": ["drake", "programming", "documentation", "meme"],
  "cluster_id": "memes",
  "processed_at": "2024-04-15T14:32:00Z",
  "model_version": "pixit-v0.1"
}
```

---

## Processing Pipeline — Detailed

### 1. Image Discovery

```kotlin
// Query MediaStore for all images not yet processed
val projection = arrayOf(
    MediaStore.Images.Media._ID,
    MediaStore.Images.Media.DISPLAY_NAME,
    MediaStore.Images.Media.DATE_TAKEN,
    MediaStore.Images.Media.WIDTH,
    MediaStore.Images.Media.HEIGHT,
    MediaStore.Images.Media.MIME_TYPE
)
val cursor = contentResolver.query(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    projection,
    null, null,
    "${MediaStore.Images.Media.DATE_TAKEN} DESC"
)
// Build content URI: ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
```

### 2. Batch Partitioning

Split discovered images into batches of 100. Each batch is an independent WorkManager `Worker` chained sequentially. This allows:
- Progress tracking per batch
- Pause/resume capability
- Crash recovery (failed batch retries, completed batches are skipped)

```kotlin
val batches = allImageUris.chunked(100)
var continuation = WorkManager.getInstance(context)
    .beginWith(createBatchWorker(batches[0], batchIndex = 0))
for (i in 1 until batches.size) {
    continuation = continuation.then(createBatchWorker(batches[i], batchIndex = i))
}
continuation.enqueue()
```

### 3. Per-Image Processing (inside each batch)

For each image in the batch, run these steps sequentially:

#### Step 3a: ML Kit — OCR + Labels

```kotlin
// OCR
val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
val inputImage = InputImage.fromContentUri(context, uri)
val ocrResult = recognizer.process(inputImage).await()
val extractedText = ocrResult.text  // may be empty

// Image Labeling
val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
val labels = labeler.process(inputImage).await()
val labelList = labels.map { it.text }  // ["Person", "Food", "Text", ...]
```

#### Step 3b: Meme Classification

Heuristic-based, not a trained classifier. A photo is classified as a meme if it meets 2+ of these criteria:

1. **Has overlaid text**: OCR returns text AND the text bounding boxes cover >15% of image area
2. **Impact-style font**: OCR text is predominantly uppercase
3. **Standard meme aspect ratios**: Square (1:1) or near-square, common in meme formats
4. **Has "text" label**: ML Kit labels include "Text" with high confidence
5. **White/black border regions**: Top and/or bottom bands of solid color (caption meme format)
6. **Repeating panel structure**: Image can be segmented into 2-4 roughly equal vertical or horizontal panels

```kotlin
fun classifyAsMeme(
    ocrResult: Text,
    labels: List<ImageLabel>,
    imageWidth: Int,
    imageHeight: Int,
    bitmap: Bitmap
): Boolean {
    var score = 0
    val textAreaRatio = calculateTextAreaRatio(ocrResult, imageWidth, imageHeight)
    if (textAreaRatio > 0.15) score++
    if (ocrResult.text.uppercase() == ocrResult.text && ocrResult.text.length > 5) score++
    val aspectRatio = imageWidth.toFloat() / imageHeight
    if (aspectRatio in 0.8f..1.3f) score++
    if (labels.any { it.text == "Text" && it.confidence > 0.7f }) score++
    if (hasSolidBorderBands(bitmap)) score++
    return score >= 2
}
```

#### Step 3c: Description Generation (ONNX Captioning Model)

Load a quantized image captioning model via ONNX Runtime Mobile. Model options by size/quality tradeoff:

| Model | Size (quantized) | Quality | Speed (mid-range phone) |
|---|---|---|---|
| MoondreamTiny | ~200MB | Good for simple scenes | ~2-4s/image |
| Florence-2-base | ~450MB | Better detail | ~4-8s/image |
| BLIP-2 (OPT-2.7b) | ~1.2GB | Best quality | ~8-15s/image |

Recommended starting point: **MoondreamTiny** for MVP, upgrade path to Florence-2.

```kotlin
// Pseudocode — actual implementation depends on chosen model
val session = OrtEnvironment.getEnvironment()
    .createSession(assetManager.open("moondream_q8.onnx"))

fun generateCaption(bitmap: Bitmap): String {
    val inputTensor = preprocessImage(bitmap)  // resize, normalize per model spec
    val results = session.run(mapOf("pixel_values" to inputTensor))
    val tokenIds = results[0].value as LongArray
    return tokenizer.decode(tokenIds)
}
```

The generated caption becomes the `description` field. Append OCR text to it for search:
```kotlin
val fullDescription = buildString {
    append(generatedCaption)
    if (ocrText.isNotBlank()) {
        append(" [TEXT IN IMAGE: $ocrText]")
    }
}
```

#### Step 3d: Embedding Generation

Embed the full description using a quantized sentence-transformer:

```kotlin
// all-MiniLM-L6-v2 quantized to int8, ~22MB
val embeddingSession = OrtEnvironment.getEnvironment()
    .createSession(assetManager.open("minilm_q8.onnx"))

fun embed(text: String): FloatArray {
    val encoded = tokenizer.encode(text)  // WordPiece tokenization
    val inputIds = OnnxTensor.createTensor(env, longArrayOf(*encoded.ids))
    val attentionMask = OnnxTensor.createTensor(env, longArrayOf(*encoded.attentionMask))
    val result = embeddingSession.run(mapOf(
        "input_ids" to inputIds,
        "attention_mask" to attentionMask
    ))
    val embeddings = result[0].value as Array<FloatArray>
    return meanPool(embeddings, encoded.attentionMask)  // 384-dim vector
}
```

#### Step 3e: Tag Extraction

Derive tags from labels + OCR + description:

```kotlin
fun extractTags(labels: List<String>, ocrText: String, description: String): List<String> {
    val tags = mutableSetOf<String>()
    tags.addAll(labels.map { it.lowercase() })

    // Known meme template matching
    val memeTemplates = mapOf(
        "drake" to listOf("drake", "hotline", "approve", "reject"),
        "distracted boyfriend" to listOf("distracted", "boyfriend", "girlfriend", "looking"),
        "expanding brain" to listOf("brain", "expanding", "galaxy", "panels"),
        // ... more templates
    )
    val combined = "$ocrText $description".lowercase()
    for ((template, keywords) in memeTemplates) {
        if (keywords.count { it in combined } >= 2) {
            tags.add(template)
        }
    }

    // Extract nouns/entities from description (simple keyword extraction)
    val stopwords = setOf("the", "a", "an", "is", "in", "on", "at", "to", "of", "and", "with")
    description.lowercase().split(Regex("\\W+"))
        .filter { it.length > 3 && it !in stopwords }
        .take(10)
        .forEach { tags.add(it) }

    return tags.toList()
}
```

### 4. Folder Clustering

After processing, assign images to folders. Two-phase approach:

**Phase 1 — Rule-based assignment (always runs):**

```kotlin
val FOLDER_RULES = listOf(
    FolderRule("memes", "Memes", "😂") { it.isMeme },
    FolderRule("screenshots", "Screenshots", "📱") {
        it.displayName.startsWith("Screenshot", ignoreCase = true) ||
        it.labels.contains("screenshot")
    },
    FolderRule("selfies", "Selfies", "🤳") {
        it.labels.contains("selfie") || it.labels.contains("face")
    },
    FolderRule("receipts", "Receipts & Docs", "🧾") {
        it.ocrText != null && it.ocrText.length > 100 &&
        (it.ocrText.contains("total", ignoreCase = true) ||
         it.ocrText.contains("receipt", ignoreCase = true))
    },
)
```

**Phase 2 — Embedding-based clustering (optional, for remaining unclassified images):**

```kotlin
fun clusterRemaining(unclassified: List<ImageMetadata>, embeddings: Map<String, FloatArray>) {
    // Simple k-means on embedding vectors
    val k = estimateK(unclassified.size)  // heuristic: sqrt(n/2), capped at 10
    val vectors = unclassified.mapNotNull { embeddings[it.contentUri] }
    val clusters = kMeans(vectors, k, maxIterations = 50)

    // Name clusters by most common labels in each cluster
    for ((clusterIdx, members) in clusters) {
        val topLabels = members.flatMap { it.labels.split(",") }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(2)
        val folderName = topLabels.joinToString(" & ") { it.key.capitalize() }
        // Create folder and assign images
    }
}
```

---

## Search — Detailed

### Semantic Search Pipeline

```kotlin
class SearchEngine(
    private val embeddingModel: EmbeddingModel,
    private val db: PixItDatabase
) {
    suspend fun search(query: String, topK: Int = 20): List<SearchResult> {
        // 1. Embed the query
        val queryVector = embeddingModel.embed(query)

        // 2. Load all embeddings (for <10k images, this is fine in memory)
        val allEmbeddings = db.embeddingDao().getAll()

        // 3. Cosine similarity
        val scored = allEmbeddings.map { entry ->
            val similarity = cosineSimilarity(queryVector, entry.vector.toFloatArray())
            SearchResult(entry.contentUri, similarity)
        }

        // 4. Also do FTS text search as fallback/boost
        val ftsResults = db.metadataDao().searchFts("%${query}%")
        val ftsUris = ftsResults.map { it.contentUri }.toSet()

        // 5. Combine: boost items that match both semantic + text
        return scored.map { result ->
            if (result.contentUri in ftsUris) {
                result.copy(score = result.score * 1.3f)  // 30% boost for text match
            } else result
        }
        .sortedByDescending { it.score }
        .take(topK)
    }
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return dot / (sqrt(normA) * sqrt(normB) + 1e-8f)
}
```

### Search UX

- Results appear as the user types (debounced 300ms)
- Each result shows: thumbnail, filename, relevance score bar, meme/photo badge
- Tapping a result opens the detail view with full metadata
- Suggested searches based on most common tags in the library

---

## Project Structure

```
app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── moondream_q8.onnx          # captioning model
│   │   ├── minilm_q8.onnx             # embedding model
│   │   └── minilm_tokenizer.json      # tokenizer vocab
│   ├── java/com/pixit/
│   │   ├── PixItApplication.kt         # Hilt application
│   │   ├── MainActivity.kt            # Single activity, Compose entry
│   │   ├── di/
│   │   │   └── AppModule.kt           # Hilt modules (DB, models, repos)
│   │   ├── data/
│   │   │   ├── db/
│   │   │   │   ├── PixItDatabase.kt
│   │   │   │   ├── ImageMetadataDao.kt
│   │   │   │   ├── EmbeddingDao.kt
│   │   │   │   └── FolderDao.kt
│   │   │   ├── model/
│   │   │   │   ├── ImageMetadata.kt
│   │   │   │   ├── ImageEmbedding.kt
│   │   │   │   └── Folder.kt
│   │   │   └── repository/
│   │   │       ├── MediaRepository.kt      # MediaStore access
│   │   │       ├── MetadataRepository.kt   # Room CRUD
│   │   │       └── SearchRepository.kt     # Search logic
│   │   ├── ml/
│   │   │   ├── OcrEngine.kt               # ML Kit text recognition
│   │   │   ├── LabelEngine.kt            # ML Kit image labeling
│   │   │   ├── CaptionEngine.kt          # ONNX captioning model
│   │   │   ├── EmbeddingEngine.kt         # ONNX sentence-transformer
│   │   │   ├── MemeClassifier.kt          # Heuristic meme detection
│   │   │   └── TagExtractor.kt            # Tag derivation logic
│   │   ├── processing/
│   │   │   ├── BatchProcessor.kt          # Orchestrates per-image pipeline
│   │   │   ├── ProcessingWorker.kt        # WorkManager Worker
│   │   │   └── ClusterManager.kt          # Folder assignment + k-means
│   │   ├── search/
│   │   │   └── SearchEngine.kt            # Semantic + FTS hybrid search
│   │   └── ui/
│   │       ├── navigation/
│   │       │   └── PixItNavGraph.kt
│   │       ├── theme/
│   │       │   ├── Theme.kt
│   │       │   ├── Color.kt
│   │       │   └── Type.kt
│   │       ├── home/
│   │       │   ├── HomeScreen.kt          # Folder grid + stats + scan button
│   │       │   └── HomeViewModel.kt
│   │       ├── folder/
│   │       │   ├── FolderScreen.kt        # Image grid within folder
│   │       │   └── FolderViewModel.kt
│   │       ├── detail/
│   │       │   ├── DetailScreen.kt        # Full image + metadata + JSON
│   │       │   └── DetailViewModel.kt
│   │       ├── search/
│   │       │   ├── SearchScreen.kt        # Search bar + results
│   │       │   └── SearchViewModel.kt
│   │       └── processing/
│   │           ├── ProcessingScreen.kt    # Progress overlay during scan
│   │           └── ProcessingViewModel.kt
│   └── res/
│       ├── values/
│       │   ├── strings.xml
│       │   └── themes.xml
│       └── drawable/                      # App icon, placeholder
```

---

## Key Implementation Decisions

### Why on-device, not cloud?

1. **Privacy**: photos never leave the device.
2. **Cost**: no API bills for classification. Runs on user hardware.
3. **Offline**: works without internet after model download.
4. **Speed**: no network latency per image (model inference is the bottleneck).

### Why batch processing?

1. Processing 1000+ images sequentially would take 30-60 minutes. Batches of 100 let users see progress and pause.
2. WorkManager handles Android lifecycle (app kill, restart, battery optimization).
3. Failed batches retry independently without re-processing completed ones.

### Why hybrid search (semantic + FTS)?

Pure semantic search misses exact matches (someone searching "IMG_2041" wants filename match, not nearest embedding). FTS catches those. The 1.3x boost for dual-match items surfaces the most confident results.

### Model download strategy

ONNX models (~250MB total) should not ship in the APK. On first launch:
1. Show onboarding explaining the app and that a one-time model download is needed.
2. Download models to internal storage via `DownloadManager`.
3. Verify checksums.
4. Processing is only available after models are ready.

---

## Permissions Required

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />       <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />                                              <!-- API 26-32 -->
<uses-permission android:name="android.permission.INTERNET" />                 <!-- Model download -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />       <!-- Long processing -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />       <!-- Progress -->
```

---

## Build Order (for coding agent)

Implement in this order — each step is testable independently:

1. **Project scaffold**: Create Android project, add all Gradle dependencies, set up Hilt, Room, Navigation.
2. **Data layer**: Room database with all three entities, DAOs with insert/query/search methods.
3. **Media access**: `MediaRepository` that queries MediaStore, returns list of image URIs + metadata. Test: log all photos on device.
4. **ML engines** (one at a time):
   - `OcrEngine` wrapper around ML Kit. Test: feed a screenshot, get text.
   - `LabelEngine` wrapper around ML Kit. Test: feed a photo, get labels.
   - `MemeClassifier` heuristic. Test: feed meme screenshots vs normal photos, verify classification.
   - `CaptionEngine` ONNX model loader + inference. Test: feed image, get caption string.
   - `EmbeddingEngine` ONNX sentence-transformer. Test: embed two similar sentences, verify cosine similarity > 0.8.
5. **Processing pipeline**: `BatchProcessor` orchestrating all engines per image, `ProcessingWorker` for WorkManager.
6. **Clustering**: `ClusterManager` with rule-based assignment. K-means clustering is a stretch goal.
7. **Search**: `SearchEngine` with cosine similarity + FTS hybrid.
8. **UI screens** (in order): HomeScreen → FolderScreen → DetailScreen → SearchScreen → ProcessingScreen.
9. **Polish**: onboarding flow, model download, error handling, edge cases (corrupt images, permission denied, empty library).

---

## Stretch Goals

- **Duplicate detection**: compare embeddings, flag images with cosine similarity > 0.95 as duplicates.
- **Smart albums**: auto-generated albums like "This Week's Memes", "Food I Photographed", "Screenshots I Probably Don't Need".
- **Export metadata**: JSON export of entire library metadata for backup or migration.
- **Widget**: home screen widget showing random meme or recent photo.
- **Meme template recognition**: identify specific meme formats (drake, expanding brain, etc.) and tag accordingly.
- **Yoruba/pidgin OCR**: extend text recognition for Nigerian languages using custom ML Kit models or fine-tuned Tesseract.

---

## Dependencies (Gradle)

```kotlin
// build.gradle.kts (app)
dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ML Kit
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:image-labeling:17.0.8")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```

---

## Notes for the Agent

- All ML inference must run on background threads. Use `withContext(Dispatchers.Default)` for ONNX, `suspendCoroutine` wrappers for ML Kit callbacks.
- Content URIs from MediaStore should be stored as strings and reconstructed with `Uri.parse()`. Never store raw file paths.
- The embedding vector is 384 floats = 1536 bytes. For 10,000 images that is ~15MB in Room, which is fine.
- ONNX model files go in `assets/` for bundled or `filesDir` for downloaded. Use `OrtEnvironment.getEnvironment().createSession(byteArray)` for assets.
- Test meme classification with a diverse set: classic meme templates, text screenshots, photos with incidental text (street signs, menus), and pure photos. Target >85% precision on memes.
- The UI should feel fast. Folder navigation and search results should be instant (metadata is tiny). Only image loading (thumbnails via Coil) has latency.
