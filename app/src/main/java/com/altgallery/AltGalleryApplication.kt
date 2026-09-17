package com.altgallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.altgallery.work.IndexingScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AltGalleryApplication : Application(), Configuration.Provider {

    // Supplied by Hilt so @HiltWorker-annotated workers can be constructor-injected.
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Every cold start converges to an indexed library: fresh installs
        // pick up the run as soon as permission is granted (the worker
        // no-ops until then), and reopening after a kill or force-stop
        // resumes the remaining photos — completed ones are skipped via the
        // already-processed definition. Idempotent, so always safe to ask.
        IndexingScheduler.enqueue(this)
    }
}
