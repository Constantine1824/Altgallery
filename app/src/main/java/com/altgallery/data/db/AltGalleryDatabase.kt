package com.altgallery.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.altgallery.data.model.Folder
import com.altgallery.data.model.ImageEmbedding
import com.altgallery.data.model.ImageMetadata
import com.altgallery.data.model.ImageMetadataFts

@Database(
    entities = [
        ImageMetadata::class,
        ImageEmbedding::class,
        Folder::class,
        ImageMetadataFts::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AltGalleryDatabase : RoomDatabase() {
    abstract fun imageMetadataDao(): ImageMetadataDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun folderDao(): FolderDao

    companion object {
        const val NAME = "altgallery.db"
    }
}
