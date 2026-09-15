package com.altgallery.di

import android.content.Context
import androidx.room.Room
import com.altgallery.data.db.AltGalleryDatabase
import com.altgallery.data.db.EmbeddingDao
import com.altgallery.data.db.FolderDao
import com.altgallery.data.db.ImageMetadataDao
import com.altgallery.data.db.ProcessingFailureDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AltGalleryDatabase =
        Room.databaseBuilder(context, AltGalleryDatabase::class.java, AltGalleryDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideImageMetadataDao(db: AltGalleryDatabase): ImageMetadataDao = db.imageMetadataDao()

    @Provides
    fun provideEmbeddingDao(db: AltGalleryDatabase): EmbeddingDao = db.embeddingDao()

    @Provides
    fun provideFolderDao(db: AltGalleryDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideProcessingFailureDao(db: AltGalleryDatabase): ProcessingFailureDao =
        db.processingFailureDao()
}
