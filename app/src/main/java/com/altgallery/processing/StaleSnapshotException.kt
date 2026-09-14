package com.altgallery.processing

/**
 * The library row moved while the photo's own slow stages (OCR, caption,
 * embed) were running. The assembled record describes pre-edit bytes, and the
 * mtime-vs-processedAt freshness check cannot flag it (the edit predates the
 * write), so the run must write nothing and leave the photo pending for the
 * next pass. See [IndexPolicy.snapshotChanged].
 */
class StaleSnapshotException(uriString: String) :
    IllegalStateException("library row changed mid-run, will retry: $uriString")

/** The library row vanished mid-run (deleted or permission lost). Nothing to record. */
class PhotoGoneException(uriString: String) :
    IllegalStateException("photo gone mid-run: $uriString")
