# AltGallery (PixIt) — On-Device AI Photo Organizer

AltGallery indexes your photo library **entirely on-device** — OCR, image
labels, meme detection, captions, and text embeddings — so you can
semantically search photos by describing what you vaguely remember.
No cloud, no uploads, works offline after a one-time model setup.

> Status: data layer, media access, ML engines, indexing pipeline, and
> background execution are implemented (M1–M4). Folder clustering, hybrid
> search, and the full UI (M5–M7) plus final polish (M8) are next.
> See [Project status](#project-status) and [Roadmap](#roadmap).

---

## Contents

- [How it works](#how-it-works)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Data model](#data-model)
- [Processing pipeline](#processing-pipeline)
- [Background execution](#background-execution)
- [Failure handling](#failure-handling)
- [Build & run](#build--run)
- [Models](#models)
- [Testing](#testing)
- [Permissions](#permissions)
- [Project status](#project-status)
- [Roadmap](#roadmap)
- [Known limitations](#known-limitations)

---

## How it works

```
Photo library (MediaStore)
  →  IndexingWorker (WorkManager, foreground, sliced)
  →  per photo: OCR + labels → meme check → caption → embedding → tags
  →  atomic record (metadata + embedding + FTS) in Room
  →  (next) folders + hybrid semantic/text search in Compose UI
```

Each photo ends up as one complete Room record with a 384-dim embedding
vector. Search (M6) will embed the query, rank by cosine similarity, and
boost hits that also match full-text search.

## Tech stack

| Layer        | Technology                                          |
|--------------|-----------------------------------------------------|
| Language     | Kotlin, min SDK 26, target SDK 34                   |
| UI           | Jetpack Compose + Material 3 (placeholder home now) |
| DI           | Hilt (incl. Hilt WorkManager factory)               |
| Database     | Room (metadata, embeddings, FTS4, folders, failures)|
| Background   | WorkManager (`dataSync` foreground slices)          |
| OCR / labels | ML Kit Text Recognition v2 + Image Labeling         |
| Embeddings   | ONNX Runtime Mobile, quantized MiniLM-L6-v2         |
| Caption      | Label/OCR-composed MVP (`OnnxCaptionEngine` slated) |
| Images       | Coil 3 · State: ViewModel + StateFlow               |

## Project structure

```
app/src/main/java/com/altgallery/
├── AltGalleryApplication.kt      # Hilt app, WorkManager config, cold-start enqueue
├── MainActivity.kt               # placeholder home + permission + status wiring
├── data/
│   ├── db/                       # Room: database, 5 DAOs, vector converters
│   ├── model/                    # ImageMetadata, ImageEmbedding, Folder,
│   │                             #   ImageMetadataFts, MediaImage, ProcessingFailure
│   └── repository/               # MediaRepository (MediaStore),
│                                 #   MetadataRepository (saves, done-definition, failures)
├── ml/                           # OcrEngine, LabelEngine, MemeClassifier, TagExtractor,
│   ├── onnx/                     #   BitmapLoader, Caption/Embedding engines,
│   │                             #   OnnxEmbeddingEngine, WordPieceTokenizer,
│   │                             #   OnnxCaptionEngine (scaffold), ModelAssets
├── processing/                   # ImageProcessor, RecordAssembler, IndexPolicy,
│                                 #   BatchRunner, IndexingPipeline (+ SliceResult),
│                                 #   StaleSnapshotException
├── work/                         # IndexingScheduler, IndexingWorker,
│                                 #   RunDecision (throw table), RunSlice (budgets)
├── permissions/                  # SDK-correct media-read permission
├── di/                           # Hilt modules
└── ui/theme/                     # Compose Material 3 theme (dark)
```

## Data model

- `image_metadata` — one row per photo, keyed by content URI: names, dates,
  dims, MIME type, meme flag, generated description, OCR text, labels, tags,
  cluster id, timestamps, model version, embedding link.
- `image_embeddings` — one 384-float vector per photo (stored as bytes).
- `image_metadata_fts` — FTS4 shadow table for text search fallback.
- `folders` — cluster/folder rows (populated by M5).
- `processing_failures` — one row per unindexable photo: URI, filename,
  reason, attempts, timestamp. Survives process death; cleared on recovery.

## Processing pipeline

Per photo, in order: load bitmap → ML Kit OCR + labels → meme heuristic
(2+ of 6 signals: overlaid-text area, uppercase impact font, square aspect,
"Text" label, caption bands, panel structure) → caption (MVP: composed from
labels + OCR) → MiniLM embedding of the full description → tags → **one
atomic Room transaction**. Any stage throwing means nothing is written, so
the photo simply stays pending.

**Done is strict**: a photo counts as indexed only when its *full* record
landed (metadata + embedding row + FTS row, linked, non-blank description)
*and* it still matches the library (dims, capture date, file mtime with
second-quantized comparison). Partial rows from killed runs stay eligible;
edited photos requeue automatically. A pre-save snapshot re-read discards
results when the photo changed mid-processing.

Batch policy: one bad photo never stops the run — up to 3 spaced attempts
(250/500ms pauses) each, then recorded with filename and reason while the
run moves on. A missing model file aborts once, globally, instead of once
per photo.

## Background execution

Indexing starts on every cold start and right after photo access is granted
(unique work, KEEP dedup so the second trigger is a no-op — opening the app
never cancels an in-flight run). Because WorkManager stops executions at
~10 minutes, long libraries run as **sliced foreground chains**:

- ≤ **50 photos** and ≤ **8 minutes** per execution, as a `dataSync`
  foreground service with a progress notification;
- the stop flag is checked before each photo, each retry, and each pause —
  overrun past the budget is at most one in-flight attempt;
- unfinished passes append a continuation that skips already-logged URIs
  (a bad head can never starve the tail) and appends its own failures;
- a system kill gets a best-effort continuation; force-stop falls back to
  the next cold start, which skips completed photos and retries the rest.

Run-level throws follow a pinned table: missing models settle quietly,
transient errors retry with exponential backoff up to 5 executions, then
fail instead of looping — the next cold start grants a fresh budget.

## Failure handling

| Situation                  | Behavior                                              |
|----------------------------|-------------------------------------------------------|
| Corrupt/unreadable photo   | 3 attempts, recorded, run continues                   |
| Transient (locked file…)   | Spaced retries; whole-run retry on run-level failure  |
| Models missing             | Single clear error, no per-photo spam, no retry loop  |
| Photo edited mid-run       | Result discarded, stays pending for next run          |
| Run killed (cap/constraints)| Continuation resumes; cold start is the backstop      |
| Permission denied          | Worker no-ops; home screen still loads its count      |

## Build & run

Authored write-only (no Android SDK on the authoring machine), so open the
project root in **Android Studio** (Koala 2024.1.1+) and let Gradle sync —
Studio fetches Gradle 8.9 and generates the wrapper. CLI alternative with a
local Gradle 8.9: `gradle wrapper` once, then `./gradlew assembleDebug`.
Toolchain: JDK 17, AGP 8.6.0, Kotlin 2.0.20, KSP. Run on a device or
emulator (Android 8+).

```bash
./gradlew test          # plain-JVM unit tests (no device needed)
./gradlew assembleDebug # debug APK
```

## Models

No model files ship in the repo or APK. `ml/onnx/ModelAssets.kt` resolves
`<filesDir>/models/<name>` first, then `assets/<name>`:

| File           | Used for              | Required     |
|--------------|-----------------------|--------------|
| `minilm.onnx` | Sentence embeddings   | for search   |
| `vocab.txt`  | Embedding tokenizer   | with above   |
| `caption.onnx`| Image captioning     | no (MVP composes from labels + OCR) |

Until the embedding files are present, semantic search waits but everything
else builds and runs. Hand-install on a device/emulator:

```bash
adb shell run-as com.altgallery mkdir -p files/models
adb push minilm.onnx /data/local/tmp/ && adb shell run-as com.altgallery cp /data/local/tmp/minilm.onnx files/models/
adb push vocab.txt   /data/local/tmp/ && adb shell run-as com.altgallery cp /data/local/tmp/vocab.txt   files/models/
```

A proper one-time in-app download with checksum verification is M8 scope.

## Testing

- **Plain-JVM tests** (`app/src/test`): pipeline decisions without a device
  — record assembly, save-transaction ordering, done-definition and pending
  computation, snapshot guard, batch policy and retry spacing, run wiring,
  throw→disposition table, slice budgets and stop semantics.
- **On-device acceptance** (per ticket, needs a device): large first-run
  library completion, rotation during a run, deny→grant flow, kill and
  force-stop resume, retry/backoff behavior.
- WorkManager enqueue behavior and end-to-end flows are device-verified by
  design; the JVM tests pin every pure decision underneath them.

## Permissions

`READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (≤32) ·
`INTERNET` (model download) · `FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_DATA_SYNC` (indexing slices) · `POST_NOTIFICATIONS`.

## Project status

- [x] **M1** scaffold + data layer
- [x] **M2** MediaStore access + permissions
- [x] **M3** ML engines (real ONNX captioning deferred by decision)
- [x] **M4** batch pipeline + WorkManager worker (merged as INDX-001…004)
- [ ] **M5** rule-based folder clustering (k-means stretch)
- [ ] **M6** hybrid search (cosine + FTS)
- [ ] **M7** UI screens (Home → Folder → Detail → Search → Processing)
- [ ] **M8** polish (onboarding, model download, edge cases, icon)

## Roadmap

Suggested order: on-device verification of M4 → M6 search (it validates the
whole pipeline — embeddings and FTS finally get read) → M5 clustering → M7
UI (all data dependencies exist by then) → M8 polish. Consider pulling a
minimal models-missing onboarding screen forward: fresh installs currently
no-op indexing with only a status line to explain why.

## Known limitations

- INDX-004 was reviewed by inspection; JVM + on-device test runs are owed.
- One slow inference attempt cannot be preempted (overrun ≈ one attempt).
- Failed photos retry on the next app open, not immediately (deliberate:
  one attempt per photo per pass).
- A fresh slice's log replace briefly hides prior failures until retried.
- `POST_NOTIFICATIONS` is declared but never requested.
- Launcher icon is a framework placeholder; theme is dark-only by design.
