package com.altgallery.di

import com.altgallery.ml.CaptionEngine
import com.altgallery.ml.EmbeddingEngine
import com.altgallery.ml.LabelOcrCaptionEngine
import com.altgallery.ml.onnx.OnnxEmbeddingEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the ML engine interfaces to their default implementations.
 *
 * The concrete engines without an interface (OcrEngine, LabelEngine,
 * MemeClassifier, TagExtractor, BitmapLoader, ModelAssets) are injected directly
 * via their @Inject constructors and need no binding here.
 *
 * To upgrade captioning later, point [provideCaptionEngine] at your
 * OnnxCaptionEngine instead of [LabelOcrCaptionEngine].
 */
@Module
@InstallIn(SingletonComponent::class)
object MlModule {

    @Provides
    @Singleton
    fun provideEmbeddingEngine(impl: OnnxEmbeddingEngine): EmbeddingEngine = impl

    @Provides
    @Singleton
    fun provideCaptionEngine(impl: LabelOcrCaptionEngine): CaptionEngine = impl
}
