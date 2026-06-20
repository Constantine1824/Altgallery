# Alt Gallery — build & handoff notes

On-device AI photo organizer for Android. Spec: `pixit-spec.md`. UI design reference: `pixit-prototype.jsx`.

## Opening the project

This project was authored write-only (no Android SDK on the authoring machine), so a
few binary/generated bits are intentionally absent — Android Studio regenerates them:

1. Open the project root in **Android Studio** (Koala / 2024.1.1 or newer).
2. Let Gradle sync. Studio will download the Gradle distribution declared in
   `gradle/wrapper/gradle-wrapper.properties` (8.9) and generate the wrapper JAR
   (`gradle/wrapper/gradle-wrapper.jar`) plus the `gradlew` / `gradlew.bat` scripts.
   - If you prefer the CLI and already have Gradle 8.9 installed:
     `gradle wrapper` once to materialize the wrapper, then `./gradlew assembleDebug`.
3. Build/run on a device or emulator (min SDK 26 / Android 8, target SDK 34).

## Toolchain expected

- JDK 17 (Studio's bundled JBR is fine)
- Android Gradle Plugin 8.6.0, Gradle 8.9, Kotlin 2.0.20
- KSP (replaces kapt for Hilt + Room — faster, current standard)

## Status — milestones

- [x] **M1 — Scaffold + data layer** (this commit)
  - Gradle (version catalog), Hilt, Compose, Navigation, WorkManager wiring
  - Room: `ImageMetadata`, `ImageEmbedding`, `Folder` + FTS4 table, DAOs, vector (de)serialization
  - Placeholder Home screen proving Hilt + Room + Compose compile and run together
- [x] **M2 — MediaStore access + runtime permissions**
  - `MediaRepository` queries MediaStore (newest-first, `queryUnprocessed` skip set), `MediaImage` holder
  - `MediaPermissions` resolves the SDK-correct read-media permission (request UI lands with Home in M7)
- [x] **M3 — ML engines** (OCR, labels, meme heuristic, embedding+tokenizer; caption = label/OCR default)
  - ML Kit `OcrEngine` / `LabelEngine`, heuristic `MemeClassifier`, `TagExtractor`, `BitmapLoader` — no model files
  - `EmbeddingEngine` ← `OnnxEmbeddingEngine` (MiniLM-L6, mean-pool + L2-norm) + `WordPieceTokenizer`
  - `CaptionEngine` ← `LabelOcrCaptionEngine` (model-free default); `OnnxCaptionEngine` is a scaffold for later
  - All model files are user-supplied via `ModelAssets` — see "Models (you supply these)" below
- [ ] M4 — Batch processing pipeline + WorkManager worker
- [ ] M5 — Rule-based folder clustering (k-means stretch)
- [ ] M6 — Hybrid search (cosine + FTS)
- [ ] M7 — UI screens (Home → Folder → Detail → Search → Processing)
- [ ] M8 — Polish (onboarding, model download, edge cases)

## Deferred by decision

- **Real ONNX image captioning** (BLIP-2 / Florence-2 / Moondream). The MVP composes
  `description` from ML Kit labels + OCR text and embeds that. The real captioner slots
  in behind the `CaptionEngine` interface (M3) when ready — it is a substantial,
  separate effort (autoregressive decode loop, tokenizer, model hosting/download).

## Models (you supply these)

No model files ship in the repo or APK. `ml/onnx/ModelAssets.kt` is the single
hub that resolves them, checking two locations in order:

1. `<filesDir>/models/<name>` — pushed/downloaded at runtime (preferred)
2. `app/src/main/assets/<name>` — bundled in the APK (optional)

| Logical model | File name | Used by | Required? |
|---|---|---|---|
| Sentence embeddings | `minilm.onnx` | `OnnxEmbeddingEngine` | for semantic search |
| Embedding vocab | `vocab.txt` | `WordPieceTokenizer` | with the above (bert-base-uncased WordPiece, one token/line) |
| Image captioner | `caption.onnx` | `OnnxCaptionEngine` (scaffold) | no — MVP uses the label/OCR captioner |

Until the embedding files are present, `EmbeddingEngine.isReady()` returns false
and `embed()` throws `ModelUnavailableException` — the app still builds and runs;
only semantic search waits. Install by hand on a device/emulator:

```
adb shell run-as com.altgallery mkdir -p files/models
adb push minilm.onnx /data/local/tmp/ && adb shell run-as com.altgallery cp /data/local/tmp/minilm.onnx files/models/
adb push vocab.txt   /data/local/tmp/ && adb shell run-as com.altgallery cp /data/local/tmp/vocab.txt   files/models/
```

Model-export specifics (ONNX input/output node names, MiniLM 384-dim assumption)
live as documented constants in `OnnxEmbeddingEngine.kt`; the only files to edit
if your export differs are that engine and `ModelAssets.kt`.

## Notes

- App is dark-only by design. The XML launch theme uses framework Material to avoid an
  AppCompat/Material-Components dependency; the in-app theme is Compose Material 3.
- Launcher icon is a temporary framework placeholder (`@android:drawable/sym_def_app_icon`);
  replace with a real adaptive icon in M8.
