package com.altgallery.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_embeddings",
    indices = [Index(value = ["contentUri"], unique = true)],
)
data class ImageEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentUri: String,
    val vector: ByteArray,   // float32[384] serialized little-endian
) {
    // ByteArray needs structural equals/hashCode (Room emits a warning otherwise).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageEmbedding) return false
        return id == other.id &&
            contentUri == other.contentUri &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + contentUri.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
