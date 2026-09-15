package com.altgallery.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.altgallery.data.model.Folder
import com.altgallery.data.model.ImageEmbedding
import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts
import com.altgallery.data.model.ProcessingFailure

@Database(
    entities = [
        ImageMetadata::class,
        ImageEmbedding::class,
        Folder::class,
        ImageMetadataFts::class,
        ProcessingFailure::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AltGalleryDatabase : RoomDatabase() {
    abstract fun imageMetadataDao(): ImageMetadataDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun folderDao(): FolderDao
    abstract fun processingFailureDao(): ProcessingFailureDao

    companion object {
        const val NAME = "altgallery.db"
    }
}
